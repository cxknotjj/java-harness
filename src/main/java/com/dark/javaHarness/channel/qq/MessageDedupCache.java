package com.dark.javaHarness.channel.qq;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * message_id 幂等去重缓存（内存过期 Set）。
 *
 * <p>NapCat HTTP 上报失败会重发同一条事件，不去重会重复回复；去重必须发生在
 * 200 ACK 之前（重发的第二次 ACK 就该被丢弃）。容量超阈值时按 TTL 清扫一次，
 * 无后台线程、零外部依赖。
 */
public class MessageDedupCache {

    private static final long TTL_MS = Duration.ofMinutes(5).toMillis();

    /** 容量软上限：达到即触发一次过期清扫（QQ 个人频道远达不到） */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    /**
     * @param key 幂等键（message_id 字符串；null 表示事件无 id，不去重直接放行）
     * @return true=首次出现（放行处理），false=TTL 内重复（丢弃）
     */
    public boolean tryAcquire(String key) {
        if (key == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (seen.size() >= SWEEP_THRESHOLD) {
            seen.entrySet().removeIf(e -> now - e.getValue() > TTL_MS);
        }
        Long prev = seen.putIfAbsent(key, now);
        if (prev == null) {
            return true;
        }
        if (now - prev > TTL_MS) {
            seen.put(key, now);
            return true;
        }
        return false;
    }
}
