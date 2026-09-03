package com.dark.javaHarness.config.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

/**
 * 关闭思考拦截器：对 chat/completions 请求体注入 {@code "enable_thinking": false}。
 *
 * <p>背景：dashscope 兼容模式的 qwen3 系思考型模型在非流式调用下单轮推理可达数分钟、
 * content 常为空、长思考响应还会被截断；该参数请求级关闭思考后恢复秒级正常响应。
 * Spring AI 的 OpenAiChatOptions 无法透传该私有参数，故在 HTTP 层改写请求体。
 *
 * <p>防御性设计：
 * - 仅改写 chat/completions 路径（embeddings 等端点不接受该参数，原样透传）；
 * - 请求体已带 enable_thinking 时不覆盖（模型方参数显式声明优先）；
 * - 请求体非合法 JSON 时原样透传（不因改写失败阻断调用）。
 *
 * <p>仅挂在阻塞调用通道（RestClient）；流式通道（WebClient）的对应注入见
 * {@link DisableThinkingStreamFilter}（流式不关思考的话，思考模型输出全在
 * reasoning_content，content 一个 token 都收不到）。
 */
public final class DisableThinkingInterceptor implements ClientHttpRequestInterceptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public @NonNull ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
                                                 @NonNull ClientHttpRequestExecution execution) throws IOException {
        if (isChatCompletions(request) && body.length > 0) {
            byte[] rewritten = injectDisableThinking(body);
            if (rewritten != null) {
                body = rewritten;
            }
        }
        return execution.execute(request, body);
    }

    /** 仅对话补全端点需要该参数 */
    private boolean isChatCompletions(HttpRequest request) {
        String path = request.getURI().getPath();
        return path != null && path.contains("chat/completions");
    }

    /** 注入 enable_thinking:false；无需改写（已带该字段/非 JSON）返回 null 表示原样透传 */
    private byte[] injectDisableThinking(byte[] body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!(root instanceof ObjectNode obj) || obj.has("enable_thinking")) {
                return null;
            }
            obj.put("enable_thinking", false);
            return MAPPER.writeValueAsBytes(obj);
        } catch (Exception e) {
            return null; // 非 JSON 请求体：原样透传
        }
    }
}
