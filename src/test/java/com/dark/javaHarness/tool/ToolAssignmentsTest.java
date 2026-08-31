package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolAssignments 单测：Sandbox 接入后的双通道（@Tool 对象 + ToolCallback）分配语义。
 * - 退役替换：原 FileTools/SearchTools/ShellTools 能力由沙箱 ToolCallback 等价承担
 * - 最小可见性：writer/未登记（含编排器）为空集；未分配工具对模型不可见
 * - 同名去重：先到者优先（沙箱工具优先于 MCP 重名工具），防 Spring AI 同名校验失败
 * - 懒加载：EMPTY 集合的专家不触发沙箱初始化
 */
@ExtendWith(MockitoExtension.class)
class ToolAssignmentsTest {

    @Mock
    private SandboxToolProvider sandbox;

    @Mock
    private McpToolProvider mcp;

    private final WebTools webTools = new WebTools();

    private ToolAssignments assignments;

    @BeforeEach
    void setUp() {
        ToolCallback base = named("base");
        ToolCallback ro1 = named("ro1");
        ToolCallback ro2 = named("ro2");
        ToolCallback w1 = named("w1");
        ToolCallback w2 = named("w2");
        ToolCallback w3 = named("w3");
        ToolCallback b1 = named("browser_navigate");
        ToolCallback b2 = named("browser_snapshot");
        ToolCallback mcpSearch = named("mcp_search");
        lenient().when(sandbox.baseTools()).thenReturn(List.of(base));
        lenient().when(sandbox.readOnlyFileTools()).thenReturn(List.of(ro1, ro2));
        lenient().when(sandbox.writeTools()).thenReturn(List.of(w1, w2, w3));
        lenient().when(sandbox.browserTools()).thenReturn(List.of(b1, b2));
        lenient().when(mcp.toolCallbacks()).thenReturn(List.of(mcpSearch));
        assignments = new ToolAssignments(webTools, sandbox, mcp);
    }

    /** 带 toolDefinition 名字的 mock 回调（真实工具名才可验证同名去重语义） */
    private static ToolCallback named(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        lenient().when(cb.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description("test tool").inputSchema("{}").build());
        return cb;
    }

    @Test
    void researcher_getsWebAndReadOnlySandboxTools() {
        ToolAssignments.ToolSet set = assignments.forAgent("researcher");
        assertEquals(List.of(webTools), set.annotated(), "researcher 注入网页抓取");
        assertEquals(5, set.callbacks().size(), "researcher = 只读文件(2) + 浏览器(2) + MCP(1)");
        verify(sandbox, never()).baseTools();
        verify(sandbox, never()).writeTools();
    }

    @Test
    void researcherAndGeneral_getsMcpTools_butNoOtherAgentDoes() {
        // MCP 外部工具（扩展工具生态）分配给 researcher 与 general（full-access 也含扩展工具）
        assertEquals(1, countNames(assignments.forAgent("researcher"), "mcp_search"),
                "researcher 应能看见 MCP 工具");
        assertEquals(1, countNames(assignments.forAgent("general"), "mcp_search"),
                "general 应能看见 MCP 工具");
        assertEquals(0, countNames(assignments.forAgent("coder"), "mcp_search"),
                "coder 不应看见 MCP 工具");
        assertEquals(0, countNames(assignments.forAgent("analyst"), "mcp_search"),
                "analyst 不应看见 MCP 工具");
    }

    @Test
    void duplicateNames_deduped_keepingFirstOccurrence() {
        // 复现生产事故：Browser MCP 的 browser_navigate/browser_snapshot 与沙箱浏览器工具重名，
        // Spring AI 校验「Multiple tools with the same name」直接拒绝请求 → 合并时按名去重，先到者优先
        ToolCallback mcpNavigate = named("browser_navigate");
        ToolCallback mcpSnapshot = named("browser_snapshot");
        ToolCallback mcpNew = named("mcp_only");
        lenient().when(mcp.toolCallbacks())
                .thenReturn(List.of(mcpNavigate, mcpSnapshot, mcpNew));

        ToolAssignments.ToolSet general = assignments.forAgent("general");
        assertEquals(9, general.callbacks().size(),
                "8 沙箱 + 3 MCP − 2 个与沙箱重名（browser_navigate/browser_snapshot）= 9");
        assertFalse(general.callbacks().contains(mcpNavigate), "重名 MCP 工具应被沙箱版本取代");
        assertFalse(general.callbacks().contains(mcpSnapshot), "重名 MCP 工具应被沙箱版本取代");
        assertTrue(general.callbacks().contains(mcpNew), "无冲突 MCP 工具正常保留");
    }

    private static long countNames(ToolAssignments.ToolSet set, String name) {
        return set.callbacks().stream()
                .filter(c -> name.equals(c.getToolDefinition().name()))
                .count();
    }

    @Test
    void coder_getsExecuteAndWriteSandboxTools() {
        ToolAssignments.ToolSet set = assignments.forAgent("coder");
        assertTrue(set.annotated().isEmpty(), "coder 无 @Tool 注解工具");
        assertEquals(4, set.callbacks().size(), "coder = 执行类(1) + 写入类(3)");
        verify(sandbox, never()).readOnlyFileTools();
        verify(sandbox, never()).browserTools();
    }

    @Test
    void analyst_getsExecuteAndReadOnlyTools() {
        ToolAssignments.ToolSet set = assignments.forAgent("analyst");
        assertEquals(3, set.callbacks().size(), "analyst = 执行类(1) + 只读类(2)");
        verify(sandbox, never()).writeTools();
        verify(sandbox, never()).browserTools();
    }

    @Test
    void general_getsFullToolset() {
        ToolAssignments.ToolSet set = assignments.forAgent("general");
        assertEquals(List.of(webTools), set.annotated());
        assertEquals(9, set.callbacks().size(), "general = 执行(1) + 只读(2) + 写入(3) + 浏览器(2) + MCP(1) 全量");
    }

    @Test
    void unregisteredAgent_returnsEmptyAndSkipsSandboxInit() {
        for (String name : new String[]{"writer", "multi-agent", "deepseek", null, "hacker"}) {
            ToolAssignments.ToolSet set = assignments.forAgent(name);
            assertTrue(set.isEmpty(), name + " 应为空集");
            assertFalse(set.annotated().contains(webTools), name + " 不应看见任何工具");
        }
        verify(sandbox, never()).baseTools();
        verify(sandbox, never()).readOnlyFileTools();
        verify(sandbox, never()).writeTools();
        verify(sandbox, never()).browserTools();
    }
}
