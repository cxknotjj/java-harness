package com.dark.javaHarness.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.dark.javaHarness.advisor.PromptBudgetAdvisor;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.enums.AgentConstants;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.impl.LlmCallRecorder;
import com.dark.javaHarness.tool.ToolAssignments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   （JSON 解析），并为每条子任务指派专家（researcher/coder/analyst/writer/general，白名单校验，非法回退）
 * - subtask-i 节点：并行执行，按指派的专家名查 agent 表配置取对应 ChatClient 产出该子任务结果
 * - aggregate 节点：查 {@code aggregator} 行（无则内置兜底），收集各子任务结果汇总成最终回答
 *
 * <p>与路径 A 的 {@link GeneralAssistantAgent} 对 Key 契约一致：
 * {@link #execute(Goal)} 返回最终回答 String；Goal 生命周期与会话记忆写回
 * 统一由 AgentService / ChatService 负责。
 *
 * <p>图拓扑只构建一次；同步执行 {@link #execute(Goal)} 走 invoke；
 * 流式执行 {@link #executeStreamReactive(Goal)} 采用「stream 主干帧 + 生命周期钩子旁路」双通道
 * （详见该方法说明与死锁教训）。
 */
public class MultiAgentGraphAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentGraphAgent.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 单个总任务拆解的子任务数上限，避免滚雪球 */
    private static final int MAX_SUBTASKS = 4;

    /** 节点名常量 */
    private static final String NODE_LEAD = "lead";
    private static final String NODE_AGGREGATE = "aggregate";

    /** 编排环节角色名（agent 表行名）：lead 拆解器、aggregator 聚合器，与编排器 multi-agent 行解耦 */
    private static final String ROLE_LEAD = "lead";
    private static final String ROLE_AGGREGATOR = "aggregator";

    /** 状态键 */
    private static final String K_OBJECTIVE = "objective";
    private static final String K_SESSION_ID = "sessionId";
    private static final String K_SUBTASK_COUNT = "subtaskCount";
    private static final String K_SUBTASK_PREFIX = "subtask_";
    private static final String K_SUBTASK_AGENT_PREFIX = "subtaskAgent_";
    private static final String K_RESULT_PREFIX = "result_";
    private static final String K_FINAL = "final";

    /** 子任务节点名前缀 */
    private static final String SUBTASK_NODE_PREFIX = "subtask-";

    /** lead 拆解可指派的专家白名单：不在名单中的 agent 名一律回退默认（general 语义） */
    private static final Set<String> EXPERT_WHITELIST = Set.of(
            AgentConstants.EXPERT_RESEARCHER,
            AgentConstants.EXPERT_CODER,
            AgentConstants.EXPERT_ANALYST,
            AgentConstants.EXPERT_WRITER,
            AgentConstants.DEFAULT_AGENT);

    /** lead 拆解产物：子任务描述 + 指派专家（agent 可为 null = 未指派，执行时回退默认） */
    record Subtask(String desc, String agent) {
    }

    private final String agentName;
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
        this.agentName = agentName;
        this.chatCaller = new AgentChatCaller(clientRegistry, agentService, toolAssignments, recorder);
        this.checkpointSaver = checkpointSaver;
        this.budgets = budgets != null ? budgets : new ContextBudgetProperties();
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

    /** 执行用 RunnableConfig：threadId=goalId（检查点归属键） */
    private static RunnableConfig runnableConfig(String goalId) {
        return RunnableConfig.builder().threadId(goalId).build();
    }

    @Override
    public String name() {
        return agentName;
    }

    /** 执行复杂目标：把客观目标注入 StateGraph，返回最终回答。 */
    @Override
    public String execute(Goal goal) {
        log.info("[multi-agent] 开始编排复杂目标: {}", goal.objective());
        Map<String, Object> input = new HashMap<>();
        input.put(K_OBJECTIVE, goal.objective());
        input.put(K_SESSION_ID, goal.sessionId());

        return graph.invoke(input, runnableConfig(goal.id()))
                .flatMap(s -> s.value(K_FINAL, String.class))
                .orElse(goal.objective());
    }

    /**
     * 流式执行复杂目标：stream 主干帧 + 双旁路（生命周期钩子 + 聚合 token）。
     *
     * <p>主干：{@link CompiledGraph#stream(Map)} 帧 → {@link #toRows} 行；
     * 旁路一：{@link BranchProgressListener} 补齐 stream 合并掉的并行分支「子任务完成」事件；
     * 旁路二：聚合节点内逐 token 实时推送最终回答（含首个 token 前的「聚合」进度行）——
     * 图节点是同步动作，token 在节点内经回调旁路发射，节点返回时 state 已含完整内容，
     * 主干对已推送内容的帧不再重复发射（见 {@link #toRows} 的 contentSent 短路）。
     *
     * <p>子任务节点保持阻塞调用：多子任务并行执行，token 直推会交错乱序；用户体感关键
     * 在最终回答的打字机效果，由聚合节点承担。lead 产出为 JSON 中间产物，不推送 token。
     *
     * <p>⚠️ 死锁教训：关闸 {@code doFinally} 必须挂在 mergeWith **之前**的主干段上——
     * merge 要求两源都终结才向下传 complete，关闸挂 merge 之后会循环等待、永不收尾。
     */
    @Override
    public Flux<String> executeStreamReactive(Goal goal) {
        return reactivePipeline(goal, runnableConfig(goal.id()));
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
     * <p>恢复点选择（{@link #selectResumeCheckpoint}）：
     * 编排已完成（final 有效）→ 从最终检查点零调用回放；
     * 否则回退到「子任务批完成、聚合前」的检查点（nextNodeId=aggregate）补跑聚合。
     */
    public Flux<String> resumeStreamReactive(Goal goal) {
        if (checkpointSaver == null) {
            throw new IllegalStateException("未启用检查点存储，无法续跑");
        }
        RunnableConfig probe = runnableConfig(goal.id());
        com.alibaba.cloud.ai.graph.checkpoint.Checkpoint target;
        try {
            java.util.Collection<com.alibaba.cloud.ai.graph.checkpoint.Checkpoint> checkpoints =
                    checkpointSaver.list(probe);
            if (checkpoints.isEmpty()) {
                throw new IllegalStateException(
                        "无可续跑的编排：goal " + goal.id() + " 没有检查点（可能未走过复杂路径）");
            }
            target = selectResumeCheckpoint(checkpoints);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取检查点失败: " + goal.id(), e);
        }
        log.info("[multi-agent] 断点续跑 goal {}: 从检查点 {} 继续（nextNodeId={}，已完成节点不再重跑）",
                goal.id(), target.getId(), target.getNextNodeId());
        RunnableConfig config = RunnableConfig.builder()
                .threadId(goal.id())
                .checkPointId(target.getId())
                .build();
        return reactivePipeline(goal, config);
    }

    /**
     * 恢复点选择：
     * - 编排已完整跑完（存在 nextNodeId=END 且 final 有效的检查点）→ 选它，续跑零 LLM 调用、直接回放最终回答；
     * - 聚合未完成（客户端断开时聚合被短路，最新检查点无有效 final）→ 回退到聚合前的检查点
     *   （nextNodeId=aggregate，state 已含子任务结果），续跑只补跑聚合；
     * - 都没有（断开极早，如 lead 中断）→ 兜底取任一检查点（通常 lead 之后，重跑缺口最小）。
     */
    private static com.alibaba.cloud.ai.graph.checkpoint.Checkpoint selectResumeCheckpoint(
            java.util.Collection<com.alibaba.cloud.ai.graph.checkpoint.Checkpoint> checkpoints) {
        com.alibaba.cloud.ai.graph.checkpoint.Checkpoint any = checkpoints.iterator().next();
        return checkpoints.stream()
                .filter(cp -> StateGraph.END.equals(cp.getNextNodeId()))
                .filter(cp -> {
                    Object fin = cp.getState() == null ? null : cp.getState().get(K_FINAL);
                    return fin instanceof String s && !s.isBlank();
                })
                .findFirst()
                .orElseGet(() -> checkpoints.stream()
                        .filter(cp -> NODE_AGGREGATE.equals(cp.getNextNodeId()))
                        .findFirst()
                        .orElse(any));
    }

    /**
     * 流式管道共用体：构建带旁路的图 → 编译（挂监听器 + 检查点）→ 主干帧合并旁路流。
     * execute（全新执行）与 resume（断点续跑）仅 RunnableConfig 不同。
     */
    private Flux<String> reactivePipeline(Goal goal, RunnableConfig config) {
        Map<String, Object> input = new HashMap<>();
        input.put(K_OBJECTIVE, goal.objective());
        input.put(K_SESSION_ID, goal.sessionId());
        AtomicBoolean contentSent = new AtomicBoolean(false);
        // 客户端断开（Reactor cancel）置位：后续 superstep 的节点短路，不再发起新的 LLM 调用
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Sinks.Many<String> branchEvents = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<String> liveTokens = Sinks.many().unicast().onBackpressureBuffer();
        // 旁路三：子任务节点内的工具调用起止（专家执行工具时 CLI 展示工具调用行）
        Sinks.Many<String> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
        CompiledGraph streamingGraph;
        try {
            // 流式拓扑每次执行独立构建（聚合节点绑定本次执行的 token 旁路，保证并发安全）
            streamingGraph = buildStateGraph(liveTokens, contentSent, toolEvents, cancelled)
                    .compile(compileConfig(new BranchProgressListener(
                            branchEvents, SUBTASK_NODE_PREFIX, K_SUBTASK_PREFIX)));
        } catch (GraphStateException e) {
            throw new IllegalStateException("编译带监听器的 StateGraph 失败", e);
        }

        // 关闸在 merge 之前（见死锁说明）；complete 与 next 共用同一把锁，防迟到事件竞争。
        // 注意：图节点异常终止时 graph-core 也会以 CANCEL 清理主干订阅，因此主干段的
        // doFinally 不能用于判定「客户端断开」（会把编排异常误报为断开）；
        // 真实断开判定挂在外层合并流上——只有下游（CLI/HTTP）真正断开才会 cancel 到这里。
        Flux<String> mainLine = streamingGraph.stream(input, config)
                .concatMap(out -> toRows(out, goal.objective(), contentSent))
                .doFinally(sig -> {
                    // 主干终结（完成/异常/被取消）后关闸旁路 sink，防止 merge 永久挂起
                    BranchProgressListener.tryCompleteSerialized(branchEvents);
                    BranchProgressListener.tryCompleteSerialized(liveTokens);
                    BranchProgressListener.tryCompleteSerialized(toolEvents);
                });

        return mainLine
                .mergeWith(branchEvents.asFlux())
                .mergeWith(liveTokens.asFlux())
                .mergeWith(toolEvents.asFlux())
                .doOnCancel(() -> {
                    // 客户端断开（Reactor cancel）置位：后续 superstep 的节点短路，不再发起新的 LLM 调用
                    cancelled.set(true);
                    log.warn("[multi-agent] 客户端已断开，终止编排：不再发起新的 LLM 调用（进行中的调用等待自然结束）");
                })
                .onErrorResume(e -> {
                    log.warn("[multi-agent] 流式执行异常：{}", safe(e));
                    return Flux.just(ProgressLine.encode("编排", "异常，已回退：" + safe(e)));
                });
    }

    /**
     * 把一个主干节点输出帧映射为 0..n 条输出行（保序）：
     * START→编排开始；lead→拆解结果；aggregate→聚合进度+最终内容；END→内容兜底。
     * 并行 subtask 分支事件由生命周期钩子旁路提供，不走此处。
     */
    private Flux<String> toRows(NodeOutput out, String objective,
                                java.util.concurrent.atomic.AtomicBoolean contentSent) {
        if (out == null) {
            return Flux.empty();
        }
        String node = out.node();
        OverAllState state = out.state();
        if (out.isSTART()) {
            return Flux.just(ProgressLine.encode("编排", "开始拆解复杂目标…"));
        }
        // END 帧：确保一定有内容行。优先取 state 中的最终回答（断点续跑越过聚合节点时，
        // final 已在检查点状态里，兜底 objective 会答非所问）；都缺失时才回退 objective
        if (out.isEND()) {
            if (contentSent.get()) {
                return Flux.empty();
            }
            String fin = state == null ? null
                    : state.value(K_FINAL, String.class).orElse(null);
            return Flux.just(fin == null || fin.isBlank() ? objective : fin);
        }
        if (NODE_LEAD.equals(node)) {
            int n = state.value(K_SUBTASK_COUNT, Integer.class).orElse(0);
            return Flux.just(ProgressLine.encode("拆解", n + " 个子任务已就绪"));
        }
        if (NODE_AGGREGATE.equals(node)) {
            // 流式模式：token 已由聚合节点内旁路实时推送，此处不再重复发射
            if (contentSent.get()) {
                return Flux.empty();
            }
            String fin = state.value(K_FINAL, String.class).orElse(null);
            if (fin != null && !fin.isBlank()) {
                contentSent.set(true);
                // 先发聚合进度，再发最终回答内容行（不加进度前缀）
                return Flux.just(
                        ProgressLine.encode("聚合", "汇总子任务结果，生成最终回答"),
                        fin);
            }
            return Flux.just(ProgressLine.encode("聚合", "正在生成最终回答…"));
        }
        return Flux.empty(); // subtask 等其它 superstep 合并帧：由钩子旁路负责
    }

    /* ---------------- 工具方法 ---------------- */

    /** 子任务节点名 */
    private String subtaskName(int i) {
        return SUBTASK_NODE_PREFIX + i;
    }

    /* ---------------- StateGraph 构建 ---------------- */

    private StateGraph buildStateGraph() throws GraphStateException {
        return buildStateGraph(null, null, null, null);
    }

    /**
     * 构建「lead → 并行子任务 → 聚合」拓扑。
     *
     * @param liveTokens  非 null 时聚合节点走流式调用并把 token 旁路发射到该 sink（含首个 token 前的「聚合」进度行）；
     *                    null 时聚合节点阻塞调用（同步 execute 路径）
     * @param contentSent 流式模式的内容已发射标志（与主干 {@link #toRows} 共享，防重复发射）；可为 null
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
    private static Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> stateKeyStrategies() {
        Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> strategies = new HashMap<>();
        com.alibaba.cloud.ai.graph.KeyStrategy replace =
                new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy();
        strategies.put(K_OBJECTIVE, replace);
        strategies.put(K_SESSION_ID, replace);
        strategies.put(K_SUBTASK_COUNT, replace);
        strategies.put(K_FINAL, replace);
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
     */
    private Map<String, Object> lead(OverAllState state, AtomicBoolean cancelled) {
        if (isCancelled(cancelled)) {
            log.info("[multi-agent][lead] 客户端已断开，跳过拆解");
            return new HashMap<>();
        }
        String objective = state.value(K_OBJECTIVE, String.class).orElse("");
        String sessionId = state.value(K_SESSION_ID, String.class).orElse(null);
        String content = predictLeadLogged(sessionId, objective);
        List<Subtask> items = parseSubtasks(content);
        if (items.isEmpty()) {
            items.add(new Subtask(objective, null)); // 拆解失败：退化为单个子任务=objective
        }
        Map<String, Object> updates = new HashMap<>();
        int n = Math.min(items.size(), MAX_SUBTASKS);
        updates.put(K_SUBTASK_COUNT, n);
        for (int i = 0; i < n; i++) {
            Subtask item = items.get(i);
            updates.put(K_SUBTASK_PREFIX + i, item.desc());
            updates.put(K_SUBTASK_AGENT_PREFIX + i, item.agent());
        }
        log.info("[multi-agent][lead] 拆解为 {} 个子任务，指派：{}", n,
                items.subList(0, n).stream().map(Subtask::agent).toList());
        return updates;
    }

    /** 子任务节点：读 subtask_i 与指派的 subtaskAgent_i，若存在则调用对应专家 ChatClient 生成 result_i。 */
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
        String result = predictSubtask(sessionId, task, expert, toolEmitter);
        Map<String, Object> updates = new HashMap<>();
        updates.put(K_RESULT_PREFIX + idx, result);
        log.info("[multi-agent][subtask-{}] 完成（专家={}），结果长度={}", idx, expert, result.length());
        return updates;
    }

    /** 聚合：读 result_0..result_{n-1}（按 subtaskCount），调用 ChatClient 汇总为 final。 */
    private Map<String, Object> aggregate(OverAllState state) {
        return aggregate(state, null, null, null);
    }

    /**
     * 聚合节点实现：非流式（liveTokens=null）阻塞调用；流式时逐 token 旁路发射，
     * 首个内容 token 前先发「聚合」进度行，失败回退阻塞调用（未推过 token 时主干兜底发完整内容）。
     * cancelled 非 null 且已置位时短路：不再调 LLM，占位收尾。
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
        for (int i = 0; i < n; i++) {
            state.value(K_RESULT_PREFIX + i, String.class)
                    .filter(s -> !s.isBlank())
                    .ifPresent(results::add);
        }
        String finalAnswer;
        if (results.isEmpty()) {
            // 子任务全失败：兜底
            finalAnswer = state.value(K_FINAL, String.class).orElse("（未生成最终回答）");
        } else if (liveTokens == null) {
            finalAnswer = predictAggregate(sessionId, results);
        } else {
            finalAnswer = predictAggregateStreaming(sessionId, results, liveTokens, contentSent);
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(K_FINAL, finalAnswer);
        log.info("[multi-agent][aggregate] 汇总 {} 个子任务结果", results.size());
        return updates;
    }

    /**
     * 流式聚合：首个内容 token 前推「聚合」进度行，随后逐 token 实时发射；
     * 流式异常或 0 个内容 token 时回退阻塞调用——已推出过 token 则以已收内容为准（避免内容重复），
     * 一个 token 都没推过则整段回退（主干 toRows 会兜底发聚合进度+完整内容）。
     */
    private String predictAggregateStreaming(String sessionId,
                                             List<String> results,
                                             Sinks.Many<String> liveTokens,
                                             AtomicBoolean contentSent) {
        BranchProgressListener.tryEmitSerialized(liveTokens,
                ProgressLine.encode("聚合", "汇总子任务结果，生成最终回答"));
        StringBuilder collected = new StringBuilder();
        try {
            chatCaller.stream(sessionId, ROLE_AGGREGATOR, AGGREGATOR_FALLBACK_PROMPT,
                    aggregateUserPrompt(results),
                    token -> {
                        if (token == null || token.isEmpty()) {
                            return;
                        }
                        collected.append(token);
                        contentSent.set(true);
                        BranchProgressListener.tryEmitSerialized(liveTokens, token);
                    },
                    null,
                    aggregateBudgetAdvisor());
            // 流式成功但 0 个内容 token（思考模型流式输出全在 reasoning_content 等）：
            // 不能把空串当最终回答落 final（CLI 会回显目标本身），回退阻塞调用兜底
            return collected.length() > 0 ? collected.toString() : predictAggregate(sessionId, results);
        } catch (Exception e) {
            log.warn("[multi-agent][aggregate] 流式聚合失败，回退阻塞调用：{}", safe(e));
            return collected.length() > 0 ? collected.toString() : predictAggregate(sessionId, results);
        }
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

    /** lead 拆解：按 agent 表 lead 行的提示词/模型执行（无配置时回退内置兜底）；目标超长尾截至 lead 预算 */
    private String predictLead(String sessionId, String objective) {
        return chatCaller.call(sessionId, ROLE_LEAD, LEAD_FALLBACK_PROMPT, "拆解目标：" + objective,
                null, PromptBudgetAdvisor.tail(budgets.getLeadBudget()));
    }

    /** lead 拆解前日志埋点便于诊断专家指派（raw 输出统一记审计） */
    private String predictLeadLogged(String sessionId, String objective) {
        String raw = predictLead(sessionId, objective);
        log.info("[multi-agent][lead] raw 拆解输出: {}", raw.length() > 300 ? raw.substring(0, 300) + "..." : raw);
        return raw;
    }

    private String predictSubtask(String sessionId, String task, String expert,
                                  java.util.function.Consumer<String> toolEmitter) {
        // 未指派（lead 输出旧格式或漏 agent 字段）→ 回退 general：通用兜底且持有全量工具
        String resolved = (expert == null || expert.isBlank())
                ? AgentConstants.DEFAULT_AGENT : expert;
        // 提示词明确「专家名只是身份不是工具」：防止模型把 researcher 等名字误当工具调用
        String persona = "你是「" + resolved + "」专家 Agent，以该领域专家的方式执行子任务，直接给出完成结果。"
                + "只能调用系统提供的工具列表中的工具；专家名（researcher/coder/analyst/writer 等）"
                + "只是你的身份标识，绝不是可调用的工具。"
                + "工具使用纪律：网络类工具（fetchUrl/browser_navigate 等抓取与浏览）合计调用不超过 8 次；"
                + "同一 URL 只抓取一次；优先一次抓取多角度提取信息，材料足以支撑结论时立即停止调用工具并输出结果。";
        return chatCaller.call(sessionId, resolved, persona, task, toolEmitter);
    }

    private String predictAggregate(String sessionId, List<String> results) {
        return chatCaller.call(sessionId, ROLE_AGGREGATOR, AGGREGATOR_FALLBACK_PROMPT, aggregateUserPrompt(results),
                null, aggregateBudgetAdvisor());
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

    /**
     * 解析 lead 拆解返回的 JSON 子任务列表；非法返回空表。
     * 兼容两种格式：新格式 {@code {"subtasks":[{"desc":"..","agent":"researcher"}]}}，
     * 旧格式 {@code {"subtasks":[".."]}}（无指派，agent=null）；agent 名不在白名单一律回退 null。
     */
    private List<Subtask> parseSubtasks(String content) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(content);
            List<Subtask> out = new ArrayList<>();
            for (JsonNode s : node.path("subtasks")) {
                if (s.isTextual()) {
                    out.add(new Subtask(s.asText(), null)); // 旧格式：纯字符串
                } else {
                    String desc = s.path("desc").asText("");
                    String agent = normalizeAgent(s.path("agent").asText(null));
                    out.add(new Subtask(desc, agent));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[multi-agent] 拆解返回非法 JSON，回退处理：{}", safe(e));
            return new ArrayList<>();
        }
    }

    /** 专家名归一化：空白视为未指派；不在白名单的回退 null（执行时走默认客户端）。 */
    private static String normalizeAgent(String agent) {
        if (agent == null || agent.isBlank()) {
            return null;
        }
        String name = agent.trim();
        return EXPERT_WHITELIST.contains(name) ? name : null;
    }

    private static String safe(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
