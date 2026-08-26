package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.domain.dto.AgentsView;
import java.util.List;
import com.dark.javaHarness.domain.dto.GoalView;
import com.dark.javaHarness.domain.dto.GoalsView;
import com.dark.javaHarness.domain.dto.SessionPageView;
import com.dark.javaHarness.domain.dto.SubmitView;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.GoalService;
import com.dark.javaHarness.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Harness 编排管理接口（表现层，纯转发）。
 */
@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private final AgentService agentService;
    private final GoalService goalService;
    private final SessionService sessionService;

    public HarnessController(AgentService agentService, GoalService goalService, SessionService sessionService) {
        this.agentService = agentService;
        this.goalService = goalService;
        this.sessionService = sessionService;
    }

    /** 列出已注册的 Agent */
    @GetMapping("/agents")
    public AgentsView agents() {
        return new AgentsView(agentService.agentNames());
    }

    /** 分页列出全部会话 */
    @GetMapping("/sessions")
    public SessionPageView sessions(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size) {
        return SessionPageView.from(sessionService.page(page, size));
    }

    /** 提交一个目标给指定 Agent 异步执行 */
    @PostMapping("/submit")
    public SubmitView submit(@RequestParam String agent,
                             @RequestParam String objective) {
        Goal goal = agentService.submit(agent, objective);
        return new SubmitView(goal.id(), goal.status().name());
    }

    /** 查询单个目标状态 */
    @GetMapping("/goals/{id}")
    public GoalView goal(@PathVariable String id) {
        Goal goal = goalService.get(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到目标: " + id));
        return GoalView.from(goal);
    }

    /** 查询全部目标 */
    @GetMapping("/goals")
    public GoalsView goals() {
        return new GoalsView(goalService.all().stream().map(GoalView::from).toList());
    }
}