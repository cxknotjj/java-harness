package com.dark.javaHarness.channel.qq.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * OneBot 11 消息段（messagePostFormat=array）：{@code {type, data}}。
 * NapCat 上报与下行发送共用；data 结构随 type 变化，统一用 Map 承载，
 * 常用取值经 {@link #text()} / {@link #qq()} / {@link #messageId()} 读取。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageSegment(
        @JsonProperty("type") String type,
        @JsonProperty("data") Map<String, Object> data) {

    /** text 段的文本内容（其他段型返回空串） */
    public String text() {
        Object t = data == null ? null : data.get("text");
        return t == null ? "" : String.valueOf(t);
    }

    /** at 段的 QQ 号（NapCat 可能给字符串或数字，统一字符串比较） */
    public String qq() {
        Object q = data == null ? null : data.get("qq");
        return q == null ? null : String.valueOf(q);
    }

    /** reply 段引用的消息 id */
    public Long messageId() {
        Object id = data == null ? null : data.get("message_id");
        if (id == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(id));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
