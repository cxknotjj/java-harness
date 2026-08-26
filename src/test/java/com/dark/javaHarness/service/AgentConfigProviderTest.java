package com.dark.javaHarness.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.mapper.AgentMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AgentConfigProvider 单测：按 agentId 查名称、按 agentName 读模型/提示词。
 */
@ExtendWith(MockitoExtension.class)
class AgentConfigProviderTest {

    @Mock
    private AgentMapper agentMapper;

    private AgentConfigProvider provider;

    @Test
    void findAgentNameById_hit_shouldReturnWriter() {
        provider = new AgentConfigProvider(agentMapper);
        AgentEntity row = new AgentEntity();
        row.setAgentId(2L);
        row.setAgentName("writer");
        when(agentMapper.selectById(2L)).thenReturn(row);

        assertEquals("writer", provider.findAgentNameById(2L).orElse("general"));
    }

    @Test
    void findAgentNameById_miss_shouldReturnEmpty() {
        provider = new AgentConfigProvider(agentMapper);
        when(agentMapper.selectById(999L)).thenReturn(null);

        assertTrue(provider.findAgentNameById(999L).isEmpty(), "agentId=999 无记录应回退空");
    }

    @Test
    void findAgentNameById_null_shouldReturnEmpty() {
        provider = new AgentConfigProvider(agentMapper);
        assertTrue(provider.findAgentNameById(null).isEmpty(), "null agentId 应返回空");
    }

    @Test
    void getAgentConfig_hit_shouldReturnModelAndPrompt() {
        provider = new AgentConfigProvider(agentMapper);
        AgentEntity row = new AgentEntity();
        row.setAgentName("writer");
        row.setModel("gpt-4o");
        row.setPrompt("你是写作助手");
        // getAgentConfig 使用 LambdaQueryWrapper.selectOne，这里 mock 返回该行
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);

        Optional<AgentConfig> cfg = provider.getAgentConfig("writer");
        assertTrue(cfg.isPresent());
        assertEquals("gpt-4o", cfg.get().model());
        assertEquals("你是写作助手", cfg.get().prompt());
    }

    @Test
    void getAgentConfig_miss_shouldReturnEmpty() {
        provider = new AgentConfigProvider(agentMapper);
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        assertTrue(provider.getAgentConfig("nonexistent").isEmpty());
    }

    @Test
    void getAgentConfig_nullModelShouldBeBlankToNull() {
        provider = new AgentConfigProvider(agentMapper);
        AgentEntity row = new AgentEntity();
        row.setAgentName("general");
        row.setModel("   ");
        row.setPrompt(null);
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);

        Optional<AgentConfig> cfg = provider.getAgentConfig("general");
        assertTrue(cfg.isPresent());
        assertEquals(null, cfg.get().model(), "空白 model 应转为 null");
    }
}