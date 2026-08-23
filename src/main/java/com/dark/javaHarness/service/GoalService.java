package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.Goal;
import java.util.List;
import java.util.Optional;

/**
 * 目标服务：负责创建、查询和更新 Goal 的生命周期状态。
 */
public interface GoalService {

    Goal create(String objective);

    Goal create(String objective, String sessionId);

    Optional<Goal> get(String id);

    List<Goal> all();
}