package com.dark.javaHarness.web;

import com.dark.javaHarness.core.agent.AgentService;
import com.dark.javaHarness.core.goal.Goal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private final AgentService agentService;

    public HarnessController(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 列出已注册的 Agent */
    @GetMapping("/agents")
    public Map<String, Object> agents() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("agents", agentService.agentNames());
        return map;
    }

    /** 提交一个目标给指定 Agent 异步执行 */
    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestParam String agent,
                                      @RequestParam String objective) {
        Goal goal = agentService.submit(agent, objective);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("goalId", goal.id());
        map.put("status", goal.status());
        return map;
    }

    /** 查询单个目标状态 */
    @GetMapping("/goals/{id}")
    public Map<String, Object> goal(@PathVariable String id) {
        Goal goal = agentService.getGoal(id)
                .orElseThrow(() -> new IllegalArgumentException("未找到目标: " + id));
        return toView(goal);
    }

    /** 查询全部目标 */
    @GetMapping("/goals")
    public Map<String, Object> goals() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("goals", agentService.allGoals().stream().map(this::toView).toList());
        return map;
    }

    private Map<String, Object> toView(Goal g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("objective", g.objective());
        m.put("status", g.status());
        m.put("summary", g.summary());
        return m;
    }
}