package com.dark.javaHarness.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 多 Agent 编排器：路径 B（复杂请求）的执行体。
 *
 * <p>基于 {@link StateGraph} 编排「Lead 拆解 → 并行子任务 → 聚合」三阶段，
 * 每个阶段都是一次独立的 ChatClient 单次调用（复用 {@link ChatClientRegistry} 的客户端）：
 * - lead 节点：把复杂目标拆成至多 {@link #MAX_SUBTASKS} 条子任务（JSON 解析）
 * - subtask-i 节点：并行执行，各自调用模型产出该子任务结果
 * - aggregate 节点：收集各子任务结果，调用模型汇总成最终回答
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

    /** 默认系统提示词（这里作为兜底；模型名取 agent 表 multi-agent 行，无则默认） */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个执行任务的通用 AI 助手，请直接给出简洁、可执行的完成结果。";

    /** 单个总任务拆解的子任务数上限，避免滚雪球 */
    public static final int MAX_SUBTASKS = 4;

    /** 节点名常量 */
    private static final String NODE_LEAD = "lead";
    private static final String NODE_AGGREGATE = "aggregate";

    /** 状态键 */
    private static final String K_OBJECTIVE = "objective";
    private static final String K_SUBTASK_COUNT = "subtaskCount";
    private static final String K_SUBTASK_PREFIX = "subtask_";
    private static final String K_RESULT_PREFIX = "result_";
    private static final String K_FINAL = "final";

    /** 子任务节点名前缀 */
    private static final String SUBTASK_NODE_PREFIX = "subtask-";

    private final String agentName;
    private final ChatClientRegistry clientRegistry;
    private final AgentService agentService;
    /** 图拓扑（构建一次）：同步执行缓存编译 {@link #graph}；流式执行每次带监听器重新编译 */
    private final StateGraph stateGraph;
    private final CompiledGraph graph;

    public MultiAgentGraphAgent(String agentName,
                                ChatClientRegistry clientRegistry,
                                AgentService agentService) {
        this.agentName = agentName;
        this.clientRegistry = clientRegistry;
        this.agentService = agentService;
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

        return graph.invoke(input)
                .flatMap(s -> s.value(K_FINAL, String.class))
                .orElse(goal.objective());
    }

    /**
     * 流式执行复杂目标：stream 主干帧 + 生命周期钩子旁路 双通道。
     *
     * <p>主干：{@link CompiledGraph#stream(Map)} 帧 → {@link #toRows} 行；
     * 旁路：{@link BranchProgressListener} 补齐 stream 合并掉的并行分支「子任务完成」事件。
     *
     * <p>⚠️ 死锁教训：关闸 {@code doFinally} 必须挂在 mergeWith **之前**的主干段上——
     * merge 要求两源都终结才向下传 complete，关闸挂 merge 之后会循环等待、永不收尾。
     */
    @Override
    public Flux<String> executeStreamReactive(Goal goal) {
        Map<String, Object> input = new HashMap<>();
        input.put(K_OBJECTIVE, goal.objective());
        AtomicBoolean contentSent = new AtomicBoolean(false);

        Sinks.Many<String> branchEvents = Sinks.many().unicast().onBackpressureBuffer();
        CompiledGraph streamingGraph;
        try {
            streamingGraph = stateGraph.compile(CompileConfig.builder()
                    .withLifecycleListener(new BranchProgressListener(branchEvents))
                    .build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("编译带监听器的 StateGraph 失败", e);
        }

        // 关闸在 merge 之前（见死锁说明）；complete 与 next 共用同一把锁，防迟到事件竞争
        Flux<String> mainLine = streamingGraph.stream(input)
                .concatMap(out -> toRows(out, goal.objective(), contentSent))
                .doFinally(sig -> tryCompleteSerialized(branchEvents));

        return mainLine.mergeWith(branchEvents.asFlux())
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

    /* ---------------- 流式旁路监听器 ---------------- */

    /**
     * 并行分支进度监听器：before/after 配对过滤 lead 未布置的短路槽位
     * （进入节点的输入态必含已布置的 subtask_i，空槽位执行即短路返回空 map），
     * 仅对真实执行的子任务经旁路 Sink 播报「完成」，避免向 CLI 推虚假进度。
     */
    static final class BranchProgressListener implements GraphLifecycleListener {

        private final Sinks.Many<String> events;
        /** before 登记已布置槽位，after 消费：不在集合中即静默 */
        private final Set<Integer> scheduled = ConcurrentHashMap.newKeySet();

        BranchProgressListener(Sinks.Many<String> events) {
            this.events = events;
        }

        @Override
        public void before(String node, Map<String, Object> state,
                           RunnableConfig config, Long costMillis) {
            Integer idx = subtaskIndexIfAny(node);
            if (idx != null && state != null) {
                Object task = state.get(K_SUBTASK_PREFIX + idx);
                if (task instanceof String s && !s.isBlank()) {
                    scheduled.add(idx);
                }
            }
        }

        @Override
        public void after(String node, Map<String, Object> state,
                          RunnableConfig config, Long costMillis) {
            Integer idx = subtaskIndexIfAny(node);
            if (idx == null || !scheduled.remove(idx)) {
                return; // 非子任务帧或短路槽位：静默
            }
            log.info("[multi-agent][hook] subtask-{} 完成", idx);
            tryEmitSerialized(events,
                    ProgressLine.encode("子任务", "第 " + (idx + 1) + " 个子任务完成"));
        }
    }

    /* ---------------- 工具方法 ---------------- */

    /** 子任务节点名 */
    private String subtaskName(int i) {
        return SUBTASK_NODE_PREFIX + i;
    }

    /** 节点名为 "subtask-{i}" 时返回索引 i，否则返回 null（供生命周期钩子判定是否子任务帧）。 */
    private static Integer subtaskIndexIfAny(String nodeName) {
        if (nodeName == null || !nodeName.startsWith(SUBTASK_NODE_PREFIX)) {
            return null;
        }
        int idx = parseSubtaskIndex(nodeName);
        return idx >= 0 ? idx : null;
    }

    /** 从 "subtask-{i}" 解析索引，非法返回 -1。 */
    private static int parseSubtaskIndex(String nodeName) {
        try {
            return Integer.parseInt(nodeName.substring(SUBTASK_NODE_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /* ---------------- StateGraph 构建 ---------------- */

    private StateGraph buildStateGraph() throws GraphStateException {
        StateGraph g = new StateGraph();

        // lead：拆解复杂目标为多条子任务
        g.addNode(NODE_LEAD, AsyncNodeAction.node_async(this::lead));
        // 子任务池：固定 MAX_SUBTASKS 个并行节点
        for (int i = 0; i < MAX_SUBTASKS; i++) {
            final int idx = i;
            g.addNode(subtaskName(idx), AsyncNodeAction.node_async(state -> subtask(state, idx)));
        }
        // 聚合：收集各子任务结果生成最终回答
        g.addNode(NODE_AGGREGATE, AsyncNodeAction.node_async(this::aggregate));

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

    /** lead：把 objective 拆解为 N 条子任务，写 subtask_0..subtask_{n-1} 与 subtaskCount。 */
    private Map<String, Object> lead(OverAllState state) {
        String objective = state.value(K_OBJECTIVE, String.class).orElse("");
        String content = predictLead(objective);
        List<String> items = parseSubtasks(content);
        if (items.isEmpty()) {
            items.add(objective); // 拆解失败：退化为单个子任务=objective
        }
        Map<String, Object> updates = new HashMap<>();
        int n = Math.min(items.size(), MAX_SUBTASKS);
        updates.put(K_SUBTASK_COUNT, n);
        for (int i = 0; i < n; i++) {
            updates.put(K_SUBTASK_PREFIX + i, items.get(i));
        }
        log.info("[multi-agent][lead] 拆解为 {} 个子任务", n);
        return updates;
    }

    /** 子任务节点：读 subtask_i，若存在则调用 ChatClient 生成 result_i。 */
    private Map<String, Object> subtask(OverAllState state, int idx) {
        String task = state.value(K_SUBTASK_PREFIX + idx, String.class).orElse(null);
        if (task == null || task.isBlank()) {
            return new HashMap<>(); // lead 未设置该子任务 → 快速短路
        }
        String result = predictSubtask(task);
        Map<String, Object> updates = new HashMap<>();
        updates.put(K_RESULT_PREFIX + idx, result);
        log.info("[multi-agent][subtask-{}] 完成，结果长度={}", idx, result.length());
        return updates;
    }

    /** 聚合：读 result_0..result_{n-1}（按 subtaskCount），调用 ChatClient 汇总为 final。 */
    private Map<String, Object> aggregate(OverAllState state) {
        int n = state.value(K_SUBTASK_COUNT, Integer.class).orElse(0);
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
        } else {
            finalAnswer = predictAggregate(results);
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(K_FINAL, finalAnswer);
        log.info("[multi-agent][aggregate] 汇总 {} 个子任务结果", results.size());
        return updates;
    }

    /* ---------- ChatClient 单次调用 ---------- */

    private String predictLead(String objective) {
        String sys = "你是多 Agent 的 Lead 拆解器。把用户复杂目标拆解为若干条可并行执行的子任务。"
                + "只输出一行 JSON，格式：{\"subtasks\":[\"子任务1\",\"子任务2\",...]}，不要任何解释。";
        return call(sys, "拆解目标：" + objective);
    }

    private String predictSubtask(String task) {
        return call("你是执行子任务的 AI 助手，直接给出该子任务的完成结果。", task);
    }

    private String predictAggregate(List<String> results) {
        StringBuilder sb = new StringBuilder("以下是各子任务结果，请汇总为一份完整、连贯的最终回答：\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append("【子任务").append(i + 1).append("】\n").append(results.get(i)).append("\n\n");
        }
        return call("你是聚合汇总的 AI 助手，依据多个子结果的最终回答可直接呈现给用户。", sb.toString());
    }

    /** 单次 ChatClient 调用：按 model 从注册表取客户端，system+user 一次调用返回 content。 */
    private String call(String system, String user) {
        AgentConfig config = agentService == null ? null
                : agentService.getAgentConfig(agentName).orElse(null);
        String model = config != null ? config.model() : null;
        ChatClient client = clientRegistry.get(model);
        return client.prompt()
                .system(config != null && config.prompt() != null ? config.prompt() : DEFAULT_SYSTEM_PROMPT)
                .user(system + "\n" + user)
                .call()
                .content();
    }

    /** 解析 lead 拆解返回的 JSON 子任务列表；非法返回空表。 */
    private List<String> parseSubtasks(String content) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(content);
            List<String> out = new ArrayList<>();
            for (JsonNode s : node.path("subtasks")) {
                out.add(s.asText());
            }
            return out;
        } catch (Exception e) {
            log.warn("[multi-agent] 拆解返回非法 JSON，回退处理：{}", safe(e));
            return new ArrayList<>();
        }
    }

    private static String safe(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** 串行化向旁路 Sink 发射一条事件（并行钩子线程可能同时回调，Reactor 单播 Sink 拒绝并发发射）。 */
    private static void tryEmitSerialized(Sinks.Many<String> sink, String line) {
        synchronized (sink) {
            sink.tryEmitNext(line);
        }
    }

    /** 串行化关闸：与 {@link #tryEmitSerialized} 共用同一把锁，防止迟到发射与 complete 竞争。 */
    private static void tryCompleteSerialized(Sinks.Many<String> sink) {
        synchronized (sink) {
            sink.tryEmitComplete();
        }
    }
}
