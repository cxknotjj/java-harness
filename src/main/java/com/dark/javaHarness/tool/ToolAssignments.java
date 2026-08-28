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
 *
 * <p>分配语义与退役前一致（同类能力等价替换），浏览器组补 web search 空缺（JS 渲染页面）：
 * - researcher：网页抓取 + 沙箱只读文件/检索 + 浏览器（探索者，无执行与写入）
 * - coder：沙箱执行 + 文件写入（读文件→改文件→跑命令验证闭环）
 * - analyst：沙箱执行 + 只读文件/检索
 * - general：全量（执行 + 读写 + 检索 + 网页 + 浏览器）
 * - writer / 未登记（含 multi-agent 编排器）：无工具
 *
 * <p>权限边界是服务端硬边界：只把分配到的工具 schema 发给模型，
 * 未分配的工具模型不可见、服务端也无执行注册。
 */
@Component
public class ToolAssignments {

    /** 双通道工具集：@Tool 注解对象（.tools 注入）+ ToolCallback（.toolCallbacks 注入） */
    public record ToolSet(List<Object> annotated, List<ToolCallback> callbacks) {

        public static final ToolSet EMPTY = new ToolSet(List.of(), List.of());

        public boolean isEmpty() {
            return annotated.isEmpty() && callbacks.isEmpty();
        }
    }

    private final WebTools webTools;
    private final SandboxToolProvider sandbox;

    public ToolAssignments(WebTools webTools, SandboxToolProvider sandbox) {
        this.webTools = webTools;
        this.sandbox = sandbox;
    }

    /** 取某专家的工具集；未登记的专家（含 multi-agent 编排器）返回空集 */
    public ToolSet forAgent(String agentName) {
        return switch (agentName == null ? "" : agentName) {
            case "researcher" -> new ToolSet(
                    List.of(webTools),
                    concat(sandbox.readOnlyFileTools(), sandbox.browserTools()));
            case "coder" -> new ToolSet(
                    List.of(),
                    concat(sandbox.baseTools(), sandbox.writeTools()));
            case "analyst" -> new ToolSet(
                    List.of(),
                    concat(sandbox.baseTools(), sandbox.readOnlyFileTools()));
            case "general" -> new ToolSet(
                    List.of(webTools),
                    concat(sandbox.baseTools(), sandbox.readOnlyFileTools(), sandbox.writeTools(),
                            sandbox.browserTools()));
            default -> ToolSet.EMPTY;
        };
    }

    private static List<ToolCallback> concat(List<ToolCallback>... lists) {
        List<ToolCallback> merged = new ArrayList<>();
        for (List<ToolCallback> l : lists) {
            merged.addAll(l);
        }
        return List.copyOf(merged);
    }
}
