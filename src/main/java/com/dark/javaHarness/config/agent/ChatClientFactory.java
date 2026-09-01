package com.dark.javaHarness.config.agent;

import com.dark.javaHarness.tool.DemoTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * ChatClient 工厂：负责按服务商标识 + 端点 url 构建 OpenAI 兼容的 ChatClient。
 *
 * 只承担"客户端构建"这一件事，供 ChatClientRegistry（注册表）在加载 model_provider 表时调用。
 * api_key 不落库，解析规则（约定优于配置）：
 * 1. yaml 显式映射：app.providers.<provider>.api-key
 * 2. 约定式回退：环境变量 <PROVIDER大写>_API_KEY（如 moonshot → MOONSHOT_API_KEY）
 * 新增第三方供应商因此零代码：环境变量 + model_provider 表加行即可。
 */
@Component
public class ChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatClientFactory.class);

    private final Environment env;

    public ChatClientFactory(Environment env) {
        this.env = env;
    }

    /** 默认 DashScope 客户端（基于 spring.ai.openai 自动配置的 Builder，注册表兜底用） */
    public ChatClient defaultClient(ChatClient.Builder dashScopeBuilder) {
        warnIfPlaceholderKey();
        return dashScopeBuilder
                .defaultTools(new DemoTools())
                .build();
    }

    /** 若 api-key 仍为启动占位值，记录警告提示配置真实 key */
    private void warnIfPlaceholderKey() {
        String placeholder = "dummy-key-for-startup";
        if (placeholder.equals(env.getProperty("spring.ai.openai.api-key"))) {
            log.warn("检测到使用占位 API Key（'{}'）。真实调用大模型前，" +
                    "请通过环境变量 QWEN_API_KEY 配置真实密钥。", placeholder);
        }
    }

    /** 按服务商 + api_url 构建 OpenAI 兼容 ChatClient；参数无效或未配置 key 返回 null */
    public ChatClient build(String provider, String apiUrl) {
        if (provider == null || apiUrl == null || apiUrl.isBlank()) {
            return null;
        }
        String apiKey = resolveApiKey(provider);
        if (apiKey == null) {
            log.warn("服务商 {} 未配置 API Key（yaml app.providers.{}.api-key 或环境变量 {}），跳过其模型",
                    provider, provider.toLowerCase(), provider.toUpperCase() + "_API_KEY");
            return null;
        }
        try {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(apiUrl)
                    .apiKey(apiKey)
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder().build())
                    .build();
            return ChatClient.builder(model)
                    .defaultTools(new DemoTools())
                    .build();
        } catch (Exception e) {
            log.warn("构建服务商客户端失败 provider={}, api_url={}", provider, apiUrl, e);
            return null;
        }
    }

    /** 按服务商解析 api-key：yaml 显式映射优先，约定式环境变量回退（敏感信息不落库） */
    private String resolveApiKey(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        String key = env.getProperty("app.providers." + provider.toLowerCase() + ".api-key");
        if (key == null || key.isBlank()) {
            key = env.getProperty(provider.toUpperCase() + "_API_KEY");
        }
        return (key == null || key.isBlank()) ? null : key;
    }
}