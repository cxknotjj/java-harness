package com.dark.javaHarness.channel.qq;

import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executor;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * OneBot 上报接收端点（POST /onebot/event，NapCat httpClients 推送）。
 *
 * <p>接收纪律：raw body 验签（可选）→ message_id 幂等去重 → **立即 200 ACK** →
 * onebotExecutor 异步处理。NapCat 上报有超时（约 4~10s），同步做任何慢事都会导致
 * 上报超时重发；解析/过滤/LLM 调用全部发生在 ACK 之后。除验签失败（403）外恒回 200
 * ——异常事件重发无意义，靠日志暴露。
 */
@RestController
public class OneBotEventController {

    private static final Logger log = LoggerFactory.getLogger(OneBotEventController.class);

    private final ObjectMapper objectMapper;
    private final OneBotEventService eventService;
    private final Executor onebotExecutor;
    private final NapCatProperties props;
    private final MessageDedupCache dedup = new MessageDedupCache();

    public OneBotEventController(ObjectMapper objectMapper,
                                 OneBotEventService eventService,
                                 @Qualifier("onebotExecutor") Executor onebotExecutor,
                                 NapCatProperties props) {
        this.objectMapper = objectMapper;
        this.eventService = eventService;
        this.onebotExecutor = onebotExecutor;
        this.props = props;
    }

    @PostMapping("/onebot/event")
    public ResponseEntity<String> onEvent(@RequestBody byte[] body,
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        if (!signatureValid(body, signature)) {
            log.warn("[napcat] 上报验签失败，拒绝（403）");
            return ResponseEntity.status(403).body("forbidden");
        }
        try {
            OneBotEvent event = objectMapper.readValue(body, OneBotEvent.class);
            // 非 message 事件（meta/notice/request）与上报链路无关，静默 ACK
            if (!"message".equals(event.postType())) {
                return ResponseEntity.ok("ok");
            }
            if (!dedup.tryAcquire(event.messageId() == null ? null : String.valueOf(event.messageId()))) {
                log.debug("[napcat] 重复上报丢弃 messageId={}", event.messageId());
                return ResponseEntity.ok("ok");
            }
            try {
                onebotExecutor.execute(() -> eventService.handle(event));
            } catch (Exception e) {
                // 有界队列满被拒：QQ 侧表现为该条消息无回复，记日志暴露
                log.error("[napcat] 异步池拒绝事件 messageId={}：{}", event.messageId(), e.getMessage());
            }
        } catch (Exception e) {
            log.warn("[napcat] 上报处理异常（恒 200，避免 NapCat 重发风暴）：{}", e.getMessage());
        }
        return ResponseEntity.ok("ok");
    }

    /** 上报验签：X-Signature: sha1=HMAC-SHA1(secret, rawBody)；secret 未配置时跳过 */
    private boolean signatureValid(byte[] body, String header) {
        String secret = props.getEventSecret();
        if (secret == null || secret.isBlank()) {
            return true;
        }
        if (header == null || !header.startsWith("sha1=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] digest = mac.doFinal(body == null ? new byte[0] : body);
            StringBuilder hex = new StringBuilder("sha1=");
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return MessageDigest.isEqual(
                    hex.toString().getBytes(StandardCharsets.UTF_8),
                    header.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[napcat] 验签计算异常：{}", e.getMessage());
            return false;
        }
    }
}
