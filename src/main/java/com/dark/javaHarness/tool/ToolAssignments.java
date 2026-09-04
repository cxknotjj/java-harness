package com.dark.javaHarness.tool;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 工具分配表：按专家 agent 分配可用工具集。
 *
 * <p>能力来源（Sandbox 接入后）：
 * - 自研 {@link WebTools}（轻量网页抓取，Sandbox 未覆盖）
 * - {@link SandboxToolProvider} 容器级沙箱工具（Python/Shell 执行 + 文件读写检索 +
 *   浏览器导航/快照，已替代退役的宿主机 FileTools/SearchTools/ShellTools——「重合即退役」）
 * - {@link McpToolProvider} 外部 MCP Server 工具（当前 researcher 与 general 分配，扩展工具生态）
 *
 * <p>分配语义与退役前一致（同类能力等价替换），浏览器组补 web search 空缺（JS 渲染页面）：
 * - researcher：网页抓取 + 沙箱只读文件/检索 + 浏览器 + MCP 外部工具（探索者，无执行与写入）
 * - coder：沙箱执行 + 文件写入（读文件→改文件→跑命令验证闭环）
 * - analyst：沙箱执行 + 只读文件/检索
 * - general：全量（执行 + 读写 + 检索 + 网页 + 浏览器 + MCP 工具）
 * - writer / 未登记（含 multi-agent 编排器）：无工具
 *
 * <p>权限边界是服务端硬边界：只把分配到的工具 schema 发给模型，
 * 未分配的工具模型不可见、服务端也无执行注册。
 *
 * <p>MCP 工具白名单：browsermcp 全量 12 个工具（~2.5k token schema）随每次 LLM 调用
 * 的 tools 字段发送、工具循环内每轮重发；其中导航/快照与沙箱浏览器重名早已被去重丢弃，
 * 其余多数（hover/select_option/tabs/console/network…）在编排场景从未被调用。
 * 白名单只保留沙箱未覆盖的页面交互类工具，收窄约 60% 的无效 schema 开销。
 */
@Component
public class ToolAssignments {

    /** MCP 工具白名单：只放行页面交互类（读取走沙箱 browser_navigate/browser_snapshot） */
    private static final java.util.Set<String> MCP_TOOL_WHITELIST = java.util.Set.of(
            "browser_click", "browser_type", "browser_press_key", "browser_scroll");

    /**
     * 工具用途元数据：工具名 → 一句话用途，供 PromptAssembler 工具索引段渲染复用
     * （覆盖自研 WebTools/演示工具、沙箱执行/读写文件/浏览器类与 MCP 白名单工具；
     * 沙箱与 MCP 同名工具共用一条用途，如 browser_click/browser_type）。
     */
    private static final java.util.Map<String, String> TOOL_PURPOSES = java.util.Map.ofEntries(
            // 自研 WebTools / 演示工具
            java.util.Map.entry("fetchUrl", "抓取网页正文（去噪并按查询意图提取相关段落，仅 http/https）"),
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

    public ToolAssignments(WebTools webTools, SandboxToolProvider sandbox, McpToolProvider mcp) {
        this.webTools = webTools;
        this.sandbox = sandbox;
        this.mcp = mcp;
    }

    /** 取某专家的工具；未登记的专家（含 multi-agent 编排器）返回空集 */
    public ToolSet forAgent(String agentName) {
        return switch (agentName == null ? "" : agentName) {
            case "researcher" -> new ToolSet(
                    List.of(webTools),
                    concat(sandbox.readOnlyFileTools(), sandbox.browserTools(), mcpTools()));
            case "coder" -> new ToolSet(
                    List.of(),
                    concat(sandbox.baseTools(), sandbox.writeTools()));
            case "analyst" -> new ToolSet(
                    List.of(),
                    concat(sandbox.baseTools(), sandbox.readOnlyFileTools()));
            case "general" -> new ToolSet(
                    List.of(webTools),
                    concat(sandbox.baseTools(), sandbox.readOnlyFileTools(), sandbox.writeTools(),
                            sandbox.browserTools(), mcpTools()));
            default -> ToolSet.EMPTY;
        };
    }

    /** 查工具一句话用途；未登记返回空串 */
    public String purposeOf(String toolName) {
        if (toolName == null) {
            return "";
        }
        return TOOL_PURPOSES.getOrDefault(toolName, "");
    }

    /** MCP 工具经白名单过滤后再参与分配（收窄 schema 开销 + 维持最小权限） */
    private List<ToolCallback> mcpTools() {
        return mcp.toolCallbacks().stream()
                .filter(cb -> MCP_TOOL_WHITELIST.contains(cb.getToolDefinition().name()))
                .toList();
    }

    /** 按顺序合并工具列表，按工具名去重（先到者优先）；Spring AI 不允许同名工具注入同一请求 */
    private static List<ToolCallback> concat(List<ToolCallback>... lists) {
        List<ToolCallback> merged = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
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
