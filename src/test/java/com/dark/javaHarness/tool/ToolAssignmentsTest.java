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

/**
 * ToolAssignments 单测：Sandbox 接入后的双通道（@Tool 对象 + ToolCallback）分配语义。
 * - 退役替换：原 FileTools/SearchTools/ShellTools 能力由沙箱 ToolCallback 等价承担
 * - 最小可见性：writer/未登记（含编排器）为空集；未分配工具对模型不可见
 * - 懒加载：EMPTY 集合的专家不触发沙箱初始化
 */
@ExtendWith(MockitoExtension.class)
class ToolAssignmentsTest {

    @Mock
    private SandboxToolProvider sandbox;

    private final WebTools webTools = new WebTools();

    private ToolAssignments assignments;

    private final ToolCallback cb = mock(ToolCallback.class);

    @BeforeEach
    void setUp() {
        lenient().when(sandbox.baseTools()).thenReturn(List.of(cb));
        lenient().when(sandbox.readOnlyFileTools()).thenReturn(List.of(cb, cb));
        lenient().when(sandbox.writeTools()).thenReturn(List.of(cb, cb, cb));
        assignments = new ToolAssignments(webTools, sandbox);
    }

    @Test
    void researcher_getsWebAndReadOnlySandboxTools() {
        ToolAssignments.ToolSet set = assignments.forAgent("researcher");
        assertEquals(List.of(webTools), set.annotated(), "researcher 注入网页抓取");
        assertEquals(2, set.callbacks().size(), "researcher 只有只读文件/检索沙箱工具");
        verify(sandbox, never()).baseTools();
        verify(sandbox, never()).writeTools();
    }

    @Test
    void coder_getsExecuteAndWriteSandboxTools() {
        ToolAssignments.ToolSet set = assignments.forAgent("coder");
        assertTrue(set.annotated().isEmpty(), "coder 无 @Tool 注解工具");
        assertEquals(4, set.callbacks().size(), "coder = 执行类(1) + 写入类(3)");
        verify(sandbox, never()).readOnlyFileTools();
    }

    @Test
    void analyst_getsExecuteAndReadOnlyTools() {
        ToolAssignments.ToolSet set = assignments.forAgent("analyst");
        assertEquals(3, set.callbacks().size(), "analyst = 执行类(1) + 只读类(2)");
        verify(sandbox, never()).writeTools();
    }

    @Test
    void general_getsFullToolset() {
        ToolAssignments.ToolSet set = assignments.forAgent("general");
        assertEquals(List.of(webTools), set.annotated());
        assertEquals(6, set.callbacks().size(), "general = 执行(1) + 只读(2) + 写入(3) 全量");
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
    }
}
