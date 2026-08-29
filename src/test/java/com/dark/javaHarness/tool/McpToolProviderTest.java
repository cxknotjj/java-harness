package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * McpToolProvider 单测：懒连接 + 失败降级策略。
 * - 未配置 server-url → 空配置面，不发起连接
 * - server-url 配置但端点不可达 → 空配置面（连接异常被吞掉，不外抛）
 * - 连得上运行中的本进程 /mcp → 能发现 McpServerTools 暴露的 3 个工具（sum/greet/today）
 */
class McpToolProviderTest {

    @Test
    void noServerUrl_returnsEmpty_neverConnects() {
        assertTrue(new McpToolProvider("").toolCallbacks().isEmpty(),
                "未配置 server-url 应直接返回空工具面");
    }

    @Test
    void blankServerUrl_returnsEmpty() {
        assertTrue(new McpToolProvider("   ").toolCallbacks().isEmpty(),
                "空白 server-url 应返回空工具面");
    }

    @Test
    void unreachableServer_returnsEmpty_neverThrows() {
        // 指向一个几乎必然无监听的本地端口，验证连接失败被吞、降级为空
        McpToolProvider provider = new McpToolProvider("http://localhost:1/mcp");
        assertTrue(provider.toolCallbacks().isEmpty(),
                "server 不可达应降级为空工具面而非抛异常");
    }

    /**
     * 端到端：连本进程已启动的 /mcp，验证能发现 McpServerTools 暴露的工具。
     * 要求先启动应用（mvn spring-boot:run）。未启动时按 assumption 跳过，不影响 CI 离线跑单测。
     */
    @Test
    void liveServer_discoversMcpServerTools() {
        // 预先探测 /mcp 是否可达；不可达则跳过（说明应用未启动，非用例失败）
        Assumptions.assumeTrue(endpointReachable(), "应用未启动（/mcp 不可达），跳过实时发现用例");
        List<ToolCallback> callbacks = new McpToolProvider("http://localhost:8080/mcp").toolCallbacks();
        assertEquals(3, callbacks.size(), "应发现 McpServerTools 暴露的 3 个 MCP 工具");
        Set<String> names = new java.util.HashSet<>();
        for (ToolCallback c : callbacks) {
            names.add(c.getToolDefinition().name());
        }
        assertEquals(Set.of("sum", "greet", "today"), names, "工具名应与 McpServerTools 一致");
    }

    private static boolean endpointReachable() {
        try (var out = new java.net.Socket("localhost", 8080)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}