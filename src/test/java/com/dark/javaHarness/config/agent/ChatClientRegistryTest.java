package com.dark.javaHarness.config.agent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientFactory;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

/**
 * ChatClientRegistry（多服务商注册表）单测：
 * - 新增 gpt-4o(dashscope) 行后能命中并返回对应客户端
 * - 禁用 deepseek-chat(status=0) 后未注册，get(...) 回退默认客户端
 */
@ExtendWith(MockitoExtension.class)
class ChatClientRegistryTest {

    @Mock
    private ChatClient.Builder dashScopeBuilder;
    @Mock
    private ChatClientFactory clientFactory;
    @Mock
    private ModelProviderMapper modelProviderMapper;
    @Mock
    private ChatClient defaultClient;
    @Mock
    private ChatClient gptClient;

    private ChatClientRegistry registry;

    private ModelProviderEntity row(Long id, String model, String provider, String url, int status) {
        ModelProviderEntity e = new ModelProviderEntity();
        e.setId(id);
        e.setModel(model);
        e.setProvider(provider);
        e.setApiUrl(url);
        e.setStatus(status);
        return e;
    }

    @Test
    void loadFromDatabase_gpt4oRegistered_shouldHit() {
        when(modelProviderMapper.selectList(any())).thenReturn(List.of(
                row(1L, "gpt-4o", "dashscope", "https://dashscope.aliyuncs.com/compatible-mode", 1)));
        when(clientFactory.defaultClient(any())).thenReturn(defaultClient);
        when(clientFactory.build("dashscope", "https://dashscope.aliyuncs.com/compatible-mode")).thenReturn(gptClient);

        registry = new ChatClientRegistry(dashScopeBuilder, clientFactory, modelProviderMapper);

        assertSame(gptClient, registry.get("gpt-4o"), "gpt-4o 应命中注册的客户端");
    }

    @Test
    void loadFromDatabase_disabledDeepseek_shouldFallbackToDefault() {
        // 只有 qwen-plus(status=1)；deepseek-chat 不在结果里（被禁用 status=0）
        when(modelProviderMapper.selectList(any())).thenReturn(List.of(
                row(2L, "qwen-plus", "dashscope", "https://dashscope.aliyuncs.com/compatible-mode", 1)));
        when(clientFactory.defaultClient(any())).thenReturn(defaultClient);
        when(clientFactory.build(anyString(), anyString())).thenReturn(defaultClient);

        registry = new ChatClientRegistry(dashScopeBuilder, clientFactory, modelProviderMapper);

        // 禁用 deepseek-chat 未注册 → 回退默认客户端
        assertSame(defaultClient, registry.get("deepseek-chat"), "禁用模型应回退默认客户端");
    }

    @Test
    void get_unknownModel_shouldFallbackToDefault() {
        when(modelProviderMapper.selectList(any())).thenReturn(List.of());
        when(clientFactory.defaultClient(any())).thenReturn(defaultClient);

        registry = new ChatClientRegistry(dashScopeBuilder, clientFactory, modelProviderMapper);

        assertSame(defaultClient, registry.get("unknown-model"));
        assertSame(defaultClient, registry.get(null));
    }

    /** 热刷新：reload 后按表最新内容重建映射（url 变更生效、新模型注册、旧映射不残留） */
    @Test
    void reload_rebuildsClientsFromLatestTableRows() {
        // 首轮只有 gpt-4o 指向 url1
        when(modelProviderMapper.selectList(any())).thenReturn(List.of(
                row(1L, "gpt-4o", "dashscope", "https://old.example.com", 1)));
        when(clientFactory.defaultClient(any())).thenReturn(defaultClient);
        when(clientFactory.build("dashscope", "https://old.example.com")).thenReturn(gptClient);

        registry = new ChatClientRegistry(dashScopeBuilder, clientFactory, modelProviderMapper);
        assertSame(gptClient, registry.get("gpt-4o"));

        // 表已变更：gpt-4o 改指新 url，新增 kimi-k2
        ChatClient newGpt = org.mockito.Mockito.mock(ChatClient.class);
        ChatClient kimi = org.mockito.Mockito.mock(ChatClient.class);
        when(modelProviderMapper.selectList(any())).thenReturn(List.of(
                row(1L, "gpt-4o", "dashscope", "https://new.example.com", 1),
                row(2L, "kimi-k2", "moonshot", "https://api.moonshot.cn/v1", 1)));
        when(clientFactory.build("dashscope", "https://new.example.com")).thenReturn(newGpt);
        when(clientFactory.build("moonshot", "https://api.moonshot.cn/v1")).thenReturn(kimi);

        registry.reload();

        assertSame(newGpt, registry.get("gpt-4o"), "url 变更后应使用新客户端");
        assertSame(kimi, registry.get("kimi-k2"), "新模型应被注册");
    }
}