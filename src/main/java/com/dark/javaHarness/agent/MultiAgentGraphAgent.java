package com.dark.javaHarness.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.internal.node.ParallelNode;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.dark.javaHarness.advisor.PromptBudgetAdvisor;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.prompt.PromptAssembler;
import com.dark.javaHarness.prompt.SkillManager;
import com.dark.javaHarness.prompt.ToolLazyManager;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 多 Agent 编排器：路径 B（复杂请求）的执行体。
 *
 * <p>基于 {@link StateGraph} 编排「Lead 拆解 → 并行子任务 → 聚合」三阶段，
 * 每个阶段都是一次独立的 ChatClient 单次调用（复用 {@link ChatClientRegistry} 的客户端），
 * 配置（模型 + 提示词）均取自 agent 表对应角色行：
 * - lead 节点：查 {@code lead} 行（无则内置兜底），把复杂目标拆成至多 {@link #MAX_SUBTASKS} 条子任务
 *   （{@link LeadOutputParser} 解析），并为每条子任务指派专家（researcher/coder/analyst/writer/general，
 *   白名单校验，非法回退）
 * - subtask-i 节点：并行执行，按指派的专家名查 agent 表配置取对应 ChatClient 产出该子任务结果
 * - aggregate 节点：查 {@code aggregator} 行（无则内置兜底），收集各子任务结果汇总成最终回答
 *
 * <p>与路径 A 的 {@link GeneralAssistantAgent} 对 Key 契约一致：
 * {@link #execute(Goal)} 返回最终回答 String；Goal 生命周期与会话记忆写回
 * 统一由 AgentService / ChatService 负责。
 *
 * <p>图拓扑只构建一次；同步执行 {@link #execute(Goal)} 走 invoke；
 * 流式执行 {@link #executeStreamReactive(Goal)} 走「stream 主干帧 + 生命周期钩子旁路」
 * 双通道管道（详见 {@link MultiAgentStreamPipeline}）；
 * 编排预算熔断与 lead 产物解析分别委托 {@link OrchestrationBudget}/{@link LeadOutputParser}。
 */
public class MultiAgentGraphAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentGraphAgent.class);

    /** 单个总任务拆解的子任务数上限，避免滚雪球 */
    private static final int MAX_SUBTASKS = 4;

    /** 节点名常量 */
    static final String NODE_LEAD = "lead";
    static final String NODE_AGGREGATE = "aggregate";

    /** 编排环节角色名（agent 表行名）：lead 拆解器、aggregator 聚合器，与编排器 multi-agent 行解耦 */
    private static final String ROLE_LEAD = "lead";
    private static final String ROLE_AGGREGATOR = "aggregator";

    /** 状态键 */
    private static final String K_OBJECTIVE = "objective";
    private static final String K_SESSION_ID = "sessionId";
    static final String K_SUBTASK_COUNT = "subtaskCount";
    static final String K_SUBTASK_PREFIX = "subtask_";
    private static final String K_SUBTASK_AGENT_PREFIX = "subtaskAgent_";
    private static final String K_RESULT_PREFIX = "result_";
    static final String K_FINAL = "final";

    /** 子任务节点名前缀 */
    static final String SUBTASK_NODE_PREFIX = "subtask-";

    /**
     * 预算超限跳过的子任务占位 result：聚合据此识别未执行子任务数（排除出真实结果），
     * 并在 prompt 注入降级说明；非空保证状态键有值、与「子任务失败无结果」区分。
     */
    static final String SKIPPED_RESULT = "（预算超限跳过：编排 token 消费已达上限）";

    private final String agentName;
    /** Prompt 组装器：子任务专家 persona 与各环节 system 段统一经此组装 */
    private final PromptAssembler promptAssembler;
    /** 编排环节 LLM 调用器：封装查表配置 / 客户端获取 / 请求组装 / 工具注入 */
    private final AgentChatCaller chatCaller;
    /** 图拓扑（构建一次）：同步执行缓存编译 {@link #graph}；流式执行每次带监听器重新编译 */
    private final StateGraph stateGraph;
    private final CompiledGraph graph;
    /**
     * 检查点存储器（可 null = 不启用断点续跑，如单测环境）：
     * 非 null 时每个 superstep 结束自动落库（threadId=goalId），
     * 支持 {@link #resumeStreamReactive(Goal)} 从断点继续（已完成节点不再重跑）。
     */
    private final BaseCheckpointSaver checkpointSaver;
    /** 上下文预算配置（lead/聚合静态 prompt 预算；null 时用内置默认值，单测场景） */
    private final ContextBudgetProperties budgets;
    /** 编排消费上限熔断：各节点共享的预算账本（依赖 {@link #budgets}） */
    private final OrchestrationBudget orchestrationBudget;
    /** 流式管道：主干帧 + 旁路合并（依赖 {@link #streamPipeline} 建图回调） */
    private final MultiAgentStreamPipeline streamPipeline;

    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder, null, null);
    }

    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                BaseCheckpointSaver checkpointSaver) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder, checkpointSaver, null);
    }

    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                BaseCheckpointSaver checkpointSaver,
                                ContextBudgetProperties budgets) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder, checkpointSaver, budgets, null);
    }

    /**
     * @param memoryStore 会话记忆源（SessionService，与路径 A GeneralAssistantAgent 同源同口径）：
     *                    lead 拆解节点据此注入会话记忆，null 时不注入（单测场景）
     */
    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                BaseCheckpointSaver checkpointSaver,
                                ContextBudgetProperties budgets,
                                SessionService memoryStore) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder,
                checkpointSaver, budgets, memoryStore, null);
    }

    /**
     * 全参构造：lazyTools 为 null 时构造禁用态实例（旧构造链/单测场景，工具面全量注入现状）。
     * 正式装配由 ChatAgentConfig 注入共享实例（app.prompt.lazy-tools.enabled 开关）——
     * 编排三节点（lead/子任务/聚合）共用本实例的 {@link AgentChatCaller} → 同一 sessionId
     * 的会话展开集共享。
     */
    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                BaseCheckpointSaver checkpointSaver,
                                ContextBudgetProperties budgets,
                                SessionService memoryStore,
                                ToolLazyManager lazyTools) {
        this(agentName, clientRegistry, agentService, toolAssignments, recorder,
                checkpointSaver, budgets, memoryStore, lazyTools, null, null);
    }

    /**
     * 全参构造（含 skill 装配）：promptAssembler/skillManager 为 null 时内部裸构建
     * （旧构造链/单测场景，无 skill 段、不注册 load_skill）；正式装配由 ChatAgentConfig
     * 注入共享实例——编排三节点与路径 A 共用同一组装器与 skill 技能面。
     */
    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder,
                                BaseCheckpointSaver checkpointSaver,
                                ContextBudgetProperties budgets,
                                SessionService memoryStore,
                                ToolLazyManager lazyTools,
                                PromptAssembler promptAssembler,
                                SkillManager skillManager) {
        ToolLazyManager lazy = lazyTools != null ? lazyTools : new ToolLazyManager(toolAssignments, false);
        this.agentName = agentName;
        // 工具索引段与延迟加载同源：开启时索引段追加 expand_tool 使用引导（与轻量态工具面对齐）
        this.promptAssembler = promptAssembler != null ? promptAssembler
                : new PromptAssembler(agentService, toolAssignments, List.of(), lazy.isEnabled());
        this.chatCaller = new AgentChatCaller(clientRegistry, agentService, toolAssignments, recorder,
                new LlmRetry(), budgets, this.promptAssembler, memoryStore, lazy, skillManager);
        this.checkpointSaver = checkpointSaver;
        this.budgets = budgets != null ? budgets : new ContextBudgetProperties();
        this.orchestrationBudget = new OrchestrationBudget(this.budgets);
        this.streamPipeline = new MultiAgentStreamPipeline(
                (liveTokens, contentSent, toolEvents, cancelled, listener) ->
                        buildStateGraph(liveTokens, contentSent, toolEvents, cancelled)
                                .compile(compileConfig(listener)));
        try {
            this.stateGraph = buildStateGraph();
            // 同步执行用的常驻实例（带检查点时每个 superstep 自动落库）
            this.graph = stateGraph.compile(compileConfig(null));
        } catch (GraphStateException e) {
            throw new IllegalStateException("构建/编译多 Agent 编排 StateGraph 失败", e);
        }
    }

    /** 编译配置：挂检查点存储器（可 null）+ 生命周期监听器（可 null）；releaseThread=false 保留检查点供续跑 */
    private CompileConfig compileConfig(com.alibaba.cloud.ai.graph.GraphLifecycleListener listener) {
        CompileConfig.Builder builder = CompileConfig.builder().releaseThread(false);
        if (checkpointSaver != null) {
            builder.saverConfig(SaverConfig.builder().register(checkpointSaver).build());
        }
        if (listener != null) {
            builder.withLifecycleListener(listener);
        }
        return builder.build();
    }

    /**
     * 执行用 RunnableConfig：threadId=goalId（检查点归属键）+ 子任务并行扇出限并发
     * （subtask-concurrency > 0 时经 ParallelNode 的 metadata 信号量排队错峰；
     * metadata 键与并行节点 id 对应关系见 {@link ParallelNode#formatMaxConcurrencyKey}）。
     */
    private RunnableConfig runnableConfig(String goalId) {
        return runnableConfig(goalId, null);
    }

    /** 同上，可带检查点 ID（断点续跑从该检查点恢复） */
    private RunnableConfig runnableConfig(String goalId, String checkPointId) {
        RunnableConfig.Builder builder = RunnableConfig.builder().threadId(goalId);
        if (checkPointId != null) {
            builder.checkPointId(checkPointId);
        }
        int concurrency = budgets.getSubtaskConcurrency();
        if (concurrency > 0) {
            // 并行节点 id 为 formatNodeId(NODE_LEAD)（__PARALLEL__(lead)），与其 metadata 键配对；
            // ParallelNode 位于 graph-core internal 包但类型/工厂方法公开，键名随上游联动
            builder.addMetadata(
                    ParallelNode.formatMaxConcurrencyKey(ParallelNode.formatNodeId(NODE_LEAD)),
                    concurrency);
        }
        return builder.build();
    }

    @Override
    public String name() {
        return agentName;
    }

    /** 编排 input 组装：objective/sessionId + 预算账本（开关开启时注入共享账本） */
    private Map<String, Object> inputOf(Goal goal) {
        Map<String, Object> input = new HashMap<>();
        input.put(K_OBJECTIVE, goal.objective());
        input.put(K_SESSION_ID, goal.sessionId());
        orchestrationBudget.putLedger(input);
        return input;
    }

    /** 执行复杂目标：把客观目标注入 StateGraph，返回最终回答。 */
    @Override
    public String execute(Goal goal) {
        log.info("[multi-agent] 开始编排复杂目标: {}", goal.objective());
        return graph.invoke(inputOf(goal), runnableConfig(goal.id()))
                .flatMap(s -> s.value(K_FINAL, String.class))
                .orElse(goal.objective());
    }

    /**
     * 流式执行复杂目标：stream 主干帧 + 双旁路（生命周期钩子 + 聚合 token）。
     *
     * <p>主干：{@link CompiledGraph#stream(Map)} 帧 → {@link MultiAgentStreamPipeline#toRows} 行；
     * 旁路与死锁教训详见 {@link MultiAgentStreamPipeline}。
     *
     * <p>子任务节点保持阻塞调用：多子任务并行执行，token 直推会交错乱序；用户体感关键
     * 在最终回答的打字机效果，由聚合节点承担。lead 产出为 JSON 中间产物，不推送 token。
     */
    @Override
    public Flux<String> executeStreamReactive(Goal goal) {
        return streamPipeline.run(inputOf(goal), goal.objective(), runnableConfig(goal.id()));
    }

    /**
     * 断点续跑：从该 goal 上次编排的检查点继续（threadId=goalId）。
     * 已完成节点（如 lead 拆解、已批量完成的子任务）不再重跑，只补执行缺口；
     * 聚合节点重新汇总（读检查点中已有的全量 result_*）。
     *
     * <p>校验在方法调用时同步完成（快速失败）：
     * 未启用检查点 / 无该 goal 的检查点记录时抛 {@link IllegalStateException}。
     *
     * <p>输出语义与 {@link #executeStreamReactive(Goal)} 完全一致（进度 + 打字机）。
     *
     * <p>graph-core 1.1.x 续跑触发条件是 config.checkPointId 非空
     * （GraphRunnerContext#initializeFromResume：state 自动合并 checkpoint 状态），
     * 而 saver.get() 对带 checkPointId 的 config 按 ID 精确匹配——
     * 因此先探测最新 checkpoint，再以其真实 ID 组装续跑 config。
     *
     * <p>恢复点选择（{@link MultiAgentStreamPipeline#selectResumeCheckpoint}）：
     * 编排已完成（final 有效）→ 从最终检查点零调用回放；
     * 否则回退到「子任务批完成、聚合前」的检查点（nextNodeId=aggregate）补跑聚合。
     */
    public Flux<String> resumeStreamReactive(Goal goal) {
        if (checkpointSaver == null) {
            throw new IllegalStateException("未启用检查点存储，无法续跑");
        }
        RunnableConfig probe = runnableConfig(goal.id());
        Checkpoint target;
        try {
            java.util.Collection<Checkpoint> checkpoints = checkpointSaver.list(probe);
            if (checkpoints.isEmpty()) {
                throw new IllegalStateException(
                        "无可续跑的编排：goal " + goal.id() + " 没有检查点（可能未走过复杂路径）");
            }
            target = MultiAgentStreamPipeline.selectResumeCheckpoint(checkpoints);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取检查点失败: " + goal.id(), e);
        }
        log.info("[multi-agent] 断点续跑 goal {}: 从检查点 {} 继续（nextNodeId={}，已完成节点不再重跑）",
                goal.id(), target.getId(), target.getNextNodeId());
        return streamPipeline.run(inputOf(goal), goal.objective(),
                runnableConfig(goal.id(), target.getId()));
    }

    /* ---------------- StateGraph 构建 ---------------- */

    /** 子任务节点名 */
    private static String subtaskName(int i) {
        return SUBTASK_NODE_PREFIX + i;
    }

    private StateGraph buildStateGraph() throws GraphStateException {
        return buildStateGraph(null, null, null, null);
    }

    /**
     * 构建「lead → 并行子任务 → 聚合」拓扑。
     *
     * @param liveTokens  非 null 时聚合节点走流式调用并把 token 旁路发射到该 sink（含首个 token 前的「聚合」进度行）；
     *                    null 时聚合节点阻塞调用（同步 execute 路径）
     * @param contentSent 流式模式的内容已发射标志（与主干 {@link MultiAgentStreamPipeline#toRows} 共享，防重复发射）；可为 null
     * @param toolEvents  非 null 时子任务节点注入追踪版工具（执行起止经该 sink 发进度行，供 CLI 工具调用行）
     * @param cancelled   非 null 时节点执行前检查该标志：客户端已断开则短路（不再发起新的 LLM 调用）
     */
    private StateGraph buildStateGraph(Sinks.Many<String> liveTokens,
                                       AtomicBoolean contentSent,
                                       Sinks.Many<String> toolEvents,
                                       AtomicBoolean cancelled) throws GraphStateException {
        // 注册编排 state 键的覆盖合并策略。关键：graph-core resume 时以 OverAllState#input()
        // 合并 checkpoint 状态，只保留「已注册 KeyStrategy」的键；不注册则断点续跑时
        // subtask/result/final 等全部丢失（全新执行走 withData 无此过滤，故首跑不受影响）
        StateGraph g = new StateGraph(MultiAgentGraphAgent::stateKeyStrategies);
        // 子任务工具事件发射器：并行节点可能同时回调，经 Sink 锁串行化
        java.util.function.Consumer<String> toolEmitter = toolEvents == null ? null
                : row -> BranchProgressListener.tryEmitSerialized(toolEvents, row);

        // lead：拆解复杂目标为多条子任务
        g.addNode(NODE_LEAD, AsyncNodeAction.node_async(state -> lead(state, cancelled)));
        // 子任务池：固定 MAX_SUBTASKS 个并行节点
        for (int i = 0; i < MAX_SUBTASKS; i++) {
            final int idx = i;
            g.addNode(subtaskName(idx),
                    AsyncNodeAction.node_async(state -> subtask(state, idx, toolEmitter, cancelled)));
        }
        // 聚合：收集各子任务结果生成最终回答（流式模式逐 token 旁路推送）
        g.addNode(NODE_AGGREGATE,
                AsyncNodeAction.node_async(state -> aggregate(state, liveTokens, contentSent, cancelled)));

        // 并联：lead → 同时派发到所有子任务节点（addEdge(from, List) 并行扇出）
        List<String> subtasks = new ArrayList<>();
        for (int i = 0; i < MAX_SUBTASKS; i++) {
            subtasks.add(subtaskName(i));
        }
        g.addEdge(NODE_LEAD, subtasks);
        // 各子任务 → 聚合
        for (int i = 0; i < MAX_SUBTASKS; i++) {
            g.addEdge(subtaskName(i), NODE_AGGREGATE);
        }
        // 聚合 → 结束
        g.addEdge(NODE_AGGREGATE, StateGraph.END);
        // 入口：START → lead
        g.addEdge(StateGraph.START, NODE_LEAD);

        return g;
    }

    /**
     * 编排 state 全部键的注册策略（覆盖语义，与节点直接 put 的现有行为一致）：
     * 输入键 + 拆解产物 + 各子任务槽位 + 最终回答。
     */
    private static Map<String, KeyStrategy> stateKeyStrategies() {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        KeyStrategy replace = new ReplaceStrategy();
        strategies.put(K_OBJECTIVE, replace);
        strategies.put(K_SESSION_ID, replace);
        strategies.put(K_SUBTASK_COUNT, replace);
        strategies.put(K_FINAL, replace);
        // 编排预算账本（可空：budget=0 时不注入；Replace 策略保证续跑时新账本覆盖旧值）
        strategies.put(OrchestrationBudget.K_TOKEN_LEDGER, replace);
        strategies.put(OrchestrationBudget.K_TOKEN_ESTIMATED, replace);
        for (int i = 0; i < MAX_SUBTASKS; i++) {
            strategies.put(K_SUBTASK_PREFIX + i, replace);
            strategies.put(K_SUBTASK_AGENT_PREFIX + i, replace);
            strategies.put(K_RESULT_PREFIX + i, replace);
        }
        return strategies;
    }

    /* ------------ 节点实现（同步 NodeAction，返回状态更新 Map） ------------ */

    /** 客户端已断开则跳过本节点的 LLM 调用（同步路径 cancelled 为 null，恒不短路） */
    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    /**
     * lead：把 objective 拆解为 N 条子任务（可带专家指派），
     * 写 subtask_0..n-1、subtaskAgent_0..n-1 与 subtaskCount。
     * 消耗经门控账本句柄按 roundtrip 增量记账（lead 是编排首调用，发起前账本为 0 不会熔断；
     * 极小预算下 usage 帧中途熔断时降级为「拆解失败」——退化为单子任务，交由后续熔断跳过，
     * 聚合注入降级说明）。lead 自身消耗计入账本供后续判定。
     */
    private Map<String, Object> lead(OverAllState state, AtomicBoolean cancelled) {
        if (isCancelled(cancelled)) {
            log.info("[multi-agent][lead] 客户端已断开，跳过拆解");
            return new HashMap<>();
        }
        String objective = state.value(K_OBJECTIVE, String.class).orElse("");
        String sessionId = state.value(K_SESSION_ID, String.class).orElse(null);
        String content;
        try {
            content = predictLeadLogged(sessionId, objective, cancelled,
                    orchestrationBudget.ledgerHandle(state, true));
        } catch (AgentChatCaller.BudgetExceededException e) {
            log.warn("[multi-agent][lead] 编排预算超限中止拆解（已消耗 {} / 上限 {}），退化为单子任务",
                    OrchestrationBudget.ledgerValue(state), budgets.getOrchestrationBudget());
            content = null; // 拆解产物缺失 → 退化为单个子任务=objective，执行前被熔断跳过
        }
        List<LeadOutputParser.Subtask> items = LeadOutputParser.parseSubtasks(content);
        if (items.isEmpty()) {
            // 拆解失败：退化为单个子任务=objective
            items.add(new LeadOutputParser.Subtask(objective, null));
        }
        Map<String, Object> updates = new HashMap<>();
        int n = Math.min(items.size(), MAX_SUBTASKS);
        updates.put(K_SUBTASK_COUNT, n);
        for (int i = 0; i < n; i++) {
            LeadOutputParser.Subtask item = items.get(i);
            updates.put(K_SUBTASK_PREFIX + i, item.desc());
            updates.put(K_SUBTASK_AGENT_PREFIX + i, item.agent());
        }
        log.info("[multi-agent][lead] 拆解为 {} 个子任务，指派：{}", n,
                items.subList(0, n).stream().map(LeadOutputParser.Subtask::agent).toList());
        return updates;
    }

    /**
     * 子任务节点：读 subtask_i 与指派的 subtaskAgent_i，若存在则调用对应专家 ChatClient 生成 result_i。
     * 熔断下沉到 caller（方向 b）：门控账本句柄在调用发起前（零 HTTP）与每轮 roundtrip 的
     * usage 帧上检查，超限抛 {@link AgentChatCaller.BudgetExceededException}——节点捕获后
     * result 写「预算超限跳过」占位，聚合据此注入降级说明。并行扇出下共享账本让后序调用
     * 及时看到前序消耗，配合 subtask-concurrency 错峰使「部分跳过」成为常态而非偶发。
     */
    private Map<String, Object> subtask(OverAllState state, int idx,
                                        java.util.function.Consumer<String> toolEmitter,
                                        AtomicBoolean cancelled) {
        String task = state.value(K_SUBTASK_PREFIX + idx, String.class).orElse(null);
        if (task == null || task.isBlank()) {
            return new HashMap<>(); // lead 未设置该子任务 → 快速短路
        }
        if (isCancelled(cancelled)) {
            log.info("[multi-agent][subtask-{}] 客户端已断开，跳过专家调用", idx);
            return new HashMap<>();
        }
        String expert = state.value(K_SUBTASK_AGENT_PREFIX + idx, String.class).orElse(null);
        String sessionId = state.value(K_SESSION_ID, String.class).orElse(null);
        String result;
        try {
            result = predictSubtask(sessionId, task, expert, toolEmitter, cancelled,
                    orchestrationBudget.ledgerHandle(state, true));
        } catch (AgentChatCaller.BudgetExceededException e) {
            log.warn("[multi-agent][subtask-{}] 编排 token 消费已达上限（{} / {}），跳过专家调用",
                    idx, OrchestrationBudget.ledgerValue(state), budgets.getOrchestrationBudget());
            Map<String, Object> updates = new HashMap<>();
            updates.put(K_RESULT_PREFIX + idx, SKIPPED_RESULT);
            return updates;
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(K_RESULT_PREFIX + idx, result);
        log.info("[multi-agent][subtask-{}] 完成（专家={}），结果长度={}", idx, expert, result.length());
        return updates;
    }

    /**
     * 聚合节点实现：非流式（liveTokens=null）阻塞调用；流式时逐 token 旁路发射，
     * 首个内容 token 前先发「聚合」进度行，失败回退阻塞调用（未推过 token 时主干兜底发完整内容）。
     * cancelled 非 null 且已置位时短路：不再调 LLM，占位收尾。
     *
     * <p>预算降级（熔断后聚合必发）：被熔断子任务的占位 result 不作为真实结果喂给模型，
     * 改为在 prompt 前置降级说明（N 个子任务因预算超限未执行 + 已消耗/上限数字 + 估算口径），
     * 聚合自身不受预算熔断（照常执行，消耗照常上报账本）。
     */
    private Map<String, Object> aggregate(OverAllState state,
                                          Sinks.Many<String> liveTokens,
                                          AtomicBoolean contentSent,
                                          AtomicBoolean cancelled) {
        if (isCancelled(cancelled)) {
            // 短路不写占位 final：避免「假完成」状态落检查点，导致续跑无法补跑聚合
            log.info("[multi-agent][aggregate] 客户端已断开，跳过聚合调用");
            return new HashMap<>();
        }
        int n = state.value(K_SUBTASK_COUNT, Integer.class).orElse(0);
        String sessionId = state.value(K_SESSION_ID, String.class).orElse(null);
        List<String> results = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < n; i++) {
            String r = state.value(K_RESULT_PREFIX + i, String.class).orElse(null);
            if (r == null || r.isBlank()) {
                continue; // 子任务失败（异常上抛）无结果
            }
            if (SKIPPED_RESULT.equals(r)) {
                skipped++; // 预算熔断跳过：不计入真实结果，降级说明交代
                continue;
            }
            results.add(r);
        }
        String finalAnswer;
        if (results.isEmpty() && skipped == 0) {
            // 子任务全失败：兜底（原有行为）
            finalAnswer = state.value(K_FINAL, String.class).orElse("（未生成最终回答）");
        } else {
            String user = aggregateUserPrompt(results);
            if (skipped > 0) {
                user = orchestrationBudget.degradationNote(skipped, state) + "\n\n" + user;
            }
            if (liveTokens == null) {
                // 同步路径 cancelled 为 null（无取消语义），流式路径传共享断连标志
                finalAnswer = predictAggregate(sessionId, user, cancelled,
                        orchestrationBudget.ledgerHandle(state, false));
            } else {
                finalAnswer = predictAggregateStreaming(sessionId, user, liveTokens, contentSent,
                        cancelled, orchestrationBudget.ledgerHandle(state, false));
            }
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(K_FINAL, finalAnswer);
        log.info("[multi-agent][aggregate] 汇总 {} 个子任务结果（预算超限跳过 {} 个）",
                results.size(), skipped);
        return updates;
    }

    /**
     * 流式聚合：首个内容 token 前推「聚合」进度行，随后逐 token 实时发射（聚合只有流式一条语义路径）。
     * 失败自愈（带护栏的流式重试，不再回退阻塞调用）：
     * - 流式异常且未推出任何 token（挂死超时等首 token 前失败）→ 流式重试一次；
     * - 流式成功但 0 个内容 token（思考模型输出全在 reasoning_content 等）→ 流式重试一次；
     * - 已推出 token 后失败 → 不重试（重试会造成内容重复），以已收内容为准；
     * - 重试后仍失败 / 仍 0 token → 上抛（编排按失败收尾，不再以阻塞调用兜底）。
     * 例外：客户端断连中止（取消异常）不重试、部分输出不按成功返回，取消异常向上传播。
     */
    private String predictAggregateStreaming(String sessionId,
                                             String user,
                                             Sinks.Many<String> liveTokens,
                                             AtomicBoolean contentSent,
                                             AtomicBoolean cancelled,
                                             AgentChatCaller.BudgetLedger budgetLedger) {
        BranchProgressListener.tryEmitSerialized(liveTokens,
                ProgressLine.encode("聚合", "汇总子任务结果，生成最终回答"));
        StringBuilder collected = new StringBuilder();
        try {
            streamAggregateOnce(sessionId, user, collected, liveTokens, contentSent, cancelled, budgetLedger);
            if (collected.length() > 0) {
                return collected.toString();
            }
            log.warn("[multi-agent][aggregate] 流式聚合 0 个内容 token，流式重试一次");
        } catch (Exception e) {
            // 客户端断连中止：取消不是流式失败，禁止重试/以部分输出充数——原样上抛
            if (e instanceof CancellationException ce) {
                throw ce;
            }
            if (isCancelled(cancelled)) {
                throw AgentChatCaller.cancelException();
            }
            if (collected.length() > 0) {
                // 已推 token 后失败：重试会内容重复，以已收内容为准
                log.warn("[multi-agent][aggregate] 流式聚合失败（已推出部分 token，不重试）：{}", safe(e));
                return collected.toString();
            }
            log.warn("[multi-agent][aggregate] 流式聚合失败，流式重试一次：{}", safe(e));
        }
        // 护栏重试：到达此处必然未推出任何 token（重试零内容重复风险）
        try {
            streamAggregateOnce(sessionId, user, collected, liveTokens, contentSent, cancelled, budgetLedger);
        } catch (Exception e2) {
            if (e2 instanceof CancellationException ce) {
                throw ce;
            }
            if (isCancelled(cancelled)) {
                throw AgentChatCaller.cancelException();
            }
            log.warn("[multi-agent][aggregate] 聚合流式重试仍失败：{}", safe(e2));
            throw e2;
        }
        if (collected.length() == 0) {
            throw new IllegalStateException("聚合流式重试后仍无内容输出");
        }
        return collected.toString();
    }

    /** 聚合单次流式尝试：token 追加进 collected 并经旁路发射（成败处置由调用方负责） */
    private void streamAggregateOnce(String sessionId, String user, StringBuilder collected,
                                     Sinks.Many<String> liveTokens, AtomicBoolean contentSent,
                                     AtomicBoolean cancelled,
                                     AgentChatCaller.BudgetLedger budgetLedger) {
        chatCaller.stream(sessionId, ROLE_AGGREGATOR, AGGREGATOR_FALLBACK_PROMPT,
                user,
                token -> {
                    if (token == null || token.isEmpty()) {
                        return;
                    }
                    collected.append(token);
                    contentSent.set(true);
                    BranchProgressListener.tryEmitSerialized(liveTokens, token);
                },
                null,
                new PromptBudgetAdvisor[]{aggregateBudgetAdvisor()},
                cancelled == null ? null : cancelled::get,
                budgetLedger);
    }

    /* ---------- ChatClient 单次调用 ---------- */

    /** lead 拆解兜底提示词（agent 表无 lead 行时使用；正常情况以表配置为准） */
    private static final String LEAD_FALLBACK_PROMPT =
            "你是多 Agent 的 Lead 拆解器。把用户复杂目标拆解为若干条可并行执行的子任务，"
                    + "并为每条子任务指派最合适的专家执行。可选专家（只能用这些名字）："
                    + "researcher（资料调研）、coder（代码编写/修复）、analyst（数据分析）、writer（汇总撰写）、general（通用兜底）。"
                    + "拆解数量必须与任务难度匹配，禁止凑数：至多 4 条；简单任务只拆 1 条，中等任务 2~3 条，"
                    + "只有确实存在多个可独立并行、且各自对最终结果都有贡献的部分时才拆满；"
                    + "任何一条子任务如果只是原任务换个说法，就不要拆。"
                    + "只输出一行 JSON，格式："
                    + "{\"subtasks\":[{\"desc\":\"子任务描述\",\"agent\":\"专家名\"}]}，不要任何解释。";

    /** 聚合兜底提示词（agent 表无 aggregator 行时使用；正常情况以表配置为准） */
    private static final String AGGREGATOR_FALLBACK_PROMPT =
            "你是聚合汇总的 AI 助手，依据多个子结果的最终回答可直接呈现给用户。";

    /** 聚合 user 内容的子任务节头（与 {@link #aggregateUserPrompt} 的拼接格式对应） */
    private static final Pattern AGG_SECTION_HEADER = Pattern.compile("【子任务\\d+】");

    /**
     * lead 拆解：按 agent 表 lead 行的提示词/模型执行（无配置时回退内置兜底）；目标超长尾截至 lead 预算。
     * cancelled 传节点共享断连标志（同步路径为 null）：调用前已置位直接抛取消异常（零 HTTP 请求），
     * 执行中置位在下一个 token 边界中止在途请求。budgetLedger 门控账本句柄（caller 发起前与
     * 每轮 roundtrip 熔断判定 + 增量记账；可 null）。
     */
    private String predictLead(String sessionId, String objective, AtomicBoolean cancelled,
                               AgentChatCaller.BudgetLedger budgetLedger) {
        return chatCaller.call(sessionId, ROLE_LEAD, LEAD_FALLBACK_PROMPT, "拆解目标：" + objective,
                null, new PromptBudgetAdvisor[]{PromptBudgetAdvisor.tail(budgets.getLeadBudget())},
                cancelled == null ? null : cancelled::get, budgetLedger);
    }

    /** lead 拆解前日志埋点便于诊断专家指派（raw 输出统一记审计） */
    private String predictLeadLogged(String sessionId, String objective, AtomicBoolean cancelled,
                                     AgentChatCaller.BudgetLedger budgetLedger) {
        String raw = predictLead(sessionId, objective, cancelled, budgetLedger);
        log.info("[multi-agent][lead] raw 拆解输出: {}", raw.length() > 300 ? raw.substring(0, 300) + "..." : raw);
        return raw;
    }

    /**
     * 子任务执行：按指派专家查配置调用（cancelled 传节点共享断连标志，同步路径为 null——
     * 执行中置位时在途调用随令牌中止，不再烧完剩余 token）。budgetLedger 门控账本句柄：
     * caller 发起前与每轮 roundtrip 熔断判定 + 按 roundtrip 增量记账（可 null）。
     */
    private String predictSubtask(String sessionId, String task, String expert,
                                  java.util.function.Consumer<String> toolEmitter,
                                  AtomicBoolean cancelled,
                                  AgentChatCaller.BudgetLedger budgetLedger) {
        // 未指派（lead 输出旧格式或漏 agent 字段）→ 回退 general：通用兜底且持有全量工具
        String resolved = (expert == null || expert.isBlank())
                ? AgentConstants.DEFAULT_AGENT : expert;
        // 专家 persona 与工具使用纪律经 PromptAssembler 统一组装（原硬编码拼接已删除）：
        // persona 作角色段兜底传入，工具索引/工具纪律/输出约定等段由调用器组装时追加
        String persona = promptAssembler.subtaskPersona(resolved);
        return chatCaller.call(sessionId, resolved, persona, task, toolEmitter,
                new PromptBudgetAdvisor[0], cancelled == null ? null : cancelled::get, budgetLedger);
    }

    /**
     * 聚合阻塞语义调用，仅服务同步编排路径（execute，liveTokens=null）；
     * 流式路径的失败自愈已改为带护栏的流式重试（见 {@link #predictAggregateStreaming}），不再经此兜底。
     * budgetLedger 为 record-only 句柄（聚合不受熔断，仅记账；可 null）。
     */
    private String predictAggregate(String sessionId, String user, AtomicBoolean cancelled,
                                    AgentChatCaller.BudgetLedger budgetLedger) {
        return chatCaller.call(sessionId, ROLE_AGGREGATOR, AGGREGATOR_FALLBACK_PROMPT, user,
                null, new PromptBudgetAdvisor[]{aggregateBudgetAdvisor()},
                cancelled == null ? null : cancelled::get, budgetLedger);
    }

    /** 聚合预算 advisor：按「【子任务N】」节边界等份额截断（禁止先到先得挤掉后面的子任务） */
    private PromptBudgetAdvisor aggregateBudgetAdvisor() {
        return PromptBudgetAdvisor.sections(budgets.getAggregateBudget(), AGG_SECTION_HEADER);
    }

    /** 聚合请求的 user 内容：各子任务结果顺序拼接（阻塞/流式两版共用） */
    private static String aggregateUserPrompt(List<String> results) {
        StringBuilder sb = new StringBuilder("以下是各子任务结果，请汇总为一份完整、连贯的最终回答：\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append("【子任务").append(i + 1).append("】\n").append(results.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    private static String safe(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
