package com.dark.javaHarness.cli.api;

import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.dto.SessionPageView;
import com.dark.javaHarness.enums.SseProtocol;
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
                // 多 Agent 编排 = lead + N 子任务 + 聚合共 4+ 次 LLM 调用，全程可达分钟级：
                // readTimeout 约束"相邻两行数据间隔"，callTimeout 约束整个调用预算。
                // （曾回退为 1min 导致长任务中途被掐断、CLI 收到残缺结果）
                .readTimeout(Duration.ofMinutes(15))
                .callTimeout(Duration.ofMinutes(30))
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
     * 多 Agent 编排的阶段反馈（event:progress）回调 onProgress，
     * 流结束后解析末尾 meta 事件返回响应 DTO。
     * agentId 可空：为空时服务端走默认 Agent。
     *
     * @throws IOException 网络错误、非 2xx 响应，或服务端 error 事件
     */
    public ChatResponse chatStream(String message, String sessionId, Long agentId,
                                   Consumer<String> onToken, Consumer<String> onProgress) throws IOException {
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
                        if (SseProtocol.EVENT_META.equals(event)) {
                            meta = mapper.readValue(data, ChatResponse.class);
                        } else if (SseProtocol.EVENT_ERROR.equals(event)) {
                            throw new IOException(data);
                        } else if (SseProtocol.EVENT_PROGRESS.equals(event)) {
                            if (onProgress != null) {
                                onProgress.accept(data);
                            }
                        } else if (!SseProtocol.DONE_MARKER.equals(data)) {
                            // [DONE] 后还会跟 meta 事件，忽略但不中断，继续读到 meta
                            onToken.accept(SseProtocol.unescapeLineBreaks(data));
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

    /**
     * 调用 /api/harness/sessions 获取会话列表，返回第一个会话的 ID。
     * 无任何会话时返回 null（调用方可决定新建会话）。
     *
     * @throws IOException 网络错误，或非 2xx 响应（消息含 HTTP 状态码与响应体）
     */
    public String firstSessionId() throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/api/harness/sessions")
                .get()
                .build();
        try (Response resp = http.newCall(request).execute()) {
            String body = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + body);
            }
            SessionPageView view = mapper.readValue(body, SessionPageView.class);
            if (view.sessions() == null || view.sessions().isEmpty()) {
                return null;
            }
            return view.sessions().get(0).id();
        }
    }

    /**
     * 调用 POST /api/harness/sessions 新建空会话。
     *
     * @return 新会话的 sessionId
     * @throws IOException 网络错误，或非 2xx 响应（消息含 HTTP 状态码与响应体）
     */
    public String createSession() throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/api/harness/sessions")
                .post(RequestBody.create(new byte[0], null))
                .build();
        try (Response resp = http.newCall(request).execute()) {
            String body = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + body);
            }
            return mapper.readTree(body).path("sessionId").asText();
        }
    }
}
