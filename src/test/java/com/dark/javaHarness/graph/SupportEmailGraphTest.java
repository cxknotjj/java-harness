package com.dark.javaHarness.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.graph.SupportEmailRuntime.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

/**
 * SupportEmailGraph 状态编排行为验证：
 * - normal 输入走 draft 分支（finalize 后 label=normal）
 * - urgent 输入走 escalate 分支（finalize 后 label=urgent）
 * - 注入 ChatClient 后，classify 由 LLM 决策（mock 返回 urgent/normal）
 * - LLM 决策路径尊重模型返回值（非关键词兜底）
 * - 事件按追加策略累积
 */
@ExtendWith(MockitoExtension.class)
class SupportEmailGraphTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClientRequestSpec requestSpec;
    @Mock
    private CallResponseSpec callSpec;

    private void stubLlm(String reply) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(reply);
    }

    @Test
    void keywordFallback_normalTicketsGoToDraftBranch() {
        RunResult result = SupportEmailRuntime.run("how to reset my password?");
        assertEquals("normal", result.label());
        assertTrue(result.events().contains("draft reply to: how to reset my password?"));
        assertTrue(result.events().contains("done [normal]"));
    }

    @Test
    void keywordFallback_urgentTicketsGoToEscalateBranch() {
        RunResult result = SupportEmailRuntime.run("UPGRADED billing is urgent please check");
        assertEquals("urgent", result.label());
        assertTrue(result.events().contains("escalate to human: UPGRADED billing is urgent please check"));
        assertTrue(result.events().contains("done [urgent]"));
    }

    @Test
    void llmDecision_classifyByModel_urgent() {
        stubLlm("urgent");
        // 文本里没有 urgent 关键词，但 LLM 判定紧急 → 走 escalate 分支（证明由 LLM 决策）
        RunResult result = SupportEmailRuntime.run("my account got charged twice", chatClient);
        assertEquals("urgent", result.label());
        assertTrue(result.events().contains("escalate to human: my account got charged twice"));
    }

    @Test
    void llmDecision_classifyByModel_normal() {
        stubLlm("normal");
        // 文本里含 urgent 关键词（"urgently"），但 LLM 判定非紧急 → 走 draft 分支（证明尊重模型而非关键词）
        RunResult result = SupportEmailRuntime.run("we urgently need a feature request? no", chatClient);
        assertEquals("normal", result.label());
        assertTrue(result.events().contains("draft reply to: we urgently need a feature request? no"));
    }
}