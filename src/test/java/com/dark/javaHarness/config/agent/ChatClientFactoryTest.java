package com.dark.javaHarness.config.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * ChatClientFactory 按服务商解析 API Key 的规则单测：
 * - yaml 显式映射优先（app.providers.<provider>.api-key）
 * - 约定式环境变量回退（<PROVIDER大写>_API_KEY）
 * - 两者皆无 → 快速失败返回 null（不构建残缺客户端）
 */
class ChatClientFactoryTest {

    private static final String URL = "https://api.example.com/v1";

    /** 约定式回退：未写 yaml 映射，仅设置 <PROVIDER>_API_KEY 环境变量即可构建（零配置接新供应商） */
    @Test
    void build_withConventionEnvKey_succeedsWithoutYamlMapping() {
        MockEnvironment env = new MockEnvironment().withProperty("MOONSHOT_API_KEY", "sk-convention");
        ChatClientFactory factory = new ChatClientFactory(env);

        assertNotNull(factory.build("moonshot", URL), "约定式环境变量 MOONSHOT_API_KEY 应被自动发现");
    }

    /** provider 大小写不敏感：表里存 'DeepSeek' 也应命中约定式回退 */
    @Test
    void build_providerCaseInsensitive_matchesConventionEnvKey() {
        MockEnvironment env = new MockEnvironment().withProperty("DEEPSEEK_API_KEY", "sk-case");
        ChatClientFactory factory = new ChatClientFactory(env);

        assertNotNull(factory.build("DeepSeek", URL));
    }

    /** yaml 显式映射优先：app.providers.<provider>.api-key 存在即用之（无需环境变量约定） */
    @Test
    void build_withYamlExplicitMapping_usesProviderProperty() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.providers.dashscope.api-key", "sk-yaml");
        ChatClientFactory factory = new ChatClientFactory(env);

        assertNotNull(factory.build("dashscope", URL), "yaml 显式映射应优先生效");
    }

    /** 两处皆无 key → 返回 null（注册表跳过该行，而不是构建出必然失败的客户端） */
    @Test
    void build_withoutAnyKey_returnsNull() {
        ChatClientFactory factory = new ChatClientFactory(new MockEnvironment());

        assertNull(factory.build("unknown-provider", URL));
    }

    /** 参数无效（provider 空 / url 空）→ 直接返回 null */
    @Test
    void build_withInvalidArgs_returnsNull() {
        MockEnvironment env = new MockEnvironment().withProperty("MOONSHOT_API_KEY", "sk-x");
        ChatClientFactory factory = new ChatClientFactory(env);

        assertNull(factory.build(null, URL));
        assertNull(factory.build("moonshot", " "));
        assertNull(factory.build(null, null));
    }
}
