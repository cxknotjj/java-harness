package com.dark.javaHarness.graph;

import java.util.HashMap;
import java.util.Map;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 状态编排本体（对应 DeerFlow 的 agents 层/Lead Agent 状态机）。
 *
 * <p>以「客服邮件处理」为例演示 Spring AI Alibaba Graph 的核心能力：
 * 节点（NodeAction）+ 普通边 + 条件边 + 全局共享状态（OverAllState）与键合并策略，
 * 并在 classify 节点内注入 ChatClient，让 LLM 参与是否紧急的决策。
 *
 * <p>数据流：
 * <pre>
 * START → classify(LLM 判断 urgent/normal)
 *          ├─ urgent → escalate(升级人工)
 *          └─ normal → draft(起草回复)
 *                                   └─→ finalize(汇总) → END
 * </pre>
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #compile()}：无 ChatModel，classify 用关键词兜底，脱离 API key 即可运行；</li>
 *   <li>{@link #compile(ChatClient)}：classify 调用 LLM 做语义分类，LLM 异常时回退关键词兜底。</li>
 * </ul>
 */
public final class SupportEmailGraph {

    private static final Logger log = LoggerFactory.getLogger(SupportEmailGraph.class);

    /** 状态键常量：统一命名，避免魔法字符串。 */
    public static final String KEY_INPUT = "input";
    public static final String KEY_LABEL = "label";
    public static final String KEY_MESSAGES = "messages";

    private static final String CLASSIFY_PROMPT =
            "你是客服邮件分类器。判断邮件是否紧急(urgent)。"
                    + "只回答一个词：紧急回复 urgent，非紧急回复 normal。邮件内容：%s";

    private SupportEmailGraph() {
    }

    /** 键合并策略：label 覆盖，messages 追加。 */
    private static KeyStrategyFactory keyStrategy() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(KEY_LABEL, new ReplaceStrategy());
            strategies.put(KEY_MESSAGES, KeyStrategy.APPEND);
            return strategies;
        };
    }

    /** 无 LLM 入口：classify 按关键词判定（便于不依赖 key 运行）。 */
    public static CompiledGraph compile() throws GraphStateException {
        return compile(null);
    }

    /** 注入 ChatClient 的入口：classify 节点用 LLM 语义分类。 */
    public static CompiledGraph compile(ChatClient chatClient) throws GraphStateException {
        StateGraph graph = new StateGraph(keyStrategy());

        // 1) 分类节点：若有 ChatModel，则调用 LLM 判断紧急程度；否则关键词兜底
        graph.addNode("classify", node_async(classifyAction(chatClient)));

        // 2) 升级节点
        graph.addNode("escalate", node_async(
                state -> Map.of(KEY_MESSAGES, "escalate to human: " + state.value(KEY_INPUT, ""))));

        // 3) 起草节点
        graph.addNode("draft", node_async(
                state -> Map.of(KEY_MESSAGES, "draft reply to: " + state.value(KEY_INPUT, ""))));

        // 4) 汇总结点
        graph.addNode("finalize", node_async(
                state -> Map.of(KEY_MESSAGES, "done [" + state.value(KEY_LABEL, "") + "]")));

        // 普通边
        graph.addEdge(START, "classify")
                .addEdge("escalate", "finalize")
                .addEdge("draft", "finalize")
                .addEdge("finalize", END);

        // 条件边：按 label 路由
        graph.addConditionalEdges("classify",
                edge_async(state -> {
                    String label = state.value(KEY_LABEL, "normal");
                    return "urgent".equals(label) ? "to-escalate" : "to-draft";
                }),
                Map.of("to-escalate", "escalate", "to-draft", "draft"));

        return graph.compile();
    }

    /** 构建 classify 节点动作：优先 LLM，异常或未注入则关键词兜底。 */
    private static NodeAction classifyAction(ChatClient chatClient) {
        return state -> {
            String input = state.value(KEY_INPUT, "");
            String label;
            if (chatClient != null) {
                try {
                    String reply = chatClient.prompt()
                            .user(CLASSIFY_PROMPT.formatted(input))
                            .call()
                            .content();
                    label = normalize(reply);
                    log.info("[graph] LLM 分类结果: '{}' -> {}", reply, label);
                } catch (Exception e) {
                    log.warn("[graph] LLM 分类失败，回退关键词兜底: {}", e.getMessage());
                    label = keywordFallback(input);
                }
            } else {
                label = keywordFallback(input);
            }
            return Map.of(KEY_LABEL, label, KEY_MESSAGES, "classified as " + label);
        };
    }

    private static String normalize(String reply) {
        if (reply != null && reply.toLowerCase().contains("urgent")) {
            return "urgent";
        }
        return "normal";
    }

    private static String keywordFallback(String input) {
        return input.toLowerCase().contains("urgent") ? "urgent" : "normal";
    }
}