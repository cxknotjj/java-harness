package com.dark.javaHarness.config.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * 关闭思考流式过滤器：流式（WebClient）通道的 {@link DisableThinkingInterceptor} 对应物。
 *
 * <p>背景：dashscope 兼容模式的 qwen3 系思考型模型，流式请求默认开思考时输出全在
 * {@code reasoning_content}（Spring AI 的 {@code .content()} 只取 content，逐 token
 * 解析后一个内容 token 都拿不到）——实测聚合流式调用 7 秒收到 0 个 token，最终回答为空、
 * CLI 回显目标本身。故流式请求体同样注入 {@code "enable_thinking": false}。
 *
 * <p>WebClient 的请求体是 {@link BodyInserter}（非字节数组），改写方式：装饰
 * {@link ClientHttpRequest}，在 {@code writeWith} 处缓冲完整请求体、注入字段后下发；
 * 改写失败（非 JSON/已带该字段）原样透传，不阻断调用。
 *
 * <p>防御性设计：仅改写 POST 的 chat/completions 请求；重写后 body 变长，
 * 主动移除 Content-Length 头防止长度不匹配。
 */
public final class DisableThinkingStreamFilter implements ExchangeFilterFunction {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        if (!HttpMethod.POST.equals(request.method()) || !isChatCompletions(request.url())) {
            return next.exchange(request);
        }
        BodyInserter<?, ? super ClientHttpRequest> original = request.body();
        // 重写后的 body 更长，移除可能存在的 Content-Length 防止长度不匹配
        BodyInserter<ClientHttpRequest, ClientHttpRequest> rewritten = new BodyInserter<>() {
            @Override
            public Mono<Void> insert(ClientHttpRequest outputMessage, Context context) {
                return insertRewritten(original, outputMessage, context);
            }
        };
        ClientRequest mutated = ClientRequest.from(request)
                .headers(h -> h.remove("Content-Length"))
                .body(rewritten)
                .build();
        return next.exchange(mutated);
    }

    /** 仅对话补全端点需要该参数（embeddings 等原样透传） */
    private boolean isChatCompletions(java.net.URI url) {
        String path = url == null ? null : url.getPath();
        return path != null && path.contains("chat/completions");
    }

    /** 经装饰的请求体缓冲改写后下发；BodyInserter 原始引用用 raw 类型规避捕获编译问题 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> insertRewritten(BodyInserter original, ClientHttpRequest outputMessage,
                                       BodyInserter.Context context) {
        ClientHttpRequestDecorator decorator = new ClientHttpRequestDecorator(outputMessage) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return DataBufferUtils.join(body).flatMap(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    byte[] rewritten = injectDisableThinking(bytes);
                    DataBuffer out = bufferFactory().wrap(rewritten != null ? rewritten : bytes);
                    return super.writeWith(Mono.just(out));
                });
            }
        };
        return ((BodyInserter) original).insert(decorator, context);
    }

    /** 注入 enable_thinking:false；无需改写（已带该字段/非 JSON）返回 null 表示原样下发 */
    private byte[] injectDisableThinking(byte[] body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!(root instanceof ObjectNode obj) || obj.has("enable_thinking")) {
                return null;
            }
            obj.put("enable_thinking", false);
            return MAPPER.writeValueAsBytes(obj);
        } catch (Exception e) {
            return null; // 非 JSON 请求体：原样下发
        }
    }
}
