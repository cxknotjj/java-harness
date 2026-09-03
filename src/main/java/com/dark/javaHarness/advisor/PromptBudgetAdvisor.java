package com.dark.javaHarness.advisor;

import com.dark.javaHarness.tool.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * 静态 prompt 预算拦截器（Spring AI Advisor，请求级挂载）。
 *
 * <p>职责：对单次调用的静态 prompt（system + 历史 + user）做 token 预算约束——
 * 超预算时改写最后一条 user 消息（其余消息保留），保证进入模型的 prompt 有明确上界。
 * 与 {@link com.dark.javaHarness.tool.ToolCallBudget} 互补：本类管「调用发起时」的静态内容
 * （聚合拼接结果 / lead 目标），工具循环内的动态追加由工具预算管。
 *
 * <p>挂载方式：请求级（{@code spec.advisors(...)}），不用 default advisor——
 * 聚合与子任务共用同一个 ChatClient（同模型路由），default 挂载会连坐到无关调用。
 *
 * <p>截断语义由构造注入的 {@link Truncator} 决定，advisor 本体不含业务结构知识：
 * - {@link TailTruncator}：保留头部，尾部截断（lead 目标等通用场景）；
 * - {@link SectionTruncator}：按节等份额头尾保留（聚合的多子任务结果，禁止先到先得）。
 *
 * <p>估算口径统一走 {@link TokenEstimator}；截断发生时 log.warn 留痕（原始/预算后 token 数）。
 */
