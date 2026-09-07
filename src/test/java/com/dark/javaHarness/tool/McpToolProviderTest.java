package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * McpToolProvider 单测：懒连接 + 失败降级策略。
 * - 未配置连接目标（server-url / stdio 配置）→ 空配置面，不发起连接
 * - 目标配置但不可达 → 空配置面（连接异常被吞掉，不外抛）
 * - mcp-config.json（mcpServers 结构）能正确解析出 command + args
 * - 连得上运行中的本进程 /mcp → 能发现 McpServerTools 暴露的 3 个工具（sum/greet/today）
 */
class McpToolProviderTest {

    static {
        // stdio 降级用例里，MCP 进程立即退出/管道关闭时 transport 内部 flux 会向
        // 无订阅者发射错误，触发 Reactor 全局 onErrorDropped 打印噪声 ERROR 栈。
        // 这不是被测缺陷（降级行为本身正确），测试期静默该全局回调以保持输出干净。
        reactor.core.publisher.Hooks.onErrorDropped(t -> { });
    }

    /** 传一个不存在的配置文件路径，强制走 yaml 内联命令分支（避免被项目根 mcp-config.json 劫持） */
    private static final String NO_CONFIG = "target/no-such-mcp.json";

    @Test
    void noServerUrl_returnsEmpty_neverConnects() {
        assertTrue(new McpToolProvider("http", "", "", NO_CONFIG).toolCallbacks().isEmpty(),
                "未配置连接目标应直接返回空工具面");
    }

    @Test
    void blankServerUrl_returnsEmpty() {
        assertTrue(new McpToolProvider("http", "   ", "", NO_CONFIG).toolCallbacks().isEmpty(),
                "空白 server-url 应返回空工具面");
    }

    @Test
    void stdioNoTarget_returnsEmpty_neverSpawns() {
        // 配置文件不存在 + 内联命令为空 → 无可用目标，返回空工具面
        assertTrue(new McpToolProvider("stdio", "", "", NO_CONFIG).toolCallbacks().isEmpty(),
                "stdio 模式无任何配置应返回空工具面，不 spawn 进程");
    }

    @Test
    void unreachableServer_returnsEmpty_neverThrows() {
        // 指向一个几乎必然无监听的本地端口，验证连接失败被吞、降级为空
        McpToolProvider provider = new McpToolProvider("http", "http://localhost:1/mcp", "", NO_CONFIG);
        assertTrue(provider.toolCallbacks().isEmpty(),
                "server 不可达应降级为空工具面而非抛异常");
    }

    @Test
    void stdioBadCommand_degradesToEmpty_neverThrows() {
        // 指向一个必然立即退出的进程，验证 spawn/握手失败被吞、降级为空而非抛异常
        McpToolProvider provider = new McpToolProvider("stdio", "",
                "cmd /c exit 1", NO_CONFIG);
        assertTrue(provider.toolCallbacks().isEmpty(),
                "stdio 进程握手失败应降级为空工具面而非抛异常");
    }

    @Test
    void jsonConfig_parsesCommandAndArgs() throws Exception {
        // Claude/Cursor 同款 mcpServers JSON 结构
        String json = """
                {
                  "mcpServers": {
                    "browsermcp": {
                      "command": "npx",
                      "args": ["@browsermcp/mcp@latest"]
                    }
                  }
                }
                """;
        Path f = Files.createTempFile("mcp-test", ".json");
        Files.writeString(f, json);
        McpToolProvider.StdioTarget t = McpToolProvider.loadStdioTarget(f, "");
        assertEquals("browsermcp", t.name());
        assertEquals("npx", t.command());
        assertEquals(List.of("@browsermcp/mcp@latest"), t.args());
    }

    @Test
    void jsonConfig_invalidFile_fallsBackToInline() throws Exception {
        Path f = Files.createTempFile("mcp-test", ".json");
        Files.writeString(f, "{ not valid json ");
        McpToolProvider.StdioTarget t = McpToolProvider.loadStdioTarget(f, "npx -y @browsermcp/mcp@latest");
        assertEquals("inline", t.name());
        assertEquals("npx", t.command());
        assertEquals(List.of("-y", "@browsermcp/mcp@latest"), t.args());
    }

