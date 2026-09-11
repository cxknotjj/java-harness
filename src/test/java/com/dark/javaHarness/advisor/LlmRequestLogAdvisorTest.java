package com.dark.javaHarness.advisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * LlmRequestLogAdvisor 单测：发起日志的口径（条数 = 物化后的消息列表，
 * ctxChars = 全部消息文本字符量，model/maxTokens 取自请求选项）。
 */
class LlmRequestLogAdvisorTest {

    private ChatClientRequest request(ChatOptions options) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(
                        new SystemMessage("你是聚合助手"),
                        new UserMessage("【子任务1】结果A"),
                        new AssistantMessage("历史回答")), options))
                .context(Map.of())
                .build();
    }

    @Test
    void format_containsAgentModelCountsAndChars() {
        LlmRequestLogAdvisor advisor = new LlmRequestLogAdvisor("aggregator", "76");
        String line = advisor.format(request(OpenAiChatOptions.builder()
                .model("qwen3.7-flash").maxTokens(8192).build()), "STREAM");

        assertTrue(line.contains("agent=aggregator"), line);
        assertTrue(line.contains("model=qwen3.7-flash"), line);
        assertTrue(line.contains("sessionId=76"), line);
        assertTrue(line.contains("kind=STREAM"), line);
        assertTrue(line.contains("msgs=3"), line);
        assertTrue(line.contains("ctxChars="), line);
        assertTrue(line.contains("maxTokens=8192"), line);
        assertEquals("你是聚合助手".length() + "【子任务1】结果A".length() + "历史回答".length(),
                Integer.parseInt(line.replaceAll(".*ctxChars=(\\d+).*", "$1")), line);
    }

    @Test
    void format_genericOptions_onlyModel() {
        LlmRequestLogAdvisor advisor = new LlmRequestLogAdvisor("lead", null);
        String line = advisor.format(request(ChatOptions.builder().model("qwen3.8-27b").build()), "CALL");
        assertTrue(line.contains("model=qwen3.8-27b"), line);
        assertTrue(line.contains("maxTokens=null"), line);
    }

    @Test
    void order_afterBudgetAdvisors_beforeModelBridge() {
        int order = new LlmRequestLogAdvisor("general", null).getOrder();
        // 必须晚于 ContextAssembling(+1)/PromptBudget(+2)（记录裁剪后请求），
        // 且严格早于 Integer.MAX_VALUE——与模型桥接 advisor 平局会导致本 advisor 永不执行
        assertTrue(order > org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 2, "order=" + order);
        assertTrue(order < Integer.MAX_VALUE, "order=" + order);
    }
}
