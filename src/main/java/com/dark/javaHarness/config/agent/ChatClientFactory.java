package com.dark.javaHarness.config.agent;

import com.dark.javaHarness.tool.DemoTools;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

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

    /** HTTP 连接超时：第三方端点不可达时快速失败（秒） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** HTTP 读超时：LLM 生成最长等待。默认 JdkClientHttpRequestFactory 无读超时，
     * 端点不响应会永久挂起（实测：编排卡死在 CompletableFuture.get()），必须显式设置 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(300);

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

    /** 按服务商 + api_url 构建 OpenAI 兼容 ChatClient（模型默认思考行为）；参数无效或未配置 key 返回 null */
    public ChatClient build(String provider, String apiUrl) {
        return build(provider, apiUrl, false);
    }

    /**
     * 按服务商 + api_url 构建 OpenAI 兼容 ChatClient；参数无效或未配置 key 返回 null。
     *
     * @param disableThinking true 时模型层按请求注入 enable_thinking:false（dashscope 思考模型
     *                        非流式单轮推理数分钟/content 为空，流式 0 token，见 V7 迁移说明）
     */
    public ChatClient build(String provider, String apiUrl, boolean disableThinking) {
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
            // 阻塞调用通道（call）：连接/读超时防端点无响应时永久挂起
            java.net.http.HttpClient jdkClient = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
            requestFactory.setReadTimeout(READ_TIMEOUT);
            RestClient.Builder restBuilder = RestClient.builder().requestFactory(requestFactory);
            WebClient.Builder webBuilder = WebClient.builder()
                    .clientConnector(new JdkClientHttpConnector(jdkClient));
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(apiUrl)
                    .apiKey(apiKey)
                    .restClientBuilder(restBuilder)
                    // 流式调用通道（stream）：连接超时同口径；读/空闲超时由 AgentChatCaller 的
                    // Flux.timeout 兜底（JDK 连接器无响应级超时，且项目未引入 reactor-netty）
                    .webClientBuilder(webBuilder)
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder().build())
                    .build();
            // 思考端点：模型层包装器按请求注入 enable_thinking:false（defaultOptions.extraBody
            // 在带运行时选项的调用模式下不生效，见 ThinkingSwitchChatModel 类注释）
            ChatModel effective = disableThinking ? new ThinkingSwitchChatModel(model) : model;
            return ChatClient.builder(effective)
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