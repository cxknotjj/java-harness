package com.dark.javaHarness.channel.qq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * QQ 渠道（NapCat / OneBot 11）配置（napcat.*）。
 *
 * <p>数值唯一来源是 application.yaml（napcat 块）——本类只做绑定载体，零默认值；
 * token/secret 走 {@code ${ENV_VAR:}} 环境变量不落 git。{@code enabled=false} 时
 * NapCatChannelConfig 整链不装配（无端点、无线程池、无出站客户端）。
 */
@Component
@ConfigurationProperties(prefix = "napcat")
public class NapCatProperties {

    /** 渠道总开关（false 时 channel/qq 全链不装配，/onebot/event 端点不存在） */
    private boolean enabled;

    /** NapCat HTTP API 地址（onebot11 httpServers 的 host:port） */
    private String apiBaseUrl;

    /** 下行 API 鉴权 token（与 onebot11 httpServers.token 一致；空则不带鉴权头） */
    private String apiToken;

    /** 机器人自身 QQ 号（自消息过滤 + 群聊 @ 触发判据） */
    private String selfId;

    /** 回复使用的 Agent（agent 表主键）；空 = 默认 Agent。该 agent 的 knowledge 绑定自动生效 */
    private Long agentId;

    /** 上报验签密钥（与 onebot11 httpClients.token 一致；空 = 不校验，联调期先留空） */
    private String eventSecret;

    /** 单条消息聊天超时秒数：超时放弃回复且静默（后台跑完仅落会话记忆），防 LLM 卡死占满处理线程；0 = 不限制 */
    private int chatTimeoutSeconds;

    /** 私聊白名单（QQ 号 CSV）：非空时仅名单内用户可私聊（防陌生人刷 LLM token）；空 = 不限制 */
    private String privateAllowUsers;

    private final GroupTrigger groupTrigger = new GroupTrigger();
    private final RateLimit rateLimit = new RateLimit();
    private final Reply reply = new Reply();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getSelfId() {
        return selfId;
    }

    public void setSelfId(String selfId) {
        this.selfId = selfId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getEventSecret() {
        return eventSecret;
    }

    public void setEventSecret(String eventSecret) {
        this.eventSecret = eventSecret;
    }

    public int getChatTimeoutSeconds() {
        return chatTimeoutSeconds;
    }

    public void setChatTimeoutSeconds(int chatTimeoutSeconds) {
        this.chatTimeoutSeconds = chatTimeoutSeconds;
    }

    public String getPrivateAllowUsers() {
        return privateAllowUsers;
    }

    public void setPrivateAllowUsers(String privateAllowUsers) {
        this.privateAllowUsers = privateAllowUsers;
    }

    public GroupTrigger getGroupTrigger() {
        return groupTrigger;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Reply getReply() {
        return reply;
    }

    /** 群聊触发方式：at=仅@机器人 | prefix=命令前缀 | all=全部响应 */
    public static class GroupTrigger {

        private String mode;

        /** prefix 模式的命令前缀 */
        private String prefix;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }
    }

    /** 群聊防风控限频：同一用户回复最小间隔秒数；0 = 不限制（项目口径） */
    public static class RateLimit {

        private int perUserSeconds;

        public int getPerUserSeconds() {
            return perUserSeconds;
        }

        public void setPerUserSeconds(int perUserSeconds) {
            this.perUserSeconds = perUserSeconds;
        }
    }

    /** 回复输出控制：超长按段落边界分段发送（兼作单条输出上限） */
    public static class Reply {

        private int maxLength;

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }
    }
}
