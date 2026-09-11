package com.dark.javaHarness.advisor;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * LLM 请求发起观测（Spring AI Advisor，请求级挂载）。
 *
 * <p>动机：llm_call_log 只在调用结束时落行，卡在途中的请求此前无任何痕迹可查（一次
 * 流式聚合跑 10 分钟，日志里完全静默）。本 advisor 在每次真实模型请求（含重试尝试）
 * 发起瞬间记一条：专家类型、模型名、上下文条数与字符量、输出封顶——排查「某次调用
 * 为何耗时数分钟」的第一手证据。
 *
 * <p>advisor 位置的价值：请求在 advisor 链中已物化为消息列表（system + 注入历史 + 本轮），
 * 能拿到真实上下文条数与总量，比调用方侧记 user 文本长度更完整；order 取
 * {@link Ordered#LOWEST_PRECEDENCE}（链最内层），排在 ContextAssemblingAdvisor /
 * PromptBudgetAdvisor 之后，记录的是预算裁剪后真正发出的 prompt。
 *
 * <p>覆盖范围：经 {@code AgentRequestSpecFactory.build} 组装的全部 agent 调用（路径 A
 * general 与编排 lead/子任务/聚合共用组装链）。工具循环的多轮 roundtrip 在 ChatModel
 * 内部进行、不重入 advisor 链，与 llm_call_log 一样按尝试粒度记一条。
 */
public final class LlmRequestLogAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LlmRequestLogAdvisor.class);

    /** 调用方角色（专家类型）：lead/researcher/aggregator/general/route-judge 等 */
    private final String agent;
    /** 关联会话（可空：无会话场景如 route-judge） */
    private final String sessionId;

    public LlmRequestLogAdvisor(String agent, String sessionId) {
        this.agent = agent;
        this.sessionId = sessionId;
    }

    @Override
    public String getName() {
        return "llm-request-log-advisor";
    }

    @Override
    public int getOrder() {
        // 紧跟 ContextAssembling（HIGHEST+1）/ PromptBudget（HIGHEST+2）之后：
        // 记录预算裁剪后真正发往模型的最终消息列表。
        // 注意不能用 Ordered.LOWEST_PRECEDENCE（Integer.MAX_VALUE）——与 Spring AI
        // 模型桥接 advisor ChatModelStreamAdvisor/ChatModelCallAdvisor 的 order 相同，
        // 平局时链装配会把模型 advisor 排在前面直接调模型，本 advisor 永不执行
        // （端到端测试 LlmRequestLogAdvisorChainTest 复现过该问题）
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        logRequest(request, "CALL");
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        logRequest(request, "STREAM");
        return chain.nextStream(request);
    }

    /** 发起日志纯函数（便于单测）：返回组装好的日志行内容；无日志副作用便于断言 */
    String format(ChatClientRequest request, String kind) {
        Prompt prompt = request.prompt();
        List<Message> messages = prompt.getInstructions();
        int chars = 0;
        if (messages != null) {
            for (Message m : messages) {
                String text = m.getText();
                if (text != null) {
                    chars += text.length();
                }
            }
        }
        // getOptions() 声明类型即 ChatOptions（instanceof 同类型非法）；maxTokens 仅 OpenAI 系选项携带
        ChatOptions opts = prompt.getOptions();
        String model = opts == null ? null : opts.getModel();
        Integer maxTokens = opts instanceof OpenAiChatOptions openai ? openai.getMaxTokens() : null;
        return "[llm-call] 发起 agent=" + agent + " model=" + model + " sessionId=" + sessionId
                + " kind=" + kind + " msgs=" + (messages == null ? 0 : messages.size())
                + " ctxChars=" + chars + " maxTokens=" + maxTokens;
    }

    private void logRequest(ChatClientRequest request, String kind) {
        log.info(format(request, kind));
    }
}
