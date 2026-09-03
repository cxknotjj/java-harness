package com.dark.javaHarness.config.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * DisableThinkingStreamFilter 单测（流式/WebClient 通道）：
 * - POST chat/completions 请求体注入 enable_thinking:false
 * - 已带 enable_thinking 时不覆盖（显式声明优先）
 * - 非 chat/completions 端点（embeddings 等）整请求原样透传
 */
class DisableThinkingStreamFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHAT_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private final DisableThinkingStreamFilter filter = new DisableThinkingStreamFilter();

    /** 捕获写入请求体的假 ClientHttpRequest（只实现写路径与元信息） */
    private static final class CapturingRequest implements ClientHttpRequest {
        private final DataBufferFactory buffers = new DefaultDataBufferFactory();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override
        public DataBufferFactory bufferFactory() {
            return buffers;
        }

        @Override
        public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body).doOnNext(b -> {
                byte[] bytes = new byte[b.readableByteCount()];
                b.read(bytes);
                DataBufferUtils.release(b);
                out.writeBytes(bytes);
            }).then();
        }

        @Override
        public Mono<Void> writeAndFlushWith(
                org.reactivestreams.Publisher<? extends org.reactivestreams.Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMap(b -> b));
        }

        @Override
        public Mono<Void> setComplete() {
            return Mono.empty();
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public void beforeCommit(java.util.function.Supplier<? extends Mono<Void>> commitAction) {
            // 测试桩：无需提交前动作
        }

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.POST;
        }

        @Override
        public URI getURI() {
            return URI.create(CHAT_URL);
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public MultiValueMap<String, HttpCookie> getCookies() {
            return new LinkedMultiValueMap<>();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getNativeRequest() {
            return (T) this;
        }

        byte[] written() {
            return out.toByteArray();
        }
    }

    /**
     * 执行过滤器并触发改写后请求的 body 写入，返回最终下发的请求体；
     * 请求被原样透传（非目标端点）时返回 null。
     */
    private byte[] run(String url, Object bodyValue) {
        ClientRequest original = ClientRequest.create(HttpMethod.POST, URI.create(url))
                .body(BodyInserters.fromValue(bodyValue))
                .build();
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ExchangeFunction next = req -> {
            captured.set(req);
            return Mono.just(Mockito.mock(ClientResponse.class));
        };
        filter.filter(original, next).block();
        if (captured.get() == original) {
            return null;
        }
        @SuppressWarnings("rawtypes")
        BodyInserter inserter = captured.get().body();
        BodyInserter.Context context = Mockito.mock(BodyInserter.Context.class);
        List<HttpMessageWriter<?>> writers =
                List.of(new EncoderHttpMessageWriter<>(new Jackson2JsonEncoder()));
        when(context.messageWriters()).thenReturn(writers);
        when(context.hints()).thenReturn(Map.of());
        CapturingRequest sink = new CapturingRequest();
        inserter.insert(sink, context).block();
        return sink.written();
    }

    /** chat/completions 且未声明该字段 → 注入 enable_thinking:false，其余字段保留 */
    @Test
    void injectsFalseIntoStreamingBody() throws java.io.IOException {
        byte[] out = run(CHAT_URL, Map.of("model", "qwen3.7-flash", "messages", List.of()));

        JsonNode root = MAPPER.readTree(out);
        assertTrue(root.has("enable_thinking"), "流式请求体应注入 enable_thinking 字段");
        assertFalse(root.get("enable_thinking").asBoolean(), "值应为 false");
        assertEquals("qwen3.7-flash", root.get("model").asText(), "其余字段应保留");
    }

    /** 请求体已带 enable_thinking → 不覆盖 */
    @Test
    void preservesExistingFlag() throws java.io.IOException {
        byte[] out = run(CHAT_URL, Map.of("model", "m", "enable_thinking", true));

        assertTrue(MAPPER.readTree(out).get("enable_thinking").asBoolean(), "显式声明应优先，不被改写");
    }

    /** 非 chat/completions 端点（embeddings）→ 整请求原样透传 */
    @Test
    void ignoresNonChatCompletionsPath() {
        byte[] out = run("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings",
                Map.of("input", "hello"));

        assertNull(out, "embeddings 请求不应被改写");
    }
}
