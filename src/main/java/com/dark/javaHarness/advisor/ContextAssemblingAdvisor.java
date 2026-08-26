package com.dark.javaHarness.advisor;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * 执行上下文组装拦截器（Spring AI Advisor）。
 *
 * <p>职责：对一次 ChatClient 调用中已注入的 messages 序列做
 * 「执行上下文组装」——过滤空/系统噪声、保证 role 交替顺序、按 token 预算从旧丢弃。
 * 它是纯横切增强：不负责"从哪里加载历史"（历史加载由 Agent 侧 SessionService 完成，
 * 与本 Advisor 解耦），只负责"把已给到的消息裁剪成一个 LLM 友好、受控长度的上下文"。
 *
 * <p>对应 HARNESS_TODO ①（执行上下文组装）：
 * - 按 token 预算裁剪（保留 system + 最近 N 轮）
 * - 过滤空/系统噪声消息
 * - 保证 role 顺序（system → user/assistant 交替）
 */
public class ContextAssemblingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextAssemblingAdvisor.class);

    private final int tokenBudget;

    public ContextAssemblingAdvisor() {
        // 默认 token 预算：约 4000（后续可由配置注入）
        this(4000);
    }

    public ContextAssemblingAdvisor(int tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    @Override
    public String getName() {
        return "context-assembling-advisor";
    }

    @Override
    public int getOrder() {
        // 早于默认内存 advisor 执行，确保组装先于记忆注入等
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Prompt assembled = assemble(request.prompt());
        ChatClientRequest rebuilt = ChatClientRequest.builder()
                .prompt(assembled)
                .context(request.context())
                .build();
        return chain.nextCall(rebuilt);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                                 StreamAdvisorChain chain) {
        Prompt assembled = assemble(request.prompt());
        ChatClientRequest rebuilt = ChatClientRequest.builder()
                .prompt(assembled)
                .context(request.context())
                .build();
        return chain.nextStream(rebuilt);
    }

    /* ---------------- 组装纯函数（便于单测） ---------------- */

    /**
     * 对 Prompt 内的消息序列做上下文组装：
     * 1. 过滤空内容 / 系统噪声（空白内容消息丢弃）；
     * 2. 归一化 role 顺序（以 system 开头，之后 user/assistant 交替压制连续同类）；
     * 3. 从最旧丢弃直至总 token 估算不超过预算（system 始终保留）。
     * <p>重建 Prompt 时保留原 options（含 model 等 ChatOptions），避免丢失模型参数。
     */
    public Prompt assemble(Prompt prompt) {
        List<Message> raw = prompt.getInstructions();
        List<Message> assembled = assemble(raw);
        return new Prompt(assembled, prompt.getOptions());
    }

    /** 组装消息列表（核心逻辑，纯函数可测）。 */
    public List<Message> assemble(List<Message> raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? new ArrayList<>() : new ArrayList<>(raw);
        }
        // 1. 过滤空消息 / 系统噪声
        List<Message> cleaned = filterNoise(raw);
        // 2. 归一化 role 顺序
        List<Message> normalized = normalizeRoles(cleaned);
        // 3. token 预算从旧丢弃（保留 system + 最近消息）
        return trimToBudget(normalized);
    }

    /** 过滤空白内容与纯系统占位消息。 */
    private List<Message> filterNoise(List<Message> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            String text = m.getText();
            if (text == null || text.isBlank()) {
                continue; // 空内容丢弃
            }
            if (m instanceof SystemMessage && text.isBlank()) {
                continue; // 系统占位噪声丢弃
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 归一化 role 顺序：以 system 开头（若有）；之后 user/assistant 交替，
     * 压制任意连续同类消息（保留最后一个，system 除外）。
     */
    private List<Message> normalizeRoles(List<Message> messages) {
        List<Message> out = new ArrayList<>();
        for (Message m : messages) {
            if (m instanceof SystemMessage) {
                // system 统一放在最前（去重多余 system）
                out.removeIf(x -> x instanceof SystemMessage);
                out.add(0, m);
                continue;
            }
            String role = roleOf(m);
            if (!out.isEmpty()) {
                String lastRole = roleOf(out.get(out.size() - 1));
                // 连续同类且非 system：替换为当前（保留最新）
                if (lastRole.equals(role) && !"system".equals(lastRole)) {
                    out.set(out.size() - 1, m);
                    continue;
                }
            }
            out.add(m);
        }
        return out;
    }

    /** 按 token 预算从最旧丢弃：保留 system 与最近的 user/assistant，直至估算 token ≤ 预算。 */
    private List<Message> trimToBudget(List<Message> messages) {
        if (messages.size() <= 1) {
            return messages;
        }
        long total = messages.stream().mapToLong(this::estimateTokens).sum();
        if (total <= tokenBudget) {
            return messages;
        }
        List<Message> out = new ArrayList<>(messages);
        // 从最旧逐步丢弃（Skip system，予以保留）
        while (out.size() > 1 && total > tokenBudget) {
            int dropIdx = -1;
            for (int i = 0; i < out.size(); i++) {
                if (!(out.get(i) instanceof SystemMessage)) {
                    dropIdx = i;
                    break;
                }
            }
            if (dropIdx < 0) {
                break; // 全为 system，无法再丢
            }
            total -= estimateTokens(out.get(dropIdx));
            out.remove(dropIdx);
        }
        log.info("[context-assembler] 裁剪后消息数={}, 估算token={}/{}", out.size(), total, tokenBudget);
        return out;
    }

    /** 近似 token 估算：中文按字符、英文按每 4 字符近似 1 token。 */
    private long estimateTokens(Message m) {
        String text = m.getText();
        if (text == null) {
            return 0;
        }
        long chinese = text.codePoints().filter(cp -> cp > 0x2E80).count();
        long other = text.length() - chinese;
        return chinese + (other + 3) / 4;
    }

    private String roleOf(Message m) {
        if (m instanceof SystemMessage) {
            return "system";
        }
        if (m instanceof AssistantMessage) {
            return "assistant";
        }
        return "user";
    }
}