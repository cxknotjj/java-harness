package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.entity.GoalEntity;
import com.dark.javaHarness.enums.GoalStatus;
import com.dark.javaHarness.mapper.GoalMapper;
import com.dark.javaHarness.service.GoalService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 目标服务实现：基于 goal 表持久化 Goal，负责创建、查询和更新生命周期状态。
 */
@Service
public class GoalServiceImpl implements GoalService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GoalServiceImpl.class);

    private final GoalMapper goalMapper;

    public GoalServiceImpl(GoalMapper goalMapper) {
        this.goalMapper = goalMapper;
    }

    /** 创建目标（无会话记忆） */
    @Override
    public Goal create(String objective) {
        return create(objective, null);
    }

    /** 创建目标（带会话记忆） */
    @Override
    public Goal create(String objective, String sessionId) {
        // UUID 保证重启/并发下主键唯一，避免内存序号与库中已有数据冲突
        Goal goal = new Goal("goal-" + UUID.randomUUID(), objective, sessionId);
        goalMapper.insert(toEntity(goal));
        return goal;
    }

    /** 按 id 查询目标 */
    @Override
    public Optional<Goal> get(String id) {
        return Optional.ofNullable(toGoal(goalMapper.selectById(id)));
    }

    /** 查询全部目标 */
    @Override
    public List<Goal> all() {
        return goalMapper.selectList(null).stream().map(this::toGoal).toList();
    }

    /** 将 Goal 当前状态持久化 */
    @Override
    public void update(Goal goal) {
        goalMapper.updateById(toEntity(goal));
    }

    /** 启动时清理僵尸 RUNNING 目标：服务非正常退出（强杀）时 cancel/error 回调不执行，状态残留会拦住 /resume */
    @jakarta.annotation.PostConstruct
    void cleanStaleRunningGoalsOnStartup() {
        int cleaned = failAllRunning("服务重启，执行中断");
        if (cleaned > 0) {
            log.warn("[goal] 启动清理僵尸 RUNNING 目标 {} 个（上次服务非正常退出）", cleaned);
        }
    }

    /** 将全部 RUNNING 目标批量标记为 FAILED（单实例部署下启动时执行，安全） */
    @Override
    public int failAllRunning(String reason) {
        return goalMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<GoalEntity>lambdaUpdate()
                        .eq(GoalEntity::getStatus, GoalStatus.RUNNING.name())
                        .set(GoalEntity::getStatus, GoalStatus.FAILED.name())
                        .set(GoalEntity::getSummary, reason)
                        .set(GoalEntity::getFinishedAt, java.time.LocalDateTime.now()));
    }

    private GoalEntity toEntity(Goal g) {
        GoalEntity e = new GoalEntity();
        e.setId(g.id());
        e.setObjective(g.objective());
        e.setSessionId(g.sessionId());
        e.setStatus(g.status().name());
        e.setSummary(g.summary());
        e.setCreatedAt(g.createdAt());
        e.setFinishedAt(g.finishedAt());
        return e;
    }

    private Goal toGoal(GoalEntity e) {
        if (e == null) {
            return null;
        }
        return new Goal(e.getId(), e.getObjective(), e.getSessionId(),
                GoalStatus.valueOf(e.getStatus()), e.getCreatedAt(), e.getFinishedAt(), e.getSummary());
    }
}
