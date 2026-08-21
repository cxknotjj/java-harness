package com.dark.javaHarness.core.goal;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 内存版目标仓库：负责创建、查询和更新 Goal 的生命周期状态。
 * 后续可替换为数据库实现。
 */
@Component
public class GoalManager {

    private final ConcurrentMap<String, Goal> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    public Goal create(String objective) {
        return create(objective, null);
    }

    public Goal create(String objective, String sessionId) {
        String id = "goal-" + seq.incrementAndGet();
        Goal goal = new Goal(id, objective, sessionId);
        store.put(id, goal);
        return goal;
    }

    public Optional<Goal> get(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public java.util.List<Goal> all() {
        return java.util.List.copyOf(store.values());
    }
}