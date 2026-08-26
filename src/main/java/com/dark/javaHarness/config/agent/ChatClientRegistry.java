package com.dark.javaHarness.config.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * ChatClient 注册表（Registry 模式）：统一管理各厂商的大模型客户端。
 *
 * 职责单一：维护"模型名 → ChatClient"映射，供 Agent 按 model 取对应客户端。
 * 启动时从 model_provider 表加载映射；具体客户端的构建委托给 ChatClientFactory，
 * 本类不关心 OpenAI 协议细节。模型接入与 Agent 行为彻底解耦。
 *
 * 新增模型/服务商 = 在 model_provider 表加一行（含 provider + api_url），无需改动代码。
 */
@Component
public class ChatClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatClientRegistry.class);

    private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();
    private final ChatClient defaultClient;
    private final ChatClientFactory clientFactory;
    private final ModelProviderMapper modelProviderMapper;

    public ChatClientRegistry(ChatClient.Builder dashScopeBuilder,
                              ChatClientFactory clientFactory,
                              ModelProviderMapper modelProviderMapper) {
        this.clientFactory = clientFactory;
        this.modelProviderMapper = modelProviderMapper;
        // 默认客户端：DashScope 自动配置（表无匹配或读取失败时的兜底）
        this.defaultClient = clientFactory.defaultClient(dashScopeBuilder);
        loadFromDatabase();
    }

    /** 从 model_provider 表加载映射，交由 ChatClientFactory 构建各模型客户端 */
    private void loadFromDatabase() {
        try {
            List<ModelProviderEntity> rows = modelProviderMapper.selectList(
                    new LambdaQueryWrapper<ModelProviderEntity>()
                            .eq(ModelProviderEntity::getStatus, 1));
            if (rows == null || rows.isEmpty()) {
                log.warn("model_provider 表无数据，使用默认 DashScope 客户端兜底");
                return;
            }
            for (ModelProviderEntity row : rows) {
                ChatClient client = clientFactory.build(row.getProvider(), row.getApiUrl());
                if (client != null) {
                    clients.put(row.getModel(), client);
                }
            }
            log.info("ChatClientRegistry 已从 model_provider 表加载 {} 条模型映射", clients.size());
        } catch (Exception e) {
            log.warn("加载 model_provider 表失败，回退默认 DashScope 客户端", e);
        }
    }

    /** 动态注册：将某个模型名绑定到指定客户端 */
    public void register(String model, ChatClient client) {
        if (model != null && client != null) {
            clients.put(model, client);
        }
    }

    /** 按模型名取客户端；未匹配时回退默认 DashScope 客户端 */
    public ChatClient get(String model) {
        if (model != null) {
            ChatClient c = clients.get(model);
            if (c != null) {
                log.info("[registry] 命中已注册客户端: model='{}'", model);
                return c;
            }
            log.info("[registry] model='{}' 未命中注册表，回退默认 DashScope 客户端", model);
        } else {
            log.info("[registry] model 为空，使用默认 DashScope 客户端");
        }
        return defaultClient;
    }
}