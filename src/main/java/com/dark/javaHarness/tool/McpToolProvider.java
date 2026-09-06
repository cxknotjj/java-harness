package com.dark.javaHarness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * MCP 工具提供者：懒连接 MCP Server，把其暴露的工具转为
 * 标准 {@link ToolCallback} 列表，供 {@link ToolAssignments} 按 agent 分配。
 *
 * <p><b>双传输支持</b>：由 {@code spring.ai.mcp.client.transport} 选择——
 * <ul>
 *   <li>{@code http}（默认）：Streamable-HTTP 连 {@code server-url}（如本进程自建的 /mcp 端点）；
 *   <li>{@code stdio}：spawn 外部 MCP 进程，目标解析优先级：项目根 {@code mcp-config.json}
 *       （Claude/Cursor 同款 mcpServers JSON 结构，取第一个条目）→ yaml 内联 {@code stdio-command}。
 *       Windows 下 npx/npm 自动补 .cmd（ProcessBuilder 不做 PATHEXT 解析）。
 * </ul>
 *
 * <p><b>连接策略（架构决策）</b>：MCP client 走「后台预热 + 懒连接兜底 + 失败降级」，与
 * {@link SandboxToolProvider} 一脉相承——应用启动完全不依赖 MCP；应用就绪后（ApplicationReadyEvent）
 * 后台线程先行连接/发现，把握手超时的代价挪出请求路径；若请求早于预热完成到来，仍走
 * toolCallbacks() 的懒连接（双重检查锁并发安全）。连接/发现失败（含空目标）同样写入缓存，
 * 绝不让单点故障拖垮应用、也绝不让首条用户消息为坏掉的 MCP server 买单（曾经：坏的 stdio
 * server 握手超时 30s 全额落在用户首条消息上）。
 * 注意 http 模式连本进程时须待 Spring 启动完成后 /mcp 才可达，预热挂在 ApplicationReadyEvent
 * 天然规避了启动死锁。
 *
 * <p><b>按 agent 硬边界</b>：这里只负责「收集」MCP 工具；谁可见、谁能调由
 * {@link ToolAssignments} 决定（当前 researcher 与 general）。未分配 agent 的请求不注入这些 schema。
 */
