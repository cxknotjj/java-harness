package com.dark.javaHarness.channel.qq.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OneBot 11 上报事件（NapCat HTTP 上报，POST /onebot/event）。
 * 只绑定消息链路关心的字段，其余字段忽略（NapCat 事件结构随版本演进）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OneBotEvent(
        @JsonProperty("time") Long time,
        @JsonProperty("self_id") Long selfId,
        @JsonProperty("post_type") String postType,
        @JsonProperty("message_type") String messageType,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("group_id") Long groupId,
        @JsonProperty("message_id") Long messageId,
        @JsonProperty("raw_message") String rawMessage,
        @JsonProperty("message") List<MessageSegment> message,
        @JsonProperty("sender") Sender sender) {

    /** 发送者信息（群聊 card 为群名片） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sender(
            @JsonProperty("user_id") Long userId,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("card") String card) {
    }
}
