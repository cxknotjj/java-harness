package com.dark.javaHarness.tool;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 本应用自建的 MCP Server 工具集：作为 MCP client 连接到本进程 {@code /mcp} 后可发现的「外部」工具。
 *
 * <p>作用：演示「模型调用一个外部 MCP 工具完成真实任务」的闭环（本项目进程内 server ↔ client）。
 * 工具保持简单、确定、无副作用，便于端到端验收。
 */
@Component
public class McpServerTools {

    /** 两数求和（MCP 演示工具）：验证模型能发现并调用外部工具 */
    @Tool(description = "计算两个整数的和（MCP 演示工具）")
    public String sum(int a, int b) {
        return "sum=" + (a + b);
    }

    /** 拼接问候语（枚举演示）：验证字符串参数在 client↔server 间正确传参 */
    @Tool(description = "对给定名字生成一条中文问候语（MCP 演示工具）")
    public String greet(String name) {
        return "你好，" + name + "！";
    }

    /** 当前日期（无参工具演示）：展示无参工具如何被发现与调用 */
    @Tool(description = "返回今天的日期字符串（MCP 演示工具）")
    public String today() {
        return java.time.LocalDate.now().toString();
    }

    /** 把 @Tool 方法注册为 ToolCallbackProvider，供 MCP server 端点暴露这些工具 */
    @Configuration
    public static class McpServerToolConfiguration {
        @Bean
        ToolCallbackProvider mcpServerToolsProvider(McpServerTools tools) {
            return MethodToolCallbackProvider.builder()
                    .toolObjects(tools)
                    .build();
        }
    }
}