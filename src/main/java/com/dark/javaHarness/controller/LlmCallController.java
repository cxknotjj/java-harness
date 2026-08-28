package com.dark.javaHarness.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM 调用观测查询接口：按会话或全量查看每次 LLM 调用的耗时 / token 消耗。
 *
 * <p>数据由 LlmCallRecorder 在各调用出口（路径 A / 路径 B 各环节 / 路由判断）异步写入。
 * GET /api/llm-calls?sessionId=xxx&amp;limit=50（sessionId 可省略查全量，按时间倒序）。
 */
@RestController
@RequestMapping("/api/llm-calls")
public class LlmCallController {

    /** 默认与最大返回条数（防全表拖取） */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final LlmCallLogMapper mapper;

    public LlmCallController(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public List<LlmCallLogEntity> list(@RequestParam(required = false) String sessionId,
                                       @RequestParam(defaultValue = "50") int limit) {
        int n = Math.min(Math.max(limit, 1), MAX_LIMIT);
        QueryWrapper<LlmCallLogEntity> qw = new QueryWrapper<>();
        if (sessionId != null && !sessionId.isBlank()) {
            qw.eq("session_id", sessionId);
        }
        qw.orderByDesc("id").last("LIMIT " + n);
        return mapper.selectList(qw);
    }
}
