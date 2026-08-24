package com.dark.javaHarness.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.mapper.AgentMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 配置读取器：负责从 agent 表读取 Agent 的运行配置与名称映射。
 * 将 AgentMapper 的数据访问从 AgentServiceImpl 拆出，职责单一，便于替换与测试。
 */
@Component
public class AgentConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigProvider.class);

    private final AgentMapper agentMapper;

    public AgentConfigProvider(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    /** 按 agentId 从 agent 表查询 agentName（CLI 传入 agentId 时用于路由映射） */
    public Optional<String> findAgentNameById(Long agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        try {
            AgentEntity row = agentMapper.selectById(agentId);
            if (row != null) {
                log.debug("[agent映射] agentId={} 命中 agent_name='{}'", agentId, row.getAgentName());
                return Optional.of(row.getAgentName());
            }
            log.warn("[agent映射] agentId={} 在 agent 表无记录", agentId);
        } catch (Exception e) {
            log.warn("按 agentId 查询 agent 表失败 agentId={}", agentId, e);
        }
        return Optional.empty();
    }

    /** 从 agent 表读取指定 Agent 的运行配置（模型 + 系统提示词） */
    public Optional<AgentConfig> getAgentConfig(String agentName) {
        try {
            AgentEntity row = agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                    .eq(AgentEntity::getAgentName, agentName)
                    .last("LIMIT 1"));
            if (row != null) {
                AgentConfig cfg = new AgentConfig(
                        blankToNull(row.getModel()),
                        blankToNull(row.getPrompt()));
                log.info("[agent配置] agentName='{}' -> model={}, prompt={}",
                        agentName, cfg.model(), cfg.prompt());
                return Optional.of(cfg);
            }
            log.warn("[agent配置] agent 表无 agentName='{}' 记录，将使用默认配置", agentName);
        } catch (Exception e) {
            log.warn("读取 agent 表配置失败 agent={}", agentName, e);
        }
        return Optional.empty();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}