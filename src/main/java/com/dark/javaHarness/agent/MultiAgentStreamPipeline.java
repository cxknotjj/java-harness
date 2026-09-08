package com.dark.javaHarness.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 多 Agent 编排的流式管道：「stream 主干帧 + 生命周期钩子旁路」双通道的合并与行映射，
 * 从编排器中独立出的纯管道组件（与 {@link BranchProgressListener}/{@link ProgressLine} 伴生）。
 *
 * <p>主干：{@link CompiledGraph#stream(Map)} 帧 → {@link #toRows} 行；
 * 旁路一：{@link BranchProgressListener} 补齐 stream 合并掉的并行分支「子任务完成」事件；
 * 旁路二：聚合节点内逐 token 实时推送最终回答（含首个 token 前的「聚合」进度行）；
 * 旁路三：子任务节点内工具调用起止。图拓扑经 {@link StreamingGraphCompiler} 每次执行独立
 * 构建（旁路 sink 绑定本次执行，保证并发安全），全新执行与断点续跑仅 RunnableConfig 不同。
 *
 * <p>⚠️ 死锁教训：关闸 {@code doFinally} 必须挂在 mergeWith **之前**的主干段上——
 * merge 要求两源都终结才向下传 complete，关闸挂 merge 之后会循环等待、永不收尾。
 */
final class MultiAgentStreamPipeline {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MultiAgentStreamPipeline.class);

    /** 流式拓扑编译器：每次执行独立建图并编译（聚合节点绑定本次执行的 token 旁路 sink） */
    interface StreamingGraphCompiler {
        CompiledGraph compile(Sinks.Many<String> liveTokens, AtomicBoolean contentSent,
                              Sinks.Many<String> toolEvents, AtomicBoolean cancelled,
                              com.alibaba.cloud.ai.graph.GraphLifecycleListener listener)
                throws GraphStateException;
    }

    private final StreamingGraphCompiler compiler;

    MultiAgentStreamPipeline(StreamingGraphCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * 流式执行：构建带旁路的图 → 编译（挂监听器 + 检查点）→ 主干帧合并旁路流。
     * input 由编排器组装（objective/sessionId + 预算账本）；objective 另传一份供
     * END 帧内容兜底（续跑越过聚合节点时 final 已在检查点状态里）。
     */
    Flux<String> run(java.util.Map<String, Object> input, String objective, RunnableConfig config) {
        AtomicBoolean contentSent = new AtomicBoolean(false);
        // 客户端断开（Reactor cancel）置位：后续 superstep 的节点短路，不再发起新的 LLM 调用
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Sinks.Many<String> branchEvents = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<String> liveTokens = Sinks.many().unicast().onBackpressureBuffer();
        // 旁路三：子任务节点内的工具调用起止（专家执行工具时 CLI 展示工具调用行）
        Sinks.Many<String> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
        CompiledGraph streamingGraph;
        try {
            streamingGraph = compiler.compile(liveTokens, contentSent, toolEvents, cancelled,
                    new BranchProgressListener(branchEvents,
                            MultiAgentGraphAgent.SUBTASK_NODE_PREFIX,
                            MultiAgentGraphAgent.K_SUBTASK_PREFIX));
        } catch (GraphStateException e) {
            throw new IllegalStateException("编译带监听器的 StateGraph 失败", e);
        }

        // 关闸在 merge 之前（见死锁说明）；complete 与 next 共用同一把锁，防迟到事件竞争。
        // 注意：图节点异常终止时 graph-core 也会以 CANCEL 清理主干订阅，因此主干段的
        // doFinally 不能用于判定「客户端断开」（会把编排异常误报为断开）；
        // 真实断开判定挂在外层合并流上——只有下游（CLI/HTTP）真正断开才会 cancel 到这里。
        Flux<String> mainLine = streamingGraph.stream(input, config)
                .concatMap(out -> toRows(out, objective, contentSent))
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
    static Flux<String> toRows(NodeOutput out, String objective, AtomicBoolean contentSent) {
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
                    : state.value(MultiAgentGraphAgent.K_FINAL, String.class).orElse(null);
            return Flux.just(fin == null || fin.isBlank() ? objective : fin);
        }
        if (MultiAgentGraphAgent.NODE_LEAD.equals(node)) {
            int n = state.value(MultiAgentGraphAgent.K_SUBTASK_COUNT, Integer.class).orElse(0);
            return Flux.just(ProgressLine.encode("拆解", n + " 个子任务已就绪"));
        }
        if (MultiAgentGraphAgent.NODE_AGGREGATE.equals(node)) {
            // 流式模式：token 已由聚合节点内旁路实时推送，此处不再重复发射
            if (contentSent.get()) {
                return Flux.empty();
            }
            String fin = state.value(MultiAgentGraphAgent.K_FINAL, String.class).orElse(null);
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

    /**
     * 恢复点选择：
     * - 编排已完整跑完（存在 nextNodeId=END 且 final 有效的检查点）→ 选它，续跑零 LLM 调用、直接回放最终回答；
     * - 聚合未完成（客户端断开时聚合被短路，最新检查点无有效 final）→ 回退到聚合前的检查点
     *   （nextNodeId=aggregate，state 已含子任务结果），续跑只补跑聚合；
     * - 都没有（断开极早，如 lead 中断）→ 兜底取任一检查点（通常 lead 之后，重跑缺口最小）。
     */
    static Checkpoint selectResumeCheckpoint(Collection<Checkpoint> checkpoints) {
        Checkpoint any = checkpoints.iterator().next();
        return checkpoints.stream()
                .filter(cp -> StateGraph.END.equals(cp.getNextNodeId()))
                .filter(cp -> {
                    Object fin = cp.getState() == null ? null : cp.getState().get(MultiAgentGraphAgent.K_FINAL);
                    return fin instanceof String s && !s.isBlank();
                })
                .findFirst()
                .orElseGet(() -> checkpoints.stream()
                        .filter(cp -> MultiAgentGraphAgent.NODE_AGGREGATE.equals(cp.getNextNodeId()))
                        .findFirst()
                        .orElse(any));
    }

    private static String safe(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
