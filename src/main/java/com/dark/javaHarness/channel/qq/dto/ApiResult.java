package com.dark.javaHarness.channel.qq.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NapCat HTTP API 统一响应：{@code {status, retcode, data}}。
 * retcode=0 且 status=ok 视为成功；data 结构随 action 变化，用原始节点承载。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiResult(
        @JsonProperty("status") String status,
        @JsonProperty("retcode") int retcode,
        @JsonProperty("data") Object data) {

    /** 网络层失败/未拿到响应体的兜底结果（retcode=-1） */
    public static ApiResult failed() {
        return new ApiResult("failed", -1, null);
    }
}
