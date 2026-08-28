package com.dark.javaHarness.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SignalType;

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

    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService,
                                ToolAssignments toolAssignments,
                                LlmCallRecorder recorder) {
        this.agentName = agentName;
        this.chatCaller = new AgentChatCaller(clientRegistry, agentService, toolAssignments, recorder);
        try {
            this.stateGraph = buildStateGraph();
            // 同步执行用的常驻实例
            this.graph = stateGraph.compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("构建/编译多 Agent 编排 StateGraph 失败", e);
        }
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

        return graph.invoke(input)
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
                    .compile(CompileConfig.builder()
                            .withLifecycleListener(new BranchProgressListener(
                                    branchEvents, SUBTASK_NODE_PREFIX, K_SUBTASK_PREFIX))
                            .build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("编译带监听器的 StateGraph 失败", e);
        }

        // 关闸在 merge 之前（见死锁说明）；complete 与 next 共用同一把锁，防迟到事件竞争
        Flux<String> mainLine = streamingGraph.stream(input)
                .concatMap(out -> toRows(out, goal.objective(), contentSent))
                .doFinally(sig -> {
                    // 下游断开 → cancel 信号在此收敛：置位短路标志 + 关闸旁路 sink，编排不再消耗 LLM
                    if (sig == SignalType.CANCEL) {
                        cancelled.set(true);
                        log.warn("[multi-agent] 客户端已断开，终止编排：不再发起新的 LLM 调用（进行中的调用等待自然结束）");
                    }
                    BranchProgressListener.tryCompleteSerialized(branchEvents);
                    BranchProgressListener.tryCompleteSerialized(liveTokens);
                    BranchProgressListener.tryCompleteSerialized(toolEvents);
                });

        return mainLine
                .mergeWith(branchEvents.asFlux())
                .mergeWith(liveTokens.asFlux())
                .mergeWith(toolEvents.asFlux())
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
        // END 帧：确保一定有内容行（防 aggregate 帧 state 未含 final 的时序差异）
        if (out.isEND()) {
            return contentSent.get() ? Flux.empty() : Flux.just(objective);
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
        StateGraph g = new StateGraph();
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
            log.info("[multi-agent][aggregate] 客户端已断开，跳过聚合调用");
            Map<String, Object> skipped = new HashMap<>();
            skipped.put(K_FINAL, "（客户端已断开，编排已终止）");
            return skipped;
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
     * 流式异常时回退阻塞调用——已推出过 token 则以已收内容为准（避免内容重复），
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
                    });
            return collected.toString();
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

    /** lead 拆解：按 agent 表 lead 行的提示词/模型执行（无配置时回退内置兜底） */
    private String predictLead(String sessionId, String objective) {
        return chatCaller.call(sessionId, ROLE_LEAD, LEAD_FALLBACK_PROMPT, "拆解目标：" + objective);
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
        String persona = "你是「" + resolved + "」专家 Agent，以该领域专家的方式执行子任务，直接给出完成结果。";
        return chatCaller.call(sessionId, resolved, persona, task, toolEmitter);
    }

    private String predictAggregate(String sessionId, List<String> results) {
        return chatCaller.call(sessionId, ROLE_AGGREGATOR, AGGREGATOR_FALLBACK_PROMPT, aggregateUserPrompt(results));
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
