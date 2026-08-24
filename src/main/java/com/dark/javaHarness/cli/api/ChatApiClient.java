package com.dark.javaHarness.cli.api;

import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 聊天 REST API 客户端：封装对主服务 /api/chat 的 HTTP 调用（OkHttp）。
 * 交互循环、打印等展示逻辑由调用方（ChatCli）负责。
 */
public class ChatApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public ChatApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .callTimeout(Duration.ofMinutes(3))
                .build();
    }

    /**
     * 调用 /api/chat 同步聊天。
     *
     * @return 服务端返回的响应 DTO
     * @throws IOException 网络错误，或非 2xx 响应（消息含 HTTP 状态码与响应体）
     */
    public ChatResponse chat(String message, String sessionId) throws IOException {
        String json = mapper.writeValueAsString(new ChatRequest(message, sessionId, null));
        Request request = new Request.Builder()
                .url(baseUrl + "/api/chat")
                .post(RequestBody.create(json, JSON))
                .build();
        try (Response resp = http.newCall(request).execute()) {
            String body = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + body);
            }
            return mapper.readValue(body, ChatResponse.class);
        }
    }

    /**
     * 调用 /api/chat/stream 流式聊天（SSE）：逐 token 回调 onToken，
     * 流结束后解析末尾 meta 事件返回响应 DTO。
     * agentId 可空：为空时服务端走默认 Agent。
     *
     * @throws IOException 网络错误、非 2xx 响应，或服务端 error 事件
     */
    public ChatResponse chatStream(String message, String sessionId, Long agentId, Consumer<String> onToken) throws IOException {
        String json = mapper.writeValueAsString(new ChatRequest(message, sessionId, agentId));
        Request request = new Request.Builder()
                .url(baseUrl + "/api/chat/stream")
                .post(RequestBody.create(json, JSON))
                .build();
        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + resp.body().string());
            }
            ChatResponse meta = null;
            String event = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        String data = line.substring("data:".length()).trim();
                        if ("meta".equals(event)) {
                            meta = mapper.readValue(data, ChatResponse.class);
                        } else if ("error".equals(event)) {
                            throw new IOException(data);
                        } else if (!"[DONE]".equals(data)) {
                            // [DONE] 后还会跟 meta 事件，忽略但不中断，继续读到 meta
                            onToken.accept(data);
                        }
                    }
                }
            }
            if (meta == null) {
                throw new IOException("流结束但未收到 meta 事件");
            }
            return meta;
        }
    }
}
