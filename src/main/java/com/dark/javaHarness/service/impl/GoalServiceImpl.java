package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.GoalService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * 目标服务实现：内存版目标仓库，负责创建、查询和更新 Goal 的生命周期状态。
 * 后续可替换为数据库实现。
 */
@Service
public class GoalServiceImpl implements GoalService {

    private final ConcurrentMap<String, Goal> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    @Override
    public Goal create(String objective) {
        return create(objective, null);
    }

    @Override
    public Goal create(String objective, String sessionId) {
        String id = "goal-" + seq.incrementAndGet();
        Goal goal = new Goal(id, objective, sessionId);
        store.put(id, goal);
        return goal;
    }

    @Override
    public Optional<Goal> get(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Goal> all() {
        return List.copyOf(store.values());
    }
}