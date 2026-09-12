package com.dark.javaHarness.tool;

import com.dark.javaHarness.domain.McpServerLog;
import com.dark.javaHarness.service.impl.McpServerRecorder;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * MCP 工具提供者（prompt 动态加载·子项 3 多 server）：管理多个 MCP Server 连接，
 * 把各 server 暴露的工具转为标准 {@link ToolCallback} 并集，供 {@link ToolAssignments} 分配。
 *
 * <p><b>server 配置来源</b>：项目根 {@code mcp-config.json}（Claude/Cursor 同款 mcpServers
 * JSON 结构）的<b>全量条目</b>——{@code command}(±{@code args}) → stdio、{@code url} → http、
 * {@code enabled: false} 跳过；文件缺失/无有效条目 → 回退 legacy yaml 单 server
 * （http 连 {@code server-url} / stdio 解析第一条目或内联 {@code stdio-command}，原逻辑平移）。
 * 配置解析逻辑见 {@link McpConfigParser}。
 *
 * <p><b>连接策略（架构决策）</b>：沿用「后台预热 + 懒连接兜底 + 失败降级」，与
 * {@link SandboxToolProvider} 一脉相承——应用启动完全不依赖 MCP；应用就绪后（ApplicationReadyEvent）
 * 后台线程逐 server 先行连接/发现，把握手超时的代价挪出请求路径；若请求早于预热完成到来，
 * 仍走 {@link #toolCallbacks()} 的懒连接（按 server 原子互斥，同一 server 并发请求只连一次）。
 * <b>失败按 server 隔离</b>：单 server 连接/发现失败只贡献空列表 + warn（失败同样写入缓存，
 * 绝不让坏掉的 server 反复落在请求路径上，也绝不拖垮其他 server）。工具并集按名去重，
 * 先到者优先（同名冲突 warn；不做命名空间前缀，保持引用简单）。
 *
 * <p><b>按 agent 硬边界</b>：这里只负责「收集」MCP 工具；谁可见、谁能调由
 * {@link ToolAssignments} 决定（legacy 白名单 / 数据路径 tools 列按名授予）。
 */
@Component
public class McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** stdio 模式的待启动目标：来自 mcp-config.json 条目或 yaml 内联命令 */
    record StdioTarget(String name, String command, List<String> args) {}

    /** 单个 MCP server 连接规格：stdio（command±args）或 http（url） */
    record ServerSpec(String name, String transport, String url, String command, List<String> args) {}

    /** 每 server 独立连接状态：client（失败时 null）+ 已发现回调（失败=永久空，防反复落在请求路径） */
    private record ServerState(McpSyncClient client, List<ToolCallback> callbacks) {}

    /** 全部 server 规格（构造时定死，运行期不变——MCP 配置热重载明确不做，改配置需重启） */
    private final List<ServerSpec> specs;
    /** 每 server 连接状态（懒连接 + 缓存；computeIfAbsent 按 server 原子互斥） */
    private final ConcurrentHashMap<String, ServerState> states = new ConcurrentHashMap<>();
    /** 连接/发现事件 sink（结构化落库 mcp_server_log；null = 未接线，仅记日志） */
    private final java.util.function.Consumer<McpServerLog> serverEventSink;
    /** 后台预热线程：应用就绪后逐 server 先行连接/发现，避免请求线程为 MCP 握手超时买单 */
    private final ExecutorService warmupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcp-warmup");
        t.setDaemon(true);
        return t;
    });

    /** Spring 装配入口：McpServerRecorder 适配为事件 sink（recorder 为 null 时仅记日志） */
    @Autowired
    public McpToolProvider(
            @Value("${spring.ai.mcp.client.transport:http}") String transport,
            @Value("${spring.ai.mcp.client.server-url:}") String serverUrl,
            @Value("${spring.ai.mcp.client.stdio-command:}") String stdioCommand,
            @Value("${spring.ai.mcp.client.stdio-config-file:mcp-config.json}") String configFile,
            McpServerRecorder recorder) {
        this(transport, serverUrl, stdioCommand, configFile, adapt(recorder));
    }

    private static java.util.function.Consumer<McpServerLog> adapt(McpServerRecorder recorder) {
        return recorder == null ? null : recorder::record;
    }

    /** 既有测试直连入口：无观测 sink（仅记日志） */
    McpToolProvider(String transport, String serverUrl, String stdioCommand, String configFile) {
        this(transport, serverUrl, stdioCommand, configFile,
                (java.util.function.Consumer<McpServerLog>) null);
    }

    McpToolProvider(String transport, String serverUrl, String stdioCommand, String configFile,
            java.util.function.Consumer<McpServerLog> serverEventSink) {
        String normalizedTransport = transport == null ? "http" : transport.trim().toLowerCase();
        Path configPath = Path.of(configFile == null || configFile.isBlank()
                ? "mcp-config.json" : configFile.trim());
        // 优先：mcp-config.json 全量条目（stdio + http 混合）；回退：legacy yaml 单 server（原逻辑平移）
        List<ServerSpec> loaded = loadServerSpecs(configPath);
        if (loaded.isEmpty()) {
            if ("stdio".equals(normalizedTransport)) {
                StdioTarget target = loadStdioTarget(configPath, stdioCommand);
                if (target != null) {
                    loaded = List.of(new ServerSpec(target.name(), "stdio", null,
                            target.command(), target.args()));
                }
            } else if (serverUrl != null && !serverUrl.isBlank()) {
                loaded = List.of(new ServerSpec("default", "http", serverUrl.trim(), null, List.of()));
            }
        }
        this.specs = loaded;
        this.serverEventSink = serverEventSink;
        log.info("[mcp] 已加载 {} 个 MCP server 配置: {}", specs.size(),
                specs.stream().map(s -> s.name() + "(" + s.transport() + ")").toList());
    }

    /** 应用就绪后后台逐 server 预热连接/发现：把握手超时的代价挪出请求路径。挂在 ApplicationReadyEvent
     *  上，http 模式连自建 /mcp 时端点必然已就绪（规避启动死锁）；失败与成功同样落缓存。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupAsync() {
        if (specs.isEmpty()) {
            return;
        }
        warmupExecutor.execute(() -> {
            for (ServerSpec spec : specs) {
                try {
                    int count = callbacksOf(spec).size();
                    log.info("[mcp] 后台预热完成 server '{}'：{} 个工具", spec.name(), count);
                } catch (Throwable t) {
                    // callbacksOf 内部已兜底降级，此处仅防预热线程意外挂掉
                    log.warn("[mcp] 后台预热异常（server '{}'，不影响请求，首次取用仍会懒连接）: {}",
                            spec.name(), t.toString());
                }
            }
        });
    }

    /** 全部 server 的工具并集（按名去重，先到者优先）；无配置或全部失败时返回空列表（不影响主链路） */
    public List<ToolCallback> toolCallbacks() {
        if (specs.isEmpty()) {
            return List.of();
        }
        List<ToolCallback> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ServerSpec spec : specs) {
            for (ToolCallback cb : callbacksOf(spec)) {
                String name = cb.getToolDefinition().name();
                if (seen.add(name)) {
                    merged.add(cb);
                } else {
                    log.warn("[mcp] 工具 '{}' 在多个 server 重复出现，保留先发现者", name);
                }
            }
        }
        return List.copyOf(merged);
    }

    /** 单 server 回调：懒连接 + 状态缓存（computeIfAbsent 原子互斥——同 server 并发请求只连一次，不同 server 互不阻塞） */
    private List<ToolCallback> callbacksOf(ServerSpec spec) {
        return states.computeIfAbsent(spec.name(), name -> connectAndDiscover(spec)).callbacks();
    }

    /** 连接单个 server 并发现工具；失败返回空状态缓存（按 server 隔离，不重试不拖垮其他 server） */
    private ServerState connectAndDiscover(ServerSpec spec) {
        // 超时预算：requestTimeout 约束单次 tools/call 从发起到服务端返回的整段墙钟时间
        // （含服务端真正干活，如浏览器自动化首次拉起 Chrome），而非通信本身（stdio/HTTP 传输都是
        // 毫秒级）；initializationTimeout 约束 initialize 握手：8s 足够覆盖正常冷启动（npx 拉包+
        // 框架初始化），同时把「server 秒退但握手傻等」的代价从 30s 压到 8s。预热在后台线程执行，
        // 此超时正常不落在请求路径上。
        try {
            McpSyncClient client = McpClient.sync(clientTransport(spec))
                    .requestTimeout(Duration.ofSeconds(120))
                    .initializationTimeout(Duration.ofSeconds(8))
                    .clientInfo(new McpSchema.Implementation("javaHarness", "1.0"))
                    .build();
            ToolCallback[] callbacks = new SyncMcpToolCallbackProvider(client).getToolCallbacks();
            int count = callbacks == null ? 0 : callbacks.length;
            if (count == 0) {
                log.info("[mcp] 已连接 server '{}'，但无可用工具", spec.name());
                recordServerEvent(spec, "CONNECTED", 0, null);
                return new ServerState(client, List.of());
            }
            // 来源标注包装：观测层（tool_call_log.server_name）据此填充工具归属 server
            List<ToolCallback> tagged = java.util.Arrays.stream(callbacks)
                    .map(cb -> (ToolCallback) new ServerTaggedCallback(spec.name(), cb))
                    .toList();
            log.info("[mcp] server '{}' 注册 {} 个工具（transport={}）",
                    spec.name(), count, spec.transport());
            recordServerEvent(spec, "CONNECTED", count, null);
            return new ServerState(client, List.copyOf(tagged));
        } catch (Throwable t) {
            log.warn("[mcp] server '{}' 连接/发现失败，该 server 工具面为空: {}", spec.name(), t.toString());
            recordServerEvent(spec, "FAILED", null, errMsg(t));
            return new ServerState(null, List.of());
        }
    }

    /** 连接事件结构化落库（mcp_server_log）；sink 未接线或落库异常不影响主链路 */
    private void recordServerEvent(ServerSpec spec, String event, Integer toolCount, String error) {
        if (serverEventSink == null) {
            return;
        }
        try {
            serverEventSink.accept(new McpServerLog(spec.name(), spec.transport(), event,
                    toolCount, error));
        } catch (RuntimeException e) {
            log.warn("[mcp] 连接事件落库失败（不影响主链路）：{}", e.getMessage());
        }
    }

    /** 错误摘要：message 优先，空则兜底类名（超长由 Recorder 截断） */
    private static String errMsg(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }

    private static McpClientTransport clientTransport(ServerSpec spec) {
        return "stdio".equals(spec.transport())
                ? stdioTransport(new StdioTarget(spec.name(), spec.command(), spec.args()))
                : httpTransport(spec.url());
    }

    private static McpClientTransport httpTransport(String url) {
        String[] split = splitEndpointUrl(url);
        return split == null
                ? HttpClientStreamableHttpTransport.builder(url).build()
                : HttpClientStreamableHttpTransport.builder(split[0]).endpoint(split[1]).build();
    }

    /**
     * 拆分带 query 的 url 为 (origin baseUri, 「路径?query」endpoint)；无 query 返回 null（走原 builder 行为）。
     * 必要性：SDK 对每个请求做 {@code baseUri.resolve(endpoint)}（endpoint 默认 "/mcp"，绝对路径引用），
     * RFC 解析会用引用的路径覆盖 base 的路径并<b>丢弃 query</b>——Tavily 等把 key 放 query 的 url 因此
     * 变成无 key 请求（401 → initialize 失败）。拆分后 resolve 完整还原原 url（round-trip 由单测锁定）。
     */
    static String[] splitEndpointUrl(String url) {
        URI uri = URI.create(url);
        if (uri.getRawQuery() == null) {
            return null;
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return new String[]{
                uri.getScheme() + "://" + uri.getRawAuthority(),
                path + "?" + uri.getRawQuery()};
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

    /** 配置解析委托 {@link McpConfigParser}；静态入口原位保留（既有测试直连） */
    static List<ServerSpec> loadServerSpecs(Path configFile) {
        return McpConfigParser.loadServerSpecs(configFile);
    }

    /** 配置解析委托 {@link McpConfigParser}；静态入口原位保留（既有测试直连） */
    static StdioTarget loadStdioTarget(Path configFile, String inlineCommand) {
        return McpConfigParser.loadStdioTarget(configFile, inlineCommand);
    }

    /** 应用退出/容器关闭时释放全部 MCP 连接与底层资源 */
    @PreDestroy
    public void shutdown() {
        for (ServerState state : states.values()) {
            McpSyncClient c = state.client();
            if (c != null) {
                try {
                    c.closeGracefully();
                } catch (Exception e) {
                    log.warn("[mcp] 关闭连接异常: {}", e.getMessage());
                }
            }
        }
    }
}
