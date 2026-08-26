package com.dark.javaHarness.advisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * ContextAssemblingAdvisor 上下文组装单测：
 * - 过滤空消息与系统占位噪声
 * - 归一化 role 顺序（system 置前，user/assistant 交替）
 * - 按 token 预算从旧丢弃（保留 system + 最近消息）
 */
class ContextAssemblingAdvisorTest {

    private static final long BIG_TEXT = 4000L; // 用于触发 token 预算裁剪

    private ContextAssemblingAdvisor advisor() {
        return new ContextAssemblingAdvisor();
    }

    private String longText(long n) {
        return "a".repeat((int) n);
    }

    @Test
    void assemble_filtersEmptyMessages() {
        ContextAssemblingAdvisor a = advisor();
        List<Message> result = a.assemble(List.of(
                new UserMessage("   "),
                new UserMessage("你好")));

        assertEquals(1, result.size(), "空消息应被过滤");
        assertEquals("你好", result.get(0).getText());
    }

    @Test
    void assemble_systemPlacedFirst() {
        ContextAssemblingAdvisor a = advisor();
        List<Message> result = a.assemble(List.of(
                new UserMessage("问题"),
                new SystemMessage("你是助手")));

        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof SystemMessage, "system 应置于最前");
        assertTrue(result.get(1) instanceof UserMessage);
    }

    @Test
    void assemble_mergesConsecutiveSameRole() {
        ContextAssemblingAdvisor a = advisor();
        List<Message> result = a.assemble(List.of(
                new UserMessage("问1"),
                new UserMessage("问2"),
                new AssistantMessage("答2")));

        // 两个连续的 user → 合并为最新一个（问2）
        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof UserMessage);
        assertEquals("问2", result.get(0).getText(), "连续 user 应保留最后一个");
        assertTrue(result.get(1) instanceof AssistantMessage);
    }

    @Test
    void assemble_trimsOldMessagesByTokenBudget_keepsRecent() {
        // 构造超预算历史：system + 很长的旧 user + 短的最近 assistant
        ContextAssemblingAdvisor small = new ContextAssemblingAdvisor(100);
        List<Message> result = small.assemble(List.of(
                new SystemMessage("system-prompt"),
                new UserMessage(longText(BIG_TEXT)),  // 旧且超长
                new AssistantMessage("short-answer")));

        // token 超预算 → 从最旧丢弃那个超长 user（非 system），保留其余
        assertFalse(result.stream().anyMatch(m -> m.getText().equals(longText(BIG_TEXT))),
                "超预算的最旧长消息应被丢弃");
        assertTrue(result.stream().anyMatch(m -> m instanceof SystemMessage), "system 应保留");
    }

    @Test
    void assemble_underBudget_preservesAll() {
        ContextAssemblingAdvisor a = advisor(); // 默认 4000
        List<Message> result = a.assemble(List.of(
                new SystemMessage("s"),
                new UserMessage("hi"),
                new AssistantMessage("hello")));

        assertEquals(3, result.size());
    }
}