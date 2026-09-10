package com.dark.javaHarness.channel.qq;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 群聊防风控限频：同一用户两次回复的最小时间间隔（内存滑动窗）。
 * 间隔内再触发直接丢弃（QQ 侧表现为该条消息不回复），零外部依赖。
 * intervalMs &lt;= 0 时不限频（napcat.rate-limit.per-user-seconds=0，项目口径）。
 */
public class UserRateLimiter {

    private final long intervalMs;

    private final ConcurrentHashMap<Long, Long> lastReplyAt = new ConcurrentHashMap<>();

    public UserRateLimiter(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /** @return true=放行（记录本次时间），false=间隔内重复触发（丢弃） */
    public boolean tryAcquire(long userId) {
        if (intervalMs <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = lastReplyAt.get(userId);
        if (last != null && now - last < intervalMs) {
            return false;
        }
        lastReplyAt.put(userId, now);
        return true;
    }
}
