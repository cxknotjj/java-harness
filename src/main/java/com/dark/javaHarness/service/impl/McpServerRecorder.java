package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.domain.McpServerLog;
import com.dark.javaHarness.domain.entity.McpServerLogEntity;
import com.dark.javaHarness.mapper.McpServerLogMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP server 连接观测记录器：把每个 server 的连接/发现事件异步写入 mcp_server_log 表。
 *
 * <p>设计约束与 {@link LlmCallRecorder} 同口径：<b>观测永不影响主链路</b>——落库走
 * boundedElastic 边界异步执行，任何异常只记 warn；调用方（McpToolProvider）失败隔离
 * 语义不变，本类仅补结构化可查询记录。
 */
@Service
public class McpServerRecorder {

    private static final Logger log = LoggerFactory.getLogger(McpServerRecorder.class);

    private final McpServerLogMapper mapper;

    public McpServerRecorder(McpServerLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 异步落库一条事件记录；立即返回，内部异常仅 warn */
    public void record(McpServerLog c) {
        Mono.fromRunnable(() -> doInsert(c))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> { },
                        e -> log.warn("[mcp-server] 观测记录落库失败（不影响主链路）：{}", e.getMessage()));
    }

    private void doInsert(McpServerLog c) {
        McpServerLogEntity e = new McpServerLogEntity();
        e.setServerName(c.serverName());
        e.setTransport(c.transport());
        e.setEvent(c.event());
        e.setToolCount(c.toolCount());
        e.setErrorMsg(LlmCallRecorder.truncate(c.errorMsg()));
        e.setCreatedAt(LocalDateTime.now());
        mapper.insert(e);
    }
}
