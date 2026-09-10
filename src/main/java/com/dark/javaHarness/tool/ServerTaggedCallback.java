package com.dark.javaHarness.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * MCP 工具来源标注装饰：保留发现来源 server 名供观测层（tool_call_log.server_name）填充，
 * 其余行为全透传。由 {@link McpToolProvider} 在 server 工具发现后包装，
 * 名称去重等合并逻辑不受影响（getToolDefinition 原样透传）。
 */
public record ServerTaggedCallback(String serverName, ToolCallback delegate) implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
