package com.dark.javaHarness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 配置文件解析（纯静态）：mcp-config.json（Claude/Cursor 同款 mcpServers JSON 结构）
 * → {@link McpToolProvider.ServerSpec} 全量条目，以及 legacy stdio 启动目标解析。
 * 不感知连接与生命周期；文件缺失/解析失败/无有效条目一律返回空（调用方回退 legacy 配置）。
 */
final class McpConfigParser {

    private static final Logger log = LoggerFactory.getLogger(McpConfigParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpConfigParser() {
    }

    /**
     * 解析 mcp-config.json 的 mcpServers 全量条目为 server 规格：
     * {@code command}(±{@code args}) → stdio、{@code url} → http、{@code enabled: false} 跳过、
     * 缺少 command/url 的条目 warn 跳过。文件缺失/解析失败/无有效条目返回空表（调用方回退 legacy）。
     */
    static List<McpToolProvider.ServerSpec> loadServerSpecs(Path configFile) {
        if (!Files.exists(configFile)) {
            return List.of();
        }
        try {
            JsonNode servers = MAPPER.readTree(configFile.toFile()).get("mcpServers");
            if (servers == null || !servers.isObject()) {
                log.warn("[mcp] {} 中无 mcpServers 对象，尝试 legacy 单 server 配置", configFile);
                return List.of();
            }
            List<McpToolProvider.ServerSpec> specs = new ArrayList<>();
            var it = servers.fields();
            while (it.hasNext()) {
                var e = it.next();
                String name = e.getKey();
                JsonNode node = e.getValue();
                JsonNode enabled = node.get("enabled");
                if (enabled != null && !enabled.asBoolean(true)) {
                    log.info("[mcp] server '{}' enabled=false，跳过", name);
                    continue;
                }
                JsonNode cmd = node.get("command");
                JsonNode url = node.get("url");
                if (cmd != null && !cmd.asText().isBlank()) {
                    List<String> args = new ArrayList<>();
                    JsonNode arr = node.get("args");
                    if (arr != null && arr.isArray()) {
                        arr.forEach(a -> args.add(a.asText()));
                    }
                    log.info("[mcp] 已从 {} 加载 stdio server '{}': {} {}",
                            configFile, name, cmd.asText(), args);
                    specs.add(new McpToolProvider.ServerSpec(name, "stdio", null, cmd.asText().trim(), args));
                } else if (url != null && !url.asText().isBlank()) {
                    log.info("[mcp] 已从 {} 加载 http server '{}': {}", configFile, name, url.asText());
                    specs.add(new McpToolProvider.ServerSpec(name, "http", url.asText().trim(), null, List.of()));
                } else {
                    log.warn("[mcp] server '{}' 缺少 command/url 字段，跳过", name);
                }
            }
            return List.copyOf(specs);
        } catch (Exception ex) {
            log.warn("[mcp] 解析 MCP 配置文件失败（{}），尝试 legacy 单 server 配置: {}",
                    configFile, ex.toString());
            return List.of();
        }
    }

    /**
     * 解析 legacy stdio 启动目标：优先读 mcpServers JSON 配置文件（取第一个有效条目），失败/
     * 不存在则回退 yaml 内联命令（空格分词）。
     */
    static McpToolProvider.StdioTarget loadStdioTarget(Path configFile, String inlineCommand) {
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
                            return new McpToolProvider.StdioTarget(name, cmd.asText().trim(), args);
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
            return new McpToolProvider.StdioTarget("inline", parts[0],
                    Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length)));
        }
        return null;
    }
}
