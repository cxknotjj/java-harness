package com.dark.javaHarness.channel.qq;

import com.dark.javaHarness.channel.qq.dto.ApiResult;
import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.channel.qq.dto.SendMsgRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * NapCat HTTP API 客户端（OkHttp）：get_login_info / send_private_msg / send_group_msg。
 *
 * <p>失败语义：IOException 或 HTTP 5xx 重试 1 次；4xx 与业务失败（retcode != 0）不重试；
 * 一切失败降级为 {@link ApiResult#failed()} 或静默（void 方法只记日志）——消息送达是
 * 尽力而为，不打断主流程。
 *
 * <p>启动自检（ApplicationRunner）：{@code get_login_info} 探活，失败仅告警不阻断启动
 * （NapCat 未就绪/网络不通时应用照常起，QQ 链路在首次使用时再暴露问题）。
 */
public class NapCatApiClientImpl implements NapCatApiClient, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NapCatApiClientImpl.class);

    private static final MediaType JSON = MediaType.parse("application/json");

    private final NapCatProperties props;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    public NapCatApiClientImpl(NapCatProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, defaultClient());
    }

    /** 测试可注入预配置 client（如 interceptor 桩） */
    public NapCatApiClientImpl(NapCatProperties props, ObjectMapper objectMapper, OkHttpClient http) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = http;
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public ApiResult getLoginInfo() {
        return call("get_login_info", null);
    }

    @Override
    public void sendPrivateMsg(long userId, List<MessageSegment> segments) {
        call("send_private_msg", new SendMsgRequest(userId, null, segments));
    }

    @Override
    public void sendGroupMsg(long groupId, List<MessageSegment> segments) {
        call("send_group_msg", new SendMsgRequest(null, groupId, segments));
    }

    /** 单次调用（含 1 次重试）：IO/5xx 重试，4xx/业务失败不重试，永不抛异常 */
    private ApiResult call(String action, Object payload) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Request.Builder builder = new Request.Builder()
                        .url(trimTail(props.getApiBaseUrl()) + "/" + action)
                        .header("Authorization", "Bearer " + nullToEmpty(props.getApiToken()));
                if (payload != null) {
                    builder.post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON));
                }
                try (Response resp = http.newCall(builder.build()).execute()) {
                    String body = resp.body() == null ? "" : resp.body().string();
                    if (resp.code() >= 500) {
                        log.warn("[napcat] {} HTTP {}（第 {} 次尝试）", action, resp.code(), attempt);
                        continue;
                    }
                    if (!resp.isSuccessful()) {
                        log.warn("[napcat] {} HTTP {}：{}", action, resp.code(), body);
                        return ApiResult.failed();
                    }
                    ApiResult result = objectMapper.readValue(body, ApiResult.class);
                    if (result.retcode() != 0) {
                        log.warn("[napcat] {} 业务失败：{}", action, body);
                    }
                    return result;
                }
            } catch (IOException e) {
                log.warn("[napcat] {} 调用异常（第 {} 次）：{}", action, attempt, e.getMessage());
            } catch (Exception e) {
                log.warn("[napcat] {} 处理异常：{}", action, e.getMessage());
                return ApiResult.failed();
            }
        }
        return ApiResult.failed();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            ApiResult result = getLoginInfo();
            if (result.retcode() == 0) {
                log.info("[napcat] 启动自检通过：NapCat 可达（{}）", props.getApiBaseUrl());
            } else {
                log.warn("[napcat] 启动自检失败：retcode={}（QQ 发送将不可用，检查 NapCat/token；"
                        + "不接入 QQ 渠道时可在 application.yaml 设 napcat.enabled=false 关闭本告警）",
                        result.retcode());
            }
        } catch (Exception e) {
            log.warn("[napcat] 启动自检异常：{}（不阻断启动）", e.getMessage());
        }
    }

    private static String trimTail(String url) {
        String s = nullToEmpty(url).trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
