package com.dark.javaHarness.tool;

import com.dark.javaHarness.service.AgentConfigProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 工具分配表：按专家 agent 分配可用工具集（prompt 动态加载·子项 2 数据驱动）。
 *
 * <p>分配来源两级：
 * <ol>
 *   <li><b>数据驱动（优先）</b>：agent 表 {@code tools} 列声明（逗号分隔组名或精确工具名，
 *       经 {@link AgentConfigProvider#findAgentTools} 每请求实时查库）——改库即生效、免重启。
 *       组名：{@code web}/{@code demo}/{@code sandbox.base}/{@code sandbox.read}/
 *       {@code sandbox.write}/{@code sandbox.browser}；其余 token 按精确工具名跨目录查找
 *       （自研注解工具 → 沙箱各组 → MCP 动态发现工具，同名先到优先=沙箱优先）。
 *       未识别 token warn 跳过，不影响其余声明。</li>
 *   <li><b>代码内置（兜底）</b>：tools 列 NULL/空白或查询异常时回退 legacy switch——
 *       与数据化前语义完全一致（含 MCP 白名单收窄）。</li>
 * </ol>
 *
 * <p>能力来源（Sandbox 接入后）：
 * - 自研 {@link WebTools}（轻量网页抓取，Sandbox 未覆盖）与 {@link DemoTools}（演示工具）
 * - {@link SandboxToolProvider} 容器级沙箱工具（Python/Shell 执行 + 文件读写检索 +
 *   浏览器导航/快照，已替代退役的宿主机 FileTools/SearchTools/ShellTools——「重合即退役」）
 * - {@link McpToolProvider} 外部 MCP Server 工具（数据路径按名授予，扩展工具生态）
 *
 * <p>权限边界是服务端硬边界：只把分配到的工具 schema 发给模型，
 * 未分配的工具模型不可见、服务端也无执行注册。
 */
@Component
public class ToolAssignments {

    private static final Logger log = LoggerFactory.getLogger(ToolAssignments.class);

    /** MCP 工具白名单：只放行页面交互类（读取走沙箱 browser_navigate/browser_snapshot）——legacy 路径专用 */
    private static final java.util.Set<String> MCP_TOOL_WHITELIST = java.util.Set.of(
            "browser_click", "browser_type", "browser_press_key", "browser_scroll");

    /**
     * 工具用途元数据：工具名 → 一句话用途，供 PromptAssembler 工具索引段渲染复用
     * （覆盖自研 WebTools/演示工具、沙箱执行/读写文件/浏览器类与 MCP 白名单工具；
     * 沙箱与 MCP 同名工具共用一条用途，如 browser_click/browser_type）。
     */
    private static final java.util.Map<String, String> TOOL_PURPOSES = java.util.Map.ofEntries(
            // 自研 WebTools / 演示工具
            java.util.Map.entry("fetchUrl", "抓取网页正文（jsoup 解析为 Markdown 并提取主内容，可按 query 过滤段落，仅 http/https）"),
            java.util.Map.entry("getCurrentTime", "获取服务器当前本地时间"),
            java.util.Map.entry("add", "计算两个整数相加"),
            // 沙箱执行类（base 容器）
            java.util.Map.entry("run_ipython_cell", "在沙箱容器内执行 Python 代码并返回输出"),
            java.util.Map.entry("run_shell_command", "在沙箱容器内执行 Shell 命令并返回输出"),
            // 沙箱只读文件类
            java.util.Map.entry("fs_read_file", "读取容器内单个文件内容"),
            java.util.Map.entry("fs_read_multiple_files", "批量读取容器内多个文件内容"),
            java.util.Map.entry("fs_list_directory", "列出容器内目录内容"),
            java.util.Map.entry("fs_directory_tree", "查看容器内目录树结构"),
            java.util.Map.entry("fs_search_files", "按模式在容器内搜索文件"),
            java.util.Map.entry("fs_get_file_info", "查看容器内文件/目录元信息"),
            // 沙箱写入类
            java.util.Map.entry("fs_write_file", "写入/新建容器内文件"),
            java.util.Map.entry("fs_edit_file", "按查找替换编辑容器内文件"),
            java.util.Map.entry("fs_create_directory", "在容器内创建目录"),
            java.util.Map.entry("fs_move_file", "移动/重命名容器内文件或目录"),
            // 沙箱浏览器类（browser 容器）+ MCP 白名单页面交互类
            java.util.Map.entry("browser_navigate", "浏览器导航打开 URL（可获取 JS 渲染后的页面）"),
            java.util.Map.entry("browser_snapshot", "获取浏览器页面无障碍快照（当前结构与文本）"),
            java.util.Map.entry("browser_click", "点击浏览器页面元素"),
            java.util.Map.entry("browser_type", "向浏览器页面元素输入文本"),
            java.util.Map.entry("browser_press_key", "在浏览器页面按键"),
            java.util.Map.entry("browser_scroll", "滚动浏览器页面"),
            java.util.Map.entry("browser_close", "关闭浏览器"));

    /** 双通道工具集：@Tool 注解对象（.tools 注入）+ ToolCallback（.toolCallbacks 注入） */
    public record ToolSet(List<Object> annotated, List<ToolCallback> callbacks) {

        public static final ToolSet EMPTY = new ToolSet(List.of(), List.of());

        public boolean isEmpty() {
            return annotated.isEmpty() && callbacks.isEmpty();
        }
    }

    private final WebTools webTools;
    private final SandboxToolProvider sandbox;
    private final McpToolProvider mcp;
    private final DemoTools demoTools = new DemoTools();
    /** agent 表 tools 列读取器（数据驱动分配来源）；null=纯 legacy 路径（测试场景） */
    private final AgentConfigProvider agentConfigProvider;

    /** 兼容构造（既有测试/旧调用链）：无 AgentConfigProvider，forAgent 走纯 legacy switch */
    public ToolAssignments(WebTools webTools, SandboxToolProvider sandbox, McpToolProvider mcp) {
        this(webTools, sandbox, mcp, null);
    }

    /** 全参构造（Spring 装配）：agentConfigProvider 非 null 时 forAgent 优先读 agent 表 tools 列 */
    @Autowired
    public ToolAssignments(WebTools webTools, SandboxToolProvider sandbox, McpToolProvider mcp,
                           AgentConfigProvider agentConfigProvider) {
        this.webTools = webTools;
        this.sandbox = sandbox;
        this.mcp = mcp;
        this.agentConfigProvider = agentConfigProvider;
    }

    /** 取某专家的工具：tools 列声明优先（数据驱动），无声明回退代码内置（legacy） */
    public ToolSet forAgent(String agentName) {
        String declared = agentConfigProvider == null ? null
                : agentConfigProvider.findAgentTools(agentName).orElse(null);
        if (declared != null && !declared.isBlank()) {
            return resolveDeclared(agentName, declared);
        }
        return legacyForAgent(agentName);
    }

    /** 代码内置分配（legacy 兜底，与数据化前语义一致）；未登记的专家（含 multi-agent 编排器）返回空集 */
    private ToolSet legacyForAgent(String agentName) {
        return switch (agentName == null ? "" : agentName) {
            case "researcher" -> new ToolSet(
                    List.of(webTools),
                    concat(List.of(sandbox.readOnlyFileTools(), sandbox.browserTools(), mcpTools())));
            case "coder" -> new ToolSet(
                    List.of(),
                    concat(List.of(sandbox.baseTools(), sandbox.writeTools())));
            case "analyst" -> new ToolSet(
                    List.of(),
                    concat(List.of(sandbox.baseTools(), sandbox.readOnlyFileTools())));
            case "general" -> new ToolSet(
                    List.of(webTools),
                    concat(List.of(sandbox.baseTools(), sandbox.readOnlyFileTools(), sandbox.writeTools(),
                            sandbox.browserTools(), mcpTools())));
            default -> ToolSet.EMPTY;
        };
    }

    /**
     * 数据驱动解析：组名展开为对应工具源，其余 token 按精确工具名跨目录查找。
     * 跨通道同名防重（Spring AI 拒绝同名工具注入同一请求）：@Tool 对象注入时登记其全部
     * 工具名，后续同名 callback/对象跳过（先到者优先，目录顺序=沙箱优先于 MCP）。
     */
    private ToolSet resolveDeclared(String agentName, String declared) {
        // 目录索引：注解工具（名 → 对象）与回调工具（名 → callback）
        Map<String, Object> annotatedByToolName = new LinkedHashMap<>();
        indexAnnotated(annotatedByToolName, webTools);
        indexAnnotated(annotatedByToolName, demoTools);
        Map<String, ToolCallback> callbackByName = new LinkedHashMap<>();
        indexCallbacks(callbackByName, List.of(sandbox.baseTools(), sandbox.readOnlyFileTools(),
                sandbox.writeTools(), sandbox.browserTools(), mcp.toolCallbacks()));

        List<Object> annotated = new ArrayList<>();
        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (String raw : declared.split("[,，;；\\s]+")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            switch (token) {
                case "web" -> addAnnotated(annotated, usedNames, webTools);
                case "demo" -> addAnnotated(annotated, usedNames, demoTools);
                case "sandbox.base" -> addCallbacks(callbacks, usedNames, sandbox.baseTools());
                case "sandbox.read" -> addCallbacks(callbacks, usedNames, sandbox.readOnlyFileTools());
                case "sandbox.write" -> addCallbacks(callbacks, usedNames, sandbox.writeTools());
                case "sandbox.browser" -> addCallbacks(callbacks, usedNames, sandbox.browserTools());
                default -> {
                    Object owner = annotatedByToolName.get(token);
                    if (owner != null) {
                        // 精确名命中注解工具：注入整个 @Tool 对象（其全部方法随行）
                        addAnnotated(annotated, usedNames, owner);
                        continue;
                    }
                    ToolCallback cb = callbackByName.get(token);
                    if (cb != null) {
                        addCallbacks(callbacks, usedNames, List.of(cb));
                    } else if (unknown.add(token)) {
                        log.warn("[tool分配] agent='{}' tools 声明含未识别 token '{}'，跳过", agentName, token);
                    }
                }
            }
        }
        return new ToolSet(List.copyOf(annotated), List.copyOf(callbacks));
    }

    /** 查工具一句话用途；未登记回退 MCP callback 真实 description（动态发现的工具在索引段也能显示用途） */
    public String purposeOf(String toolName) {
        if (toolName == null) {
            return "";
        }
        String purpose = TOOL_PURPOSES.get(toolName);
        if (purpose != null) {
            return purpose;
        }
        for (ToolCallback cb : mcp.toolCallbacks()) {
            if (toolName.equals(cb.getToolDefinition().name())) {
                return cb.getToolDefinition().description();
            }
        }
        return "";
    }

    /** 登记 @Tool 对象覆盖的工具名（注解对象按全部方法名入目录） */
    private static void indexAnnotated(Map<String, Object> index, Object toolObject) {
        for (ToolCallback cb : ToolCallbacks.from(toolObject)) {
            index.putIfAbsent(cb.getToolDefinition().name(), toolObject);
        }
    }

    /** 按序登记回调工具名（先到优先） */
    private static void indexCallbacks(Map<String, ToolCallback> index, List<List<ToolCallback>> groups) {
        for (List<ToolCallback> group : groups) {
            for (ToolCallback cb : group) {
                index.putIfAbsent(cb.getToolDefinition().name(), cb);
            }
        }
    }

    /** 注入 @Tool 对象：登记其全部工具名（防跨通道同名），重复对象/重名跳过 */
    private static void addAnnotated(List<Object> annotated, Set<String> usedNames, Object toolObject) {
        if (annotated.contains(toolObject)) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (ToolCallback cb : ToolCallbacks.from(toolObject)) {
            names.add(cb.getToolDefinition().name());
        }
        for (String name : names) {
            if (!usedNames.add(name)) {
                log.warn("[tool分配] 工具 '{}' 已由先注入的工具对象覆盖，跳过重复声明", name);
                return;
            }
        }
        annotated.add(toolObject);
    }

    /** 注入回调组：同名跳过（先到者优先；Spring AI 不允许同名工具注入同一请求） */
    private static void addCallbacks(List<ToolCallback> callbacks, Set<String> usedNames,
                                     List<ToolCallback> group) {
        for (ToolCallback cb : group) {
            if (usedNames.add(cb.getToolDefinition().name())) {
                callbacks.add(cb);
            }
        }
    }

    /** MCP 工具经白名单过滤后再参与分配（legacy 路径：收窄 schema 开销 + 维持最小权限） */
    private List<ToolCallback> mcpTools() {
        return mcp.toolCallbacks().stream()
                .filter(cb -> MCP_TOOL_WHITELIST.contains(cb.getToolDefinition().name()))
                .toList();
    }

    /** 按顺序合并工具列表，按工具名去重（先到者优先）；Spring AI 不允许同名工具注入同一请求 */
    private static List<ToolCallback> concat(List<List<ToolCallback>> lists) {
        List<ToolCallback> merged = new ArrayList<>();
        var seen = new HashSet<String>();
        for (List<ToolCallback> l : lists) {
            for (ToolCallback c : l) {
                if (seen.add(c.getToolDefinition().name())) {
                    merged.add(c);
                }
            }
        }
        return List.copyOf(merged);
    }
}
