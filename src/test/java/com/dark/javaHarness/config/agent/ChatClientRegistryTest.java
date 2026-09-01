package com.dark.javaHarness.config.agent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

/**
 * ChatClientRegistry（多服务商注册表）单测（部署模型 id 键版）：
 * - 加载后按 id 命中对应客户端，getByModel 按名索引命中
 * - 禁用行（status=0 不在结果里）按名/按 id 均回退默认客户端
 * - 跨供应商同名模型各自成行，id 各自命中
 * - 热刷新后按表最新内容重建映射
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
    void loadFromDatabase_rowRegistered_shouldHitByIdAndByName() {
        when(modelProviderMapper.selectList(any())).thenReturn(List.of(
                row(1L, "gpt-4o", "dashscope", "https://dashscope.aliyuncs.com/compatible-mode", 1)));
        when(clientFactory.defaultClient(any())).thenReturn(defaultClient);
        when(clientFactory.build("dashscope", "https://dashscope.aliyuncs.com/compatible-mode")).thenReturn(gptClient);

        registry = new ChatClientRegistry(dashScopeBuilder, clientFactory, modelProviderMapper);

        assertSame(gptClient, registry.get(1L), "按 id 应命中注册的客户端");
        assertSame(gptClient, registry.getByModel("gpt-4o"), "按名索引应命中同一客户端");
        assertSame(gptClient, registry.getByModel("GPT-4O"), "按名查找大小写不敏感");
    }

    @Test
    void loadFromDatabase_disabledRow_shouldFallbackToDefault() {
        // 只有 qwen-plus(status=1)；deepseek-chat 不在结果里（被禁用 status=0）
        when(modelProviderMapper.selectList(any())).thenReturn(List.of(
                row(2L, "qwen-plus", "dashscope", "https://dashscope.aliyuncs.com/compatible-mode", 1)));
        when(clientFactory.defaultClient(any())).thenReturn(defaultClient);
        when(clientFactory.build(anyString(), anyString())).thenReturn(defaultClient);

        registry = new ChatClientRegistry(dashScopeBuilder, clientFactory, modelProviderMapper);

        assertSame(defaultClient, registry.getByModel("deepseek-chat"), "禁用模型按名应回退默认客户端");
        assertSame(defaultClient, registry.get(99L), "未注册 id 应回退默认客户端");
    }

    @Test
    void get_unknownOrNull_shouldFallbackToDefault() {
        when(modelProviderMapper.selectList(any())).thenReturn(List.of());
        when(clientFactory.defaultClient(any())).thenReturn(defaultClient);

        registry = new ChatClientRegistry(dashScopeBuilder, clientFactory, modelProviderMapper);

        assertSame(defaultClient, registry.get(1L));
        assertSame(defaultClient, registry.get(null));
        assertSame(defaultClient, registry.getByModel("unknown-model"));
        assertSame(defaultClient, registry.getByModel(null));
    }

    /** 跨供应商同名模型：各自成行各自注册，按 id 精确区分，按名查找取先加载行 */
    @Test
    void load_sameModelDifferentProviders_registeredIndependently() {
        when(modelProviderMapper.selectList(any())).thenReturn(List.of(
                row(10L, "deepseek-v4-flash", "deepseek", "https://api.deepseek.com", 1),
                row(11L, "deepseek-v4-flash", "tecent", "https://chatapi.weixin.qq.com/openai", 1)));
        when(clientFactory.defaultClient(any())).thenReturn(defaultClient);
        when(clientFactory.build("deepseek", "https://api.deepseek.com")).thenReturn(defaultClient);
        when(clientFactory.build("tecent", "https://chatapi.weixin.qq.com/openai")).thenReturn(gptClient);

        registry = new ChatClientRegistry(dashScopeBuilder, clientFactory, modelProviderMapper);

        assertSame(defaultClient, registry.get(10L), "官方行按 id 命中自己的客户端");
        assertSame(gptClient, registry.get(11L), "腾讯行按 id 命中自己的客户端");
        assertSame(defaultClient, registry.getByModel("deepseek-v4-flash"), "同名按名查找取先加载行");
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
        assertSame(gptClient, registry.get(1L));

        // 表已变更：gpt-4o 改指新 url，新增 kimi-k2
        ChatClient newGpt = org.mockito.Mockito.mock(ChatClient.class);
        ChatClient kimi = org.mockito.Mockito.mock(ChatClient.class);
        when(modelProviderMapper.selectList(any())).thenReturn(List.of(
                row(1L, "gpt-4o", "dashscope", "https://new.example.com", 1),
                row(2L, "kimi-k2", "moonshot", "https://api.moonshot.cn/v1", 1)));
        when(clientFactory.build("dashscope", "https://new.example.com")).thenReturn(newGpt);
        when(clientFactory.build("moonshot", "https://api.moonshot.cn/v1")).thenReturn(kimi);

        registry.reload();

        assertSame(newGpt, registry.get(1L), "url 变更后应使用新客户端");
        assertSame(kimi, registry.get(2L), "新模型应被注册");
        assertSame(kimi, registry.getByModel("kimi-k2"), "按名索引应随热刷新重建");
    }
}
