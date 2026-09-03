package com.dark.javaHarness.config.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * DisableThinkingInterceptor 单测：
 * - chat/completions 请求体注入 enable_thinking:false
 * - 已带 enable_thinking 时不覆盖（显式声明优先）
 * - 非 chat/completions 端点（embeddings 等）与非法 JSON 原样透传
 */
class DisableThinkingInterceptorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DisableThinkingInterceptor interceptor = new DisableThinkingInterceptor();

    /** 模拟执行链：捕获最终下发的请求体 */
    private byte[] run(String url, byte[] body) throws IOException {
        AtomicReference<byte[]> sent = new AtomicReference<>();
        ClientHttpRequestExecution execution = (req, b) -> {
            sent.set(b);
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create(url));
        interceptor.intercept(request, body, execution);
        return sent.get();
    }

    /** chat/completions 且未声明该字段 → 注入 enable_thinking:false */
    @Test
    void injectsFalseIntoChatCompletionsBody() throws IOException {
        byte[] out = run("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "{\"model\":\"qwen3.7-flash\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8));

        JsonNode root = MAPPER.readTree(out);
        assertTrue(root.has("enable_thinking"), "应注入 enable_thinking 字段");
        assertFalse(root.get("enable_thinking").asBoolean(), "值应为 false");
        assertEquals("qwen3.7-flash", root.get("model").asText(), "其余字段应保留");
    }

    /** 请求体已带 enable_thinking → 不覆盖 */
    @Test
    void preservesExistingFlag() throws IOException {
        byte[] out = run("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "{\"model\":\"m\",\"enable_thinking\":true}".getBytes(StandardCharsets.UTF_8));

        assertTrue(MAPPER.readTree(out).get("enable_thinking").asBoolean(), "显式声明应优先，不被改写");
    }

    /** 非 chat/completions 端点（embeddings）→ 原样透传 */
    @Test
    void ignoresNonChatCompletionsPath() throws IOException {
        byte[] body = "{\"input\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        byte[] out = run("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings", body);

        assertFalse(MAPPER.readTree(out).has("enable_thinking"), "embeddings 不应被注入");
    }

    /** 非法 JSON 请求体 → 原样透传（改写失败不阻断调用） */
    @Test
    void passesThroughMalformedJsonBody() throws IOException {
        byte[] body = "not-a-json".getBytes(StandardCharsets.UTF_8);
        byte[] out = run("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", body);

        assertEquals("not-a-json", new String(out, StandardCharsets.UTF_8), "非法 JSON 应原样下发");
    }
}
