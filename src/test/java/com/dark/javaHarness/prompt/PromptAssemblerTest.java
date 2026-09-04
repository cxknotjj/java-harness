package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.tool.McpToolProvider;
import com.dark.javaHarness.tool.SandboxToolProvider;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.WebTools;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * PromptAssembler 单测：
 * - 段落固定次序（角色 → 工具索引 → 工具纪律 → 输出约定 → skill）、空段跳过无多余空行
 * - 角色段优先级：agent 表 prompt &gt; 调用方兜底 &gt; 默认 system prompt
 * - skill 段扩展点：注入 SkillSectionProvider 时其文本出现在 skill 段位，无注入时该段为空
 * - 子任务 persona 组装结果包含专家名与工具纪律文本
 */
@ExtendWith(MockitoExtension.class)
class PromptAssemblerTest {

    @Mock
    private AgentService agentService;
    @Mock
    private SandboxToolProvider sandbox;
    @Mock
    private McpToolProvider mcp;

    /** 真实分配表 + mock 沙箱/MCP 提供者：工具面分配与用途元数据走真实实现 */
    private ToolAssignments toolAssignments;
    private PromptAssembler assembler;

    @BeforeEach
    void setUp() {
        toolAssignments = new ToolAssignments(new WebTools(), sandbox, mcp);
        assembler = new PromptAssembler(agentService, toolAssignments);
        stubEmptyToolFaces();
    }

    /** 沙箱/MCP 工具面默认为空（lenient：用不到该工具组的用例免检） */
    private void stubEmptyToolFaces() {
        lenient().when(sandbox.baseTools()).thenReturn(List.of());
        lenient().when(sandbox.readOnlyFileTools()).thenReturn(List.of());
        lenient().when(sandbox.writeTools()).thenReturn(List.of());
        lenient().when(sandbox.browserTools()).thenReturn(List.of());
        lenient().when(mcp.toolCallbacks()).thenReturn(List.of());
    }

    /** 构造仅名字可读的工具回调（索引渲染只取工具名与用途元数据） */
    private static ToolCallback callbackNamed(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        lenient().when(cb.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description("d").inputSchema("{}").build());
        return cb;
    }

    /** 段落固定次序：角色段 → 工具索引段 → 工具纪律段 → 输出约定段（skill 空段跳过） */
    @Test
    void assemble_sectionsInFixedOrder() {
        when(agentService.getAgentConfig("general"))
                .thenReturn(Optional.of(new AgentConfig(1L, "m", "角色提示词")));
        // 回调 mock 先建好再注入 stub（避免在 when() 求值内嵌套 stubbing）
        ToolCallback readOnlyCb = callbackNamed("fs_read_file");
        when(sandbox.readOnlyFileTools()).thenReturn(List.of(readOnlyCb));

        // general = 自研 WebTools（@Tool 注解通道）+ 上述只读回调
        String system = assembler.assemble("general", "兜底角色");
        String[] parts = system.split("\n\n", -1);

        assertEquals("角色提示词", parts[0], "角色段在最前");
        assertTrue(parts[1].startsWith("可用工具索引"), "第二段为工具索引段");
        assertTrue(parts[1].contains("- fs_read_file：读取容器内单个文件内容"),
                "索引行复用 ToolAssignments 用途元数据（回调通道）");
        assertTrue(parts[1].contains("- fetchUrl：抓取网页正文"),
                "索引行覆盖 @Tool 注解工具（fetchUrl）");
        assertTrue(parts[2].startsWith("工具使用纪律"), "第三段为工具纪律段");
        assertEquals("输出约定：直接给出完成结果。", parts[3], "第四段为输出约定段");
        assertEquals(4, parts.length, "无 skill 提供者时 skill 段为空段被跳过");
    }

    /** 空段跳过：未分配工具的 agent 不渲染索引/纪律段，且无多余空行 */
    @Test
    void assemble_skipsEmptySectionsWithoutExtraBlankLines() {
        when(agentService.getAgentConfig("writer")).thenReturn(Optional.empty());

        String system = assembler.assemble("writer", "撰写角色");
        String[] parts = system.split("\n\n", -1);

        assertEquals(2, parts.length, "无工具时仅角色段 + 输出约定段");
        assertEquals("撰写角色", parts[0]);
        assertEquals("输出约定：直接给出完成结果。", parts[1]);
        assertFalse(system.startsWith("\n"));
        assertFalse(system.endsWith("\n"));
        assertFalse(system.contains("\n\n\n"), "不应出现连续多空行");
        assertFalse(system.contains("工具使用纪律"), "未分配工具的 agent 不渲染工具纪律段");
    }