public final class PromptBudgetAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PromptBudgetAdvisor.class);

    /** 截断标记（超预算的文本以标记结尾，模型可感知内容不完整） */
    public static final String TRUNCATED_SUFFIX = "\n…[内容已按上下文预算截断]";

    private final int maxTokens;
    private final Truncator truncator;

    /** 截断策略：把 text 裁剪到 ≤ budgetTokens（估算口径 TokenEstimator） */
    public interface Truncator {
        String truncate(String text, int budgetTokens);
    }

    public PromptBudgetAdvisor(int maxTokens, Truncator truncator) {
        this.maxTokens = maxTokens;
        this.truncator = truncator;
    }

    /** 通用尾截策略工厂（单段内容，如 lead 目标） */
    public static PromptBudgetAdvisor tail(int maxTokens) {
        return new PromptBudgetAdvisor(maxTokens, new TailTruncator());
    }

    /** 按节等份额截断策略工厂（多段内容，如聚合的子任务结果；header 形如「【子任务1】」） */
    public static PromptBudgetAdvisor sections(int maxTokens, Pattern sectionHeader) {
        return new PromptBudgetAdvisor(maxTokens, new SectionTruncator(sectionHeader));
    }

    @Override
    public String getName() {
        return "prompt-budget-advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(apply(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(apply(request));
    }

    /** 预算纯函数（便于单测）：预算内原样返回；超预算改写最后一条 user 消息后重建请求 */
    public ChatClientRequest apply(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        List<Message> messages = prompt.getInstructions();
        if (messages == null || messages.isEmpty()) {
            return request;
        }
        int lastUser = -1;
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            total += TokenEstimator.estimateTokens(messages.get(i).getText());
            if (messages.get(i) instanceof UserMessage) {
                lastUser = i; // 取最后一条 user（多轮时前面的历史算保留部分）
            }
        }
        if (lastUser < 0) {
            return request; // 无 user 消息，无从改写
        }
        if (total <= maxTokens) {
            return request; // 预算内直通
        }
        String userText = messages.get(lastUser).getText();
        // user 可用预算 = 上限 - 其余消息（system/历史）占用
        int userBudget = Math.max(0, maxTokens - (total - TokenEstimator.estimateTokens(userText)));
        String newText = truncator.truncate(userText, userBudget);
        if (newText.equals(userText)) {
            return request;
        }
        log.warn("[prompt-budget] prompt 超预算：估算 {} token > 上限 {}，user 内容已截断至 {} token",
                total, maxTokens, TokenEstimator.estimateTokens(newText));
        List<Message> rebuilt = new ArrayList<>(messages);
        rebuilt.set(lastUser, new UserMessage(newText));
        return ChatClientRequest.builder()
                .prompt(new Prompt(rebuilt, prompt.getOptions()))
                .context(request.context())
                .build();
    }

    /* ---------------- 截断策略 ---------------- */

    /** 尾截策略：保留头部至预算（扣除标记），末尾补截断标记 */
    public static final class TailTruncator implements Truncator {

        @Override
        public String truncate(String text, int budgetTokens) {
            if (text == null || TokenEstimator.estimateTokens(text) <= budgetTokens) {
                return text;
            }
            int suffix = TokenEstimator.estimateTokens(TRUNCATED_SUFFIX);
            if (budgetTokens <= suffix) {
                return "[内容已按上下文预算截断]";
            }
            return fitPrefix(text, budgetTokens - suffix) + TRUNCATED_SUFFIX;
        }
    }

    /**
     * 按节等份额截断策略：按节头正则切段，每节等额分配预算——
     * 未超份额的节原样保留，超份额的节保留头尾（各占份额一半）并加标记，
     * 保证每节至少留有等额信息量（禁止先到先得挤掉后面的节）。
     */
    public static final class SectionTruncator implements Truncator {

        private static final String SECTION_SUFFIX = "\n…[本节内容已截断]…\n";
        private final Pattern sectionHeader;

        public SectionTruncator(Pattern sectionHeader) {
            this.sectionHeader = sectionHeader;
        }

        @Override
        public String truncate(String text, int budgetTokens) {
            if (text == null || TokenEstimator.estimateTokens(text) <= budgetTokens) {
                return text;
            }
            // 定位各节起点（节头匹配处），前导文本（标题行等）单独保留
            List<Integer> starts = new ArrayList<>();
            Matcher m = sectionHeader.matcher(text);
            while (m.find()) {
                starts.add(m.start());
            }
            if (starts.isEmpty()) {
                return new TailTruncator().truncate(text, budgetTokens); // 无节结构 → 退化为尾截
            }
            String preamble = text.substring(0, starts.get(0));
            int share = Math.max(0, (budgetTokens - TokenEstimator.estimateTokens(preamble)) / starts.size());
            StringBuilder sb = new StringBuilder(preamble);
            for (int s = 0; s < starts.size(); s++) {
                int end = s + 1 < starts.size() ? starts.get(s + 1) : text.length();
                String section = text.substring(starts.get(s), end);
                sb.append(keepHeadTail(section, share));
            }
            return sb.toString();
        }

        /** 单节保留：份额内原样；超出则头尾各留一半并加标记 */
        private String keepHeadTail(String section, int share) {
            int est = TokenEstimator.estimateTokens(section);
            if (est <= share) {
                return section;
            }
            int suffix = TokenEstimator.estimateTokens(SECTION_SUFFIX);
            if (share <= suffix) {
                java.util.regex.Matcher header = sectionHeader.matcher(section);
                return header.find()
                        ? section.substring(0, header.start()).strip() + SECTION_SUFFIX
                        : SECTION_SUFFIX;
            }
            int half = (share - suffix) / 2;
            return fitPrefix(section, half) + SECTION_SUFFIX + fitSuffix(section, half);
        }
    }

    /* ---------------- 前缀/后缀适配（TokenEstimator 口径，二分定位） ---------------- */

    /** 取 text 的最长前缀使其估算 token ≤ limit（limit≤0 返回空串） */
    static String fitPrefix(String text, int limit) {
        if (text == null || text.isEmpty() || limit <= 0) {
            return "";
        }
        if (TokenEstimator.estimateTokens(text) <= limit) {
            return text;
        }
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (TokenEstimator.estimateTokens(text.substring(0, mid)) <= limit) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo);
    }

    /** 取 text 的最长后缀使其估算 token ≤ limit（limit≤0 返回空串） */
    static String fitSuffix(String text, int limit) {
        if (text == null || text.isEmpty() || limit <= 0) {
            return "";
        }
        if (TokenEstimator.estimateTokens(text) <= limit) {
            return text;
        }
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (TokenEstimator.estimateTokens(text.substring(mid)) <= limit) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return text.substring(lo);
    }
}
