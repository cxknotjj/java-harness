package com.dark.javaHarness.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.AgentMapper;
import com.dark.javaHarness.mapper.ModelProviderMapper;
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
    private final ModelProviderMapper modelProviderMapper;

    public AgentConfigProvider(AgentMapper agentMapper, ModelProviderMapper modelProviderMapper) {
        this.agentMapper = agentMapper;
        this.modelProviderMapper = modelProviderMapper;
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

    /** 从 agent 表读取指定 Agent 的运行配置（部署模型 + 系统提示词；模型名经 model_provider 表解析） */
    public Optional<AgentConfig> getAgentConfig(String agentName) {
        try {
            AgentEntity row = agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                    .eq(AgentEntity::getAgentName, agentName)
                    .last("LIMIT 1"));
            if (row != null) {
                String modelName = resolveModelName(row.getModelProviderId());
                AgentConfig cfg = new AgentConfig(
                        row.getModelProviderId(),
                        modelName,
                        blankToNull(row.getPrompt()));
                log.info("[agent配置] agentName='{}' -> modelProviderId={}, model={}, prompt={}",
                        agentName, cfg.modelProviderId(), cfg.model(), cfg.prompt());
                return Optional.of(cfg);
            }
            log.warn("[agent配置] agent 表无 agentName='{}' 记录，将使用默认配置", agentName);
        } catch (Exception e) {
            log.warn("读取 agent 表配置失败 agent={}", agentName, e);
        }
        return Optional.empty();
    }

    /** 按 model_provider.id 解析模型名（请求级 model 参数用）；id 空或查不到返回 null（走默认） */
    private String resolveModelName(Long modelProviderId) {
        if (modelProviderId == null) {
            return null;
        }
        try {
            ModelProviderEntity mp = modelProviderMapper.selectById(modelProviderId);
            return mp == null ? null : mp.getModel();
        } catch (Exception e) {
            log.warn("解析 model_provider={} 的模型名失败，将回退默认模型", modelProviderId, e);
            return null;
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}