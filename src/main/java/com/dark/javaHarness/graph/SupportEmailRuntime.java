package com.dark.javaHarness.graph;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.alibaba.cloud.ai.graph.OverAllState;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 运行时承载与调度（对应 DeerFlow 的 runtime 层：RunManager / run_agent() / StreamBridge）。
 *
 * <p>它不关心图内部如何流转，只负责：
 * 1. 接收输入文本 → 作为初始状态注入；
 * 2. 调度已编译图执行（run_agent 的职责）；
 * 3. 消费最终状态快照，产出「可流式推送」的事件列表（StreamBridge 的职责，此处以 List 简化）。
 */
public final class SupportEmailRuntime {

    /** 一次运行的聚合结果：标签 + 事件列表 + 拼接输出。 */
    public record RunResult(String label, java.util.List<String> events, String output) {
    }

    private SupportEmailRuntime() {
    }

    /** 无 ChatClient 执行：classify 走关键词兜底（不依赖 key）。 */
    public static RunResult run(String input) {
        return run(input, null);
    }

    /** 注入 ChatClient 执行：classify 由 LLM 决策。 */
    public static RunResult run(String input, ChatClient chatClient) {
        Map<String, Object> initialState = Map.of(SupportEmailGraph.KEY_INPUT, input == null ? "" : input);

        Optional<OverAllState> result;
        try {
            result = SupportEmailGraph.compile(chatClient).invoke(initialState);
        } catch (Exception e) {
            throw new IllegalStateException("graph 执行失败", e);
        }

        OverAllState state = result.orElseThrow(() -> new IllegalStateException("graph 未返回任何状态"));

        String label = state.value(SupportEmailGraph.KEY_LABEL, "?");
        List<String> events = state.value(SupportEmailGraph.KEY_MESSAGES, List.class).orElseGet(List::of);
        String output = String.join(" -> ", events);
        return new RunResult(label, events, output);
    }

    /** 简化 main：便于手动验证。 */
    public static void main(String[] args) {
        RunResult normal = SupportEmailRuntime.run("how to reset my password?");
        System.out.println("[normal] label=" + normal.label() + " | events: " + normal.events());

        RunResult urgent = SupportEmailRuntime.run("UPGRADED billing is urgent please check");
        System.out.println("[urgent] label=" + urgent.label() + " | events: " + urgent.events());
    }
}