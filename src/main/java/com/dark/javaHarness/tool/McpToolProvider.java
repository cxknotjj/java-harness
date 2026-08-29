package com.dark.javaHarness.tool;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP 工具提供者：懒连接本应用的 MCP Server（Streamable-HTTP），把其暴露的工具转为
 * 标准 {@link ToolCallback} 列表，供 {@link ToolAssignments} 按 agent 分配。
 *
 * <p><b>连接策略（架构决策）</b>：MCP client 走「懒连接 + 失败降级」，与 {@link SandboxToolProvider}
 * 一脉相承——应用启动完全不依赖 MCP，首次被 agent 取工具时才连接本进程的 {@code /mcp} 端点；
 * 连接/发现失败则返回空工具面并记录 warn，绝不让单点故障拖垮整个应用。
 * 注意 client 与 server 同进程，须待 Spring 启动完成后 /mcp 才可达，故懒连接天然规避了启动死锁。
 *
 * <p><b>按 agent 硬边界</b>：这里只负责「收集」MCP 工具；谁可见、谁能调由
 * {@link ToolAssignments} 决定（当前 researcher 与 general）。未分配 agent 的请求不注入这些 schema。
 */
@Component
public class McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    private final String serverUrl;
    private final Object lock = new Object();
    private volatile List<ToolCallback> cached;
    private volatile McpSyncClient client;

    public McpToolProvider(@Value("${spring.ai.mcp.client.server-url:}") String serverUrl) {
        this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
    }

    /** MCP 暴露的工具回调；未配置 server-url 或连接失败时返回空列表（不影响主链路） */
    public List<ToolCallback> toolCallbacks() {
        List<ToolCallback> c = cached;
        if (c != null) {
            return c;
        }
        if (serverUrl.isEmpty()) {
            log.warn("[mcp] 未配置 spring.ai.mcp.client.server-url，MCP 工具面为空");
            return List.of();
        }
        synchronized (lock) {
            if (cached != null) {
                return cached;
            }
            try {
                cached = connectAndDiscover();
            } catch (Throwable t) {
                log.warn("[mcp] 连接/发现 MCP 工具失败（{}），工具面为空: {}", serverUrl, t.toString());
                cached = List.of();
            }
            return cached;
        }
    }

    /** 建立连接并返回工具回调；初始化/发现失败抛异常（由上层降级为空） */
    private List<ToolCallback> connectAndDiscover() {
        McpSyncClient client = McpClient.sync(httpTransport(serverUrl))
                .requestTimeout(Duration.ofSeconds(15))
                .initializationTimeout(Duration.ofSeconds(10))
                .clientInfo(new McpSchema.Implementation("javaHarness", "1.0"))
                .build();
        this.client = client;
        ToolCallback[] callbacks = new SyncMcpToolCallbackProvider(client).getToolCallbacks();
        if (callbacks == null || callbacks.length == 0) {
            log.info("[mcp] 已连接 {}，但无可用工具", serverUrl);
            return List.of();
        }
        log.info("[mcp] 已注册 {} 个 MCP 工具（server={}）", callbacks.length, serverUrl);
        return List.of(callbacks);
    }

    private static McpClientTransport httpTransport(String url) {
        return HttpClientStreamableHttpTransport.builder(url).build();
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