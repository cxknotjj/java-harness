package com.dark.javaHarness.advisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.tool.TokenEstimator;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * PromptBudgetAdvisor 静态 prompt 预算单测：
 * - 预算内直通（请求对象不改写）
 * - 通用语义：单段超预算 → 尾部截断 + 截断标记
 * - 聚合语义：多段超预算 → 每份等额、头尾保留、带截断标记（禁止先到先得）
 * - 超限行为：预算后总 token ≤ 预算（TokenEstimator 口径自洽）；model 等 options 保留
 */
class PromptBudgetAdvisorTest {

    private static final Pattern SUBTASK_HEADER = Pattern.compile("【子任务\\d+】");

    private ChatClientRequest requestOf(List<Message> messages) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context(new HashMap<>())
                .build();
    }

    private ChatClientRequest requestOf(List<Message> messages, OpenAiChatOptions options) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages, options))
                .context(new HashMap<>())
                .build();
    }

    private static String cjk(int n) {
        return "目".repeat(n); // 全 CJK：1 字符 = 1 token（估算口径）
    }

    @Test
    void withinBudget_passesThroughUnchanged() {
        PromptBudgetAdvisor advisor = PromptBudgetAdvisor.tail(1000);
        ChatClientRequest request = requestOf(List.of(
                new SystemMessage("你是助手"),
                new UserMessage("一个很短的目标")));

        assertSame(request, advisor.apply(request), "预算内应原样直通（同一请求实例）");
    }

    @Test
    void tail_truncatesOverlongUser_withMarker() {
        PromptBudgetAdvisor advisor = PromptBudgetAdvisor.tail(100);
        ChatClientRequest request = requestOf(List.of(
                new SystemMessage("你是助手"),   // 4 token
                new UserMessage(cjk(300))));     // 300 token

        ChatClientRequest out = advisor.apply(request);

        String newText = out.prompt().getInstructions().get(1).getText();
        assertTrue(newText.endsWith(PromptBudgetAdvisor.TRUNCATED_SUFFIX), "尾截应带截断标记");
        // 预算后总 token ≤ 上限（估算口径自洽）
        int total = out.prompt().getInstructions().stream()
                .mapToInt(m -> TokenEstimator.estimateTokens(m.getText()))
                .sum();
        assertTrue(total <= 100, "预算后总 token 应 ≤ 100，实际 " + total);
        // 头部保留：开头内容不丢
        assertTrue(newText.startsWith(cjk(10)), "尾截应保留头部内容");
    }

    @Test
    void sections_truncatesEachSectionEqually_keepsHeadAndTail() {
        PromptBudgetAdvisor advisor = PromptBudgetAdvisor.sections(120, SUBTASK_HEADER);
        // preamble 4 token + 4 节（各 106 token）= 428 token > 120 → 每节等份额 29
        StringBuilder user = new StringBuilder("请汇总：\n");
        for (int i = 1; i <= 4; i++) {
            user.append("【子任务").append(i).append("】").append(cjk(100));
        }
        ChatClientRequest request = requestOf(List.of(new UserMessage(user.toString())));

        ChatClientRequest out = advisor.apply(request);

        String newText = out.prompt().getInstructions().get(0).getText();
        // 每节节头都保留（禁止先到先得挤掉后面的节）
        for (int i = 1; i <= 4; i++) {
            assertTrue(newText.contains("【子任务" + i + "】"), "节头【子任务" + i + "】应保留");
        }
        // 每节超份额都截断且带标记
        assertEquals(4, newText.split("本节内容已截断", -1).length - 1,
                "4 个超份额节都应带截断标记");
        // 头尾保留：每节开头是节头+部分内容（份额 28、标记 9 → 头尾各 9 字符）
        assertTrue(newText.contains("【子任务1】" + cjk(3) + SECTION_SUFFIX_TEXT),
                "每节应保留头部（节头+部分内容）");
        int total = TokenEstimator.estimateTokens(newText);
        assertTrue(total <= 120, "预算后总 token 应 ≤ 120，实际 " + total);
    }

    /** 节内截断标记原文（SectionTruncator.SECTION_SUFFIX，辅助断言可读性） */
    private static final String SECTION_SUFFIX_TEXT = "\n…[本节内容已截断]…\n";

    @Test
    void sections_withoutHeaderStructure_fallsBackToTail() {
        PromptBudgetAdvisor advisor = PromptBudgetAdvisor.sections(50, SUBTASK_HEADER);
        ChatClientRequest request = requestOf(List.of(new UserMessage(cjk(200))));

        ChatClientRequest out = advisor.apply(request);

        String newText = out.prompt().getInstructions().get(0).getText();
        assertTrue(newText.endsWith(PromptBudgetAdvisor.TRUNCATED_SUFFIX), "无节结构应退化为尾截");
        assertTrue(TokenEstimator.estimateTokens(newText) <= 50);
    }

    @Test
    void sections_withinBudget_passesThroughUnchanged() {
        PromptBudgetAdvisor advisor = PromptBudgetAdvisor.sections(12000, SUBTASK_HEADER);
        ChatClientRequest request = requestOf(List.of(new UserMessage("【子任务1】短结果")));

        assertSame(request, advisor.apply(request), "预算内应原样直通");
    }

    @Test
    void apply_preservesModelOptions() {
        // 防止回归：改写 user 重建 Prompt 时必须保留 options（尤其 model 参数，否则厂商端 400）
        PromptBudgetAdvisor advisor = PromptBudgetAdvisor.tail(50);
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("qwen3.7-flash").build();
        ChatClientRequest request = requestOf(List.of(new UserMessage(cjk(200))), options);

        ChatClientRequest out = advisor.apply(request);

        assertEquals("qwen3.7-flash", out.prompt().getOptions().getModel(), "改写后应保留 model 参数");
    }

    @Test
    void apply_withoutUserMessage_returnsUnchanged() {
        PromptBudgetAdvisor advisor = PromptBudgetAdvisor.tail(10);
        ChatClientRequest request = requestOf(List.of(new SystemMessage("只有 system")));

        assertSame(request, advisor.apply(request), "无 user 消息无从改写，应原样返回");
    }

    @Test
    void fitPrefix_and_fitSuffix_respectLimit() {
        String text = cjk(100);
        assertEquals(30, TokenEstimator.estimateTokens(PromptBudgetAdvisor.fitPrefix(text, 30)));
        assertTrue(TokenEstimator.estimateTokens(PromptBudgetAdvisor.fitPrefix(text, 30)) <= 30);
        assertEquals(20, TokenEstimator.estimateTokens(PromptBudgetAdvisor.fitSuffix(text, 20)));
        assertTrue(text.endsWith(PromptBudgetAdvisor.fitSuffix(text, 20)), "后缀应是原文结尾");
        assertEquals("", PromptBudgetAdvisor.fitPrefix(text, 0), "limit≤0 应返回空串");
        assertEquals("", PromptBudgetAdvisor.fitSuffix(text, -1), "limit≤0 应返回空串");
        assertFalse(PromptBudgetAdvisor.fitSuffix(text, 20).isEmpty());
    }
}