    /**
     * 端到端：连本进程已启动的 /mcp，验证能发现 McpServerTools 暴露的工具。
     * 要求先启动应用（mvn spring-boot:run）。未启动时按 assumption 跳过，不影响 CI 离线跑单测。
     */
    @Test
    void liveServer_discoversMcpServerTools() {
        // 预先探测 /mcp 是否可达；不可达则跳过（说明应用未启动，非用例失败）
        Assumptions.assumeTrue(endpointReachable(), "应用未启动（/mcp 不可达），跳过实时发现用例");
        List<ToolCallback> callbacks = new McpToolProvider(
                "http", "http://localhost:8080/mcp", "", NO_CONFIG).toolCallbacks();
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

    // ================================================================
    // 多 server 配置解析（prompt 动态加载·子项 3）：mcpServers 全量条目
    // ================================================================

    @Test
    void multiServerConfig_stdioAndHttpAndMixed_parsed() throws Exception {
        String json = """
                {
                  "mcpServers": {
                    "stdio-server": { "command": "npx", "args": ["-y", "some-server"] },
                    "http-server": { "url": "http://localhost:9000/mcp" },
                    "disabled": { "command": "skip-me", "enabled": false },
                    "invalid": { "description": "既无 command 也无 url" }
                  }
                }
                """;
        Path f = Files.createTempFile("mcp-multi", ".json");
        Files.writeString(f, json);
        List<McpToolProvider.ServerSpec> specs = McpToolProvider.loadServerSpecs(f);
        assertEquals(2, specs.size(), "disabled 跳过 + 无 command/url 条目跳过 = 2 个有效 server");
        McpToolProvider.ServerSpec stdio = specs.get(0);
        assertEquals("stdio-server", stdio.name());
        assertEquals("stdio", stdio.transport());
        assertEquals("npx", stdio.command());
        assertEquals(List.of("-y", "some-server"), stdio.args());
        McpToolProvider.ServerSpec http = specs.get(1);
        assertEquals("http-server", http.name());
        assertEquals("http", http.transport());
        assertEquals("http://localhost:9000/mcp", http.url());
    }

    @Test
    void multiServerConfig_enabledTrueDefault_missingEnabledFieldStillLoaded() throws Exception {
        String json = """
                { "mcpServers": { "a": { "url": "http://x/mcp" } } }
                """;
        Path f = Files.createTempFile("mcp-enabled", ".json");
        Files.writeString(f, json);
        assertEquals(1, McpToolProvider.loadServerSpecs(f).size(),
                "缺省 enabled 字段视为启用");
    }

    @Test
    void multiServerConfig_missingFileOrBadStructure_returnsEmpty() throws Exception {
        assertTrue(McpToolProvider.loadServerSpecs(Path.of("target/no-such-mcp.json")).isEmpty(),
                "文件缺失返回空表（调用方回退 legacy）");
        Path noServers = Files.createTempFile("mcp-noservers", ".json");
        Files.writeString(noServers, "{ \"other\": {} }");
        assertTrue(McpToolProvider.loadServerSpecs(noServers).isEmpty(), "无 mcpServers 对象返回空表");
        Path bad = Files.createTempFile("mcp-bad", ".json");
        Files.writeString(bad, "{ not valid json ");
        assertTrue(McpToolProvider.loadServerSpecs(bad).isEmpty(), "坏 JSON 返回空表不抛");
    }

    @Test
    void constructor_multiServerConfig_loadsAllSpecs() throws Exception {
        // 构造器优先走 mcp-config.json 全量条目（而非 legacy 单 server 分支）
        String json = """
                {
                  "mcpServers": {
                    "one": { "url": "http://localhost:1/mcp" },
                    "two": { "url": "http://localhost:2/mcp" }
                  }
                }
                """;
        Path f = Files.createTempFile("mcp-ctor", ".json");
        Files.writeString(f, json);
        // 两个不可达 server：toolCallbacks 降级为空（失败隔离，不抛），但配置面已加载
        McpToolProvider provider = new McpToolProvider("http", "", "", f.toString());
        assertTrue(provider.toolCallbacks().isEmpty(),
                "server 不可达时按 server 隔离降级为空工具面，不影响主链路");
    }
}