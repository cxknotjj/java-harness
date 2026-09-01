package com.dark.javaHarness.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.AgentMapper;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AgentConfigProvider 单测：按 agentId 查名称、按 agentName 读部署模型绑定/提示词。
 * agent.model_provider_id → model_provider.id 解析模型名。
 */
@ExtendWith(MockitoExtension.class)
class AgentConfigProviderTest {

    @Mock
    private AgentMapper agentMapper;
    @Mock
    private ModelProviderMapper modelProviderMapper;

    private AgentConfigProvider provider;

    private AgentConfigProvider newProvider() {
        return new AgentConfigProvider(agentMapper, modelProviderMapper);
    }

    @Test
    void findAgentNameById_hit_shouldReturnWriter() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setAgentId(2L);
        row.setAgentName("writer");
        when(agentMapper.selectById(2L)).thenReturn(row);

        assertEquals("writer", provider.findAgentNameById(2L).orElse("general"));
    }

    @Test
    void findAgentNameById_miss_shouldReturnEmpty() {
        provider = newProvider();
        when(agentMapper.selectById(999L)).thenReturn(null);

        assertTrue(provider.findAgentNameById(999L).isEmpty(), "agentId=999 无记录应回退空");
    }

    @Test
    void findAgentNameById_null_shouldReturnEmpty() {
        provider = newProvider();
        assertTrue(provider.findAgentNameById(null).isEmpty(), "null agentId 应返回空");
    }

    @Test
    void getAgentConfig_hit_shouldResolveModelNameFromProviderRow() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setAgentName("writer");
        row.setModelProviderId(7L);
        row.setPrompt("你是写作助手");
        // getAgentConfig 使用 LambdaQueryWrapper.selectOne，这里 mock 返回该行
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);
        ModelProviderEntity mp = new ModelProviderEntity();
        mp.setId(7L);
        mp.setModel("gpt-4o");
        when(modelProviderMapper.selectById(7L)).thenReturn(mp);

        Optional<AgentConfig> cfg = provider.getAgentConfig("writer");
        assertTrue(cfg.isPresent());
        assertEquals(7L, cfg.get().modelProviderId());
        assertEquals("gpt-4o", cfg.get().model(), "模型名应由 model_provider.id 解析");
        assertEquals("你是写作助手", cfg.get().prompt());
    }

    @Test
    void getAgentConfig_miss_shouldReturnEmpty() {
        provider = newProvider();
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        assertTrue(provider.getAgentConfig("nonexistent").isEmpty());
    }

    @Test
    void getAgentConfig_nullModelProviderId_shouldReturnNullModel() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setAgentName("general");
        row.setModelProviderId(null);
        row.setPrompt(null);
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);

        Optional<AgentConfig> cfg = provider.getAgentConfig("general");
        assertTrue(cfg.isPresent());
        assertEquals(null, cfg.get().modelProviderId());
        assertEquals(null, cfg.get().model(), "未绑定部署模型应返回 null（走默认客户端）");
    }

    @Test
    void getAgentConfig_modelProviderRowMissing_shouldReturnNullModel() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setAgentName("general");
        row.setModelProviderId(404L);
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);
        when(modelProviderMapper.selectById(404L)).thenReturn(null);

        Optional<AgentConfig> cfg = provider.getAgentConfig("general");
        assertTrue(cfg.isPresent());
        assertEquals(null, cfg.get().model(), "部署模型行不存在应回退 null（走默认客户端）");
    }
}