@Component
public class McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** stdio 模式的待启动目标：来自 mcp-config.json 条目或 yaml 内联命令 */
    record StdioTarget(String name, String command, List<String> args) {}

    private final String transport;
    private final String serverUrl;
    private final StdioTarget stdioTarget;
    private final Object lock = new Object();
    private volatile List<ToolCallback> cached;
    private volatile McpSyncClient client;
    /** 后台预热线程：应用就绪后先行连接/发现，避免请求线程为 MCP 握手超时买单 */
    private final ExecutorService warmupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcp-warmup");
        t.setDaemon(true);
        return t;
    });

    public McpToolProvider(
            @Value("${spring.ai.mcp.client.transport:http}") String transport,
            @Value("${spring.ai.mcp.client.server-url:}") String serverUrl,
            @Value("${spring.ai.mcp.client.stdio-command:}") String stdioCommand,
            @Value("${spring.ai.mcp.client.stdio-config-file:mcp-config.json}") String configFile) {
        this.transport = transport == null ? "http" : transport.trim().toLowerCase();
        this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
        this.stdioTarget = "stdio".equals(this.transport)
                ? loadStdioTarget(Path.of(configFile == null || configFile.isBlank()
                        ? "mcp-config.json" : configFile.trim()), stdioCommand)
                : null;
    }

    /** 应用就绪后后台预热 MCP 连接/发现：把握手超时的代价挪出请求路径。挂在 ApplicationReadyEvent
     *  上，http 模式连自建 /mcp 时端点必然已就绪（规避启动死锁）；失败与成功同样落缓存。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupAsync() {
        warmupExecutor.execute(() -> {
            try {
                List<ToolCallback> result = toolCallbacks();
                log.info("[mcp] 后台预热完成，工具面 {} 个工具", result.size());
            } catch (Throwable t) {
                // toolCallbacks 内部已兜底降级，此处仅防预热线程意外挂掉
                log.warn("[mcp] 后台预热异常（不影响请求，首次取用仍会懒连接）: {}", t.toString());
            }
        });
    }

    /** MCP 暴露的工具回调；未配置连接目标或连接失败时返回空列表（不影响主链路） */
    public List<ToolCallback> toolCallbacks() {
        List<ToolCallback> c = cached;
        if (c != null) {
            return c;
        }
        String target = target();
        if (target.isEmpty()) {
            // 空目标是永久态（构造时已定）：缓存空结果，避免每个请求重复 warn
            log.warn("[mcp] transport={} 但未配置连接目标（server-url / stdio-command），MCP 工具面为空", transport);
            cached = List.of();
            return cached;
        }
        synchronized (lock) {
            if (cached != null) {
                return cached;
            }
            try {
                cached = connectAndDiscover();
            } catch (Throwable t) {
                log.warn("[mcp] 连接/发现 MCP 工具失败（{}），工具面为空: {}", target, t.toString());
                cached = List.of();
            }
            return cached;
        }
    }

    /** 建立连接并返回工具回调；初始化/发现失败抛异常（由上层降级为空） */
    private List<ToolCallback> connectAndDiscover() {
        // 超时预算：requestTimeout 约束单次 tools/call 从发起到服务端返回的整段墙钟时间
        // （含服务端真正干活，如浏览器自动化首次拉起 Chrome），而非通信本身（stdio/HTTP 传输都是
        // 毫秒级）；过小会把「服务端在执行」误判为超时。
        // initializationTimeout 约束 initialize 握手：8s 足够覆盖正常冷启动（npx 拉包+框架初始化），
        // 同时把「server 秒退但握手傻等」的代价从 30s 压到 8s（坏的 npx server 通常 1-2s 内就写
        // stderr 退出——实测 browsermcp 1.3s 即报 ERR_INVALID_URL，握手却干等满 30s）。预热在
        // 后台线程执行，此超时正常不落在请求路径上。
        McpSyncClient client = McpClient.sync(clientTransport())
                .requestTimeout(Duration.ofSeconds(120))
                .initializationTimeout(Duration.ofSeconds(8))
                .clientInfo(new McpSchema.Implementation("javaHarness", "1.0"))
                .build();
        this.client = client;
        ToolCallback[] callbacks = new SyncMcpToolCallbackProvider(client).getToolCallbacks();
        if (callbacks == null || callbacks.length == 0) {
            log.info("[mcp] 已连接 {}，但无可用工具", target());
            return List.of();
        }
        log.info("[mcp] 已注册 {} 个 MCP 工具（transport={} server={}）",
                callbacks.length, transport, target());
        return List.of(callbacks);
    }

    private McpClientTransport clientTransport() {
        return "stdio".equals(transport)
                ? stdioTransport(stdioTarget)
                : httpTransport(serverUrl);
    }

    private static McpClientTransport httpTransport(String url) {
        return HttpClientStreamableHttpTransport.builder(url).build();
    }

    /** stdio 传输：spawn 外部 MCP 进程，经其 stdin/stdout 管道通信 */
    private static McpClientTransport stdioTransport(StdioTarget target) {
        String cmd = target.command();
        // Windows 下 npx/npm 是 .cmd 脚本，ProcessBuilder 不会做 PATHEXT 解析，需显式补全
        if (IS_WINDOWS && (cmd.equalsIgnoreCase("npx") || cmd.equalsIgnoreCase("npm"))) {
            cmd = cmd + ".cmd";
        }
        ServerParameters params = ServerParameters.builder(cmd)
                .args(target.args())
                .build();
        return new StdioClientTransport(params, McpJsonMapper.getDefault());
    }

    /**
     * 解析 stdio 启动目标：优先读 mcpServers JSON 配置文件（取第一个条目，结构同
     * Claude/Cursor 的 mcp-config），失败/不存在则回退 yaml 内联命令（空格分词）。
     * 包可见以便单测直接验证解析逻辑。
     */
    static StdioTarget loadStdioTarget(Path configFile, String inlineCommand) {
        if (Files.exists(configFile)) {
            try {
                JsonNode servers = MAPPER.readTree(configFile.toFile()).get("mcpServers");
                if (servers != null && servers.isObject()) {
                    var it = servers.fields();
                    while (it.hasNext()) {
                        var e = it.next();
                        JsonNode cmd = e.getValue().get("command");
                        if (cmd != null && !cmd.asText().isBlank()) {
                            List<String> args = new ArrayList<>();
                            JsonNode arr = e.getValue().get("args");
                            if (arr != null && arr.isArray()) {
                                arr.forEach(a -> args.add(a.asText()));
                            }
                            String name = e.getKey();
                            log.info("[mcp] 已从 {} 加载 stdio server '{}': {} {}",
                                    configFile, name, cmd.asText(), args);
                            return new StdioTarget(name, cmd.asText().trim(), args);
                        }
                    }
                }
                log.warn("[mcp] {} 中无有效的 mcpServers 条目，尝试 yaml 内联命令", configFile);
            } catch (Exception ex) {
                log.warn("[mcp] 解析 MCP 配置文件失败（{}），尝试 yaml 内联命令: {}", configFile, ex.toString());
            }
        }
        if (inlineCommand != null && !inlineCommand.isBlank()) {
            String[] parts = inlineCommand.trim().split("\\s+");
            return new StdioTarget("inline", parts[0],
                    Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length)));
        }
        return null;
    }

    /** 连接目标描述（日志用）：http 取 server-url，stdio 取目标名+启动命令 */
    private String target() {
        if (!"stdio".equals(transport)) {
            return serverUrl;
        }
        return stdioTarget == null ? ""
                : stdioTarget.name() + " -> " + stdioTarget.command() + " " + stdioTarget.args();
    }

    /** 应用退出/容器关闭时释放 MCP 连接与底层资源 */
    @PreDestroy
    public void shutdown() {
        McpSyncClient c = client;
        if (c != null) {
            try {
                c.closeGracefully();
            } catch (Exception e) {
                log.warn("[mcp] 关闭连接异常: {}", e.getMessage());
            }
        }
    }
}