    /** 角色段优先级：agent 表 prompt &gt; 调用方兜底 &gt; 默认 system prompt；表 prompt 空白视同无 */
    @Test
    void assemble_rolePromptPriority() {
        // 表 prompt 存在 → 用表
        when(agentService.getAgentConfig("researcher"))
                .thenReturn(Optional.of(new AgentConfig(1L, "m", "表配置角色")));
        String withTable = assembler.assemble("researcher", "兜底角色");
        assertTrue(withTable.startsWith("表配置角色"), "表 prompt 优先");
        assertFalse(withTable.contains("兜底角色"), "表 prompt 存在时不再使用兜底");

        // 表无记录 → 角色兜底
        when(agentService.getAgentConfig("coder")).thenReturn(Optional.empty());
        assertTrue(assembler.assemble("coder", "兜底角色").startsWith("兜底角色"), "无表记录用兜底");

        // 表 prompt 为空白 → 视同无
        when(agentService.getAgentConfig("analyst"))
                .thenReturn(Optional.of(new AgentConfig(2L, "m", "  ")));
        assertTrue(assembler.assemble("analyst", "兜底角色").startsWith("兜底角色"), "空白表 prompt 视同无");

        // 表无记录且无兜底 → 默认 system prompt
        when(agentService.getAgentConfig("writer")).thenReturn(Optional.empty());
        assertTrue(assembler.assemble("writer", null).startsWith("你是一个执行任务的 AI 助手"),
                "兜底也缺失时用默认 system prompt");
    }

    /** skill 扩展点：注入 SkillSectionProvider 时其文本出现在 skill 段位（末段） */
    @Test
    void assemble_skillProviderTextAtSkillSlot() {
        PromptAssembler withSkill = new PromptAssembler(agentService, toolAssignments,
                List.of(agentName -> "SKILL：demo-skill 用法说明"));

        String system = withSkill.assemble("general", "兜底角色");
        String[] parts = system.split("\n\n", -1);

        assertEquals("SKILL：demo-skill 用法说明", parts[parts.length - 1], "skill 段文本应位于末段");
        assertEquals("输出约定：直接给出完成结果。", parts[parts.length - 2], "skill 段之前是输出约定段");
    }

    /** skill 段缺省为空：无提供者时该段输出空串、不产生尾随空行 */
    @Test
    void assemble_withoutSkillProvider_skillSectionEmpty() {
        String system = assembler.assemble("writer", "撰写角色");

        assertFalse(system.contains("SKILL"), "无 skill 提供者时不应有 skill 内容");
        assertFalse(system.endsWith("\n"), "skill 空段被跳过，无尾随空行");
    }

    /** 子任务 persona 组装结果包含专家名与工具纪律文本 */
    @Test
    void subtaskPersona_assembledSystemContainsExpertNameAndToolDiscipline() {
        when(agentService.getAgentConfig("researcher")).thenReturn(Optional.empty());

        // predictSubtask 以 persona 作角色兜底传入，组装后的 system 应含专家名与工具纪律段
        String system = assembler.assemble("researcher", assembler.subtaskPersona("researcher"));

        assertTrue(system.contains("「researcher」"), "应包含专家身份 persona");
        assertTrue(system.contains("只是你的身份标识，绝不是可调用的工具"), "应包含专家名非工具的约束");
        assertTrue(system.contains("工具使用纪律"), "应包含工具纪律段");
        assertTrue(system.contains("合计调用不超过 8 次"), "应包含网络工具调用频次纪律");
    }

    /**
     * 延迟加载开关（spec 子项 6）：开启时工具索引段追加 expand_tool 使用引导（与轻量态
     * 工具面对齐——索引只给用途不给参数，模型知道何时 expand）；关闭时维持现状渲染。
     */
    @Test
    void assemble_lazyToolsEnabled_indexSectionCarriesExpandHint() {
        PromptAssembler lazy = new PromptAssembler(agentService, toolAssignments, List.of(), true);
        when(agentService.getAgentConfig("general")).thenReturn(Optional.empty());
        ToolCallback readOnlyCb = callbackNamed("fs_read_file");
        when(sandbox.readOnlyFileTools()).thenReturn(List.of(readOnlyCb));

        String lazySystem = lazy.assemble("general", "兜底角色");
        String[] parts = lazySystem.split("\n\n", -1);

        assertTrue(parts[1].startsWith("可用工具索引"), "第二段仍为工具索引段");
        assertTrue(parts[1].contains("expand_tool"), "延迟模式索引段含 expand_tool 引导，实际: " + parts[1]);
        assertTrue(parts[1].contains("- fs_read_file：读取容器内单个文件内容"), "用途行保留");

        assertFalse(assembler.assemble("general", "兜底角色").contains("expand_tool"),
                "关闭延迟加载时维持现状渲染（无 expand 引导）");
    }

    /** 工具用途元数据：登记工具返回用途，未登记/空名返回空串 */
    @Test
    void purposeOf_registeredAndUnregisteredTools() {
        assertEquals("抓取网页正文（去噪并按查询意图提取相关段落，仅 http/https）",
                toolAssignments.purposeOf("fetchUrl"));
        assertEquals("在沙箱容器内执行 Python 代码并返回输出", toolAssignments.purposeOf("run_ipython_cell"));
        assertEquals("", toolAssignments.purposeOf("no_such_tool"), "未登记返回空串");
        assertEquals("", toolAssignments.purposeOf(null), "空名返回空串");
    }
}
