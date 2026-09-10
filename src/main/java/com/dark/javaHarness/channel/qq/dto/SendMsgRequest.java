package com.dark.javaHarness.channel.qq.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * NapCat /send_private_msg 与 /send_group_msg 请求体：
 * 私聊只填 userId，群聊只填 groupId（{@code @JsonInclude(NON_NULL)} 省略空侧）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SendMsgRequest(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("group_id") Long groupId,
        @JsonProperty("message") List<MessageSegment> message) {
}
