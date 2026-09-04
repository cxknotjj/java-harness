package com.dark.javaHarness.prompt;

import com.dark.javaHarness.tool.McpToolProvider;
import com.dark.javaHarness.tool.SandboxToolProvider;
import com.dark.javaHarness.tool.ToolAssignments;
import com.dark.javaHarness.tool.WebTools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolLazyManager 单测（spec 子项 6：工具 Schema 延迟加载）：
 * - 轻量包装：name/用途保留、inputSchema 置空、call 返回引导文本且不执行真实工具
 * - 同请求自愈：会话内 expand 过的轻量包装直接放行执行真实工具
 * - expand_tool 元工具：入会话集合、返回完整参数 schema、重复展开幂等、越权拒绝
 * - 加工 API：未展开→轻量、已展开→透传、expand_tool 在列、sessionId 缺失→全量透传
 * - 会话隔离：同工具名跨会话展开集互不影响；开关关闭→原始工具面不包装、无 expand_tool
 */
@ExtendWith(MockitoExtension.class)
class ToolLazyManagerTest {

    @Mock
    private SandboxToolProvider sandbox;
    @Mock
    private McpToolProvider mcp;

    /** 真实分配表（用途元数据走真实实现），沙箱/MCP 工具面 mock 为空 */
    private ToolAssignments assignments;

    @BeforeEach
    void setUp() {
        assignments = new ToolAssignments(new WebTools(), sandbox, mcp);
        lenient().when(sandbox.baseTools()).thenReturn(List.of());
        lenient().when(sandbox.readOnlyFileTools()).thenReturn(List.of());
        lenient().when(sandbox.writeTools()).thenReturn(List.of());
        lenient().when(sandbox.browserTools()).thenReturn(List.of());
        lenient().when(mcp.toolCallbacks()).thenReturn(List.of());
    }

    /** 带自定义 schema 的 mock 真实回调（描述固定 real-desc-<name> 便于断言） */
    private static ToolCallback realCallback(String name, String schema) {
        ToolCallback cb = mock(ToolCallback.class);
        lenient().when(cb.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description("real-desc-" + name).inputSchema(schema).build());
        return cb;
    }

    /** 带默认参数 schema 的真实回调 */
    private static ToolCallback realCallback(String name) {
        return realCallback(name, "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},"
                + "\"required\":[\"url\"]}");
    }

    /** 取工具面中指定名字的回调（不存在则断言失败） */
    private static ToolCallback byName(List<ToolCallback> face, String name) {
        return face.stream()
                .filter(cb -> name.equals(cb.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具面中找不到 " + name + "，实际: "
                        + face.stream().map(cb -> cb.getToolDefinition().name()).toList()));
    }

    /* ---------------- 轻量包装 ---------------- */

    @Test
    void process_unexpanded_wrapsLightweight_namePurposeBlankSchema_noRealExecution() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback real = realCallback("fs_read_file");

        List<ToolCallback> face = manager.process("s1", List.of(real));

        assertEquals(2, face.size(), "轻量包装 + expand_tool 元工具");
        ToolCallback light = byName(face, "fs_read_file");
        ToolDefinition def = light.getToolDefinition();
        assertEquals("fs_read_file", def.name(), "工具名保留");
        assertEquals(assignments.purposeOf("fs_read_file"), def.description(),
                "一句话描述取 ToolAssignments 用途元数据");
        assertEquals("{\"type\":\"object\",\"properties\":{}}", def.inputSchema(), "参数 schema 置空");

        String out = light.call("{\"path\":\"a.txt\"}");
        assertTrue(out.contains("expand_tool"), "引导文本提示先调用 expand_tool，实际: " + out);
        assertTrue(out.contains("fs_read_file"), "引导文本含工具名");
        // 真实工具未被调用（单参/双参两个入口都未触达）
        verify(real, never()).call(anyString());
        verify(real, never()).call(anyString(), any(ToolContext.class));
    }

    /** 用途元数据未登记的工具：轻量描述回退真实 ToolDefinition 描述 */
    @Test
    void process_unregisteredPurpose_fallsBackToRealDescription() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback real = realCallback("custom_tool");

        ToolCallback light = byName(manager.process("s1", List.of(real)), "custom_tool");

        assertEquals("real-desc-custom_tool", light.getToolDefinition().description(),
                "未登记用途回退真实描述");
    }

    /** 会话内已展开的工具（如同请求内刚 expand 过）：轻量包装在调用时点动态放行执行真实工具 */
    @Test
    void process_expandedInSession_lightCallbackPassesThroughToRealTool() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback real = realCallback("fetchUrl");
        lenient().when(real.call(anyString())).thenReturn("真实工具结果");
        List<ToolCallback> face = manager.process("s1", List.of(real));
        // 同请求内先 expand
        byName(face, ToolLazyManager.EXPAND_TOOL_NAME).call("{\"toolName\":\"fetchUrl\"}");

        // 随后同请求内正式调用：轻量包装放行执行真实工具（不返回引导文本）
        String out = byName(face, "fetchUrl").call("{\"url\":\"https://x\"}");
        assertEquals("真实工具结果", out, "已展开工具调用应执行真实工具");
        verify(real).call("{\"url\":\"https://x\"}");
    }

    /* ---------------- expand_tool 元工具 ---------------- */

    @Test
    void expandTool_addsToSessionSet_returnsFullSchema_idempotent() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback real = realCallback("fetchUrl");
        ToolCallback expand = byName(manager.process("s1", List.of(real)),
                ToolLazyManager.EXPAND_TOOL_NAME);

        // 元工具自身 schema：最小 toolName 定义
        assertEquals(ToolLazyManager.EXPAND_INPUT_SCHEMA, expand.getToolDefinition().inputSchema());

        String out = expand.call("{\"toolName\":\"fetchUrl\"}");
        assertTrue(out.contains("\"required\":[\"url\"]"), "返回文本含完整参数 schema，实际: " + out);
        assertTrue(out.contains("抓取网页正文"), "返回文本含用途说明");
        assertTrue(manager.expandedToolNames("s1").contains("fetchUrl"), "工具加入会话已展开集合");

        // 重复 expand：幂等——集合不重复、不报错、仍返回完整说明
        String out2 = expand.call("{\"toolName\":\"fetchUrl\"}");
        assertTrue(out2.contains("fetchUrl"), "重复展开仍返回说明，实际: " + out2);
        assertEquals(1, manager.expandedToolNames("s1").size(), "重复展开幂等，集合不重复");
    }

    /** expand_tool 的双参 call 入口（ToolContext）与单参行为一致 */
    @Test
    void expandTool_contextOverload_behavesSameAsSingleArg() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback real = realCallback("fetchUrl");
        ToolCallback expand = byName(manager.process("s1", List.of(real)),
                ToolLazyManager.EXPAND_TOOL_NAME);

        String out = expand.call("{\"toolName\":\"fetchUrl\"}", mock(ToolContext.class));

        assertTrue(out.contains("已展开工具 fetchUrl"));
        assertTrue(manager.expandedToolNames("s1").contains("fetchUrl"));
    }

    /** 越权防护：展开未分配工具被拒绝（错误提示文本、不入会话集合） */
    @Test
    void expandTool_unassignedTool_rejected_notAddedToSet() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback real = realCallback("fetchUrl");
        ToolCallback expand = byName(manager.process("s1", List.of(real)),
                ToolLazyManager.EXPAND_TOOL_NAME);

        String out = expand.call("{\"toolName\":\"run_shell_command\"}");

        assertTrue(out.contains("拒绝"), "越权展开返回错误提示文本，实际: " + out);
        assertFalse(manager.expandedToolNames("s1").contains("run_shell_command"), "未加入会话集合");
        assertTrue(manager.expandedToolNames("s1").isEmpty(), "集合保持为空");
    }

    /** 缺少 toolName 参数：返回修正提示，不加入集合 */
    @Test
    void expandTool_missingToolName_guidesModelToFix() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback real = realCallback("fetchUrl");
        ToolCallback expand = byName(manager.process("s1", List.of(real)),
                ToolLazyManager.EXPAND_TOOL_NAME);

        String out = expand.call("{}");
        assertTrue(out.contains("toolName"), "提示补 toolName 参数，实际: " + out);
        assertTrue(manager.expandedToolNames("s1").isEmpty());
    }

    /* ---------------- 加工 API ---------------- */

    @Test
    void process_unexpandedLight_expandedPassthrough_expandToolAlwaysPresent() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback ro = realCallback("fs_read_file");
        ToolCallback write = realCallback("fs_write_file");

        // 首轮：全部轻量 + expand_tool
        List<ToolCallback> first = manager.process("s1", List.of(ro, write));
        assertEquals(3, first.size(), "2 轻量 + expand_tool");
        assertNotSame(ro, byName(first, "fs_read_file"), "未展开工具为轻量包装（非原实例）");
        assertEquals("{\"type\":\"object\",\"properties\":{}}",
                byName(first, "fs_write_file").getToolDefinition().inputSchema());

        // expand 其中一个后，下一轮请求：该工具透传完整 schema，另一个仍轻量，expand_tool 仍在列
        byName(first, ToolLazyManager.EXPAND_TOOL_NAME).call("{\"toolName\":\"fs_write_file\"}");
        List<ToolCallback> second = manager.process("s1", List.of(ro, write));
        assertEquals(3, second.size(), "透传 + 轻量 + expand_tool");
        assertNotSame(ro, byName(second, "fs_read_file"), "未展开工具仍轻量包装");
        assertSame(write, byName(second, "fs_write_file"), "已展开工具原样透传完整 schema");
        assertEquals("{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},"
                        + "\"required\":[\"url\"]}",
                byName(second, "fs_write_file").getToolDefinition().inputSchema(),
                "透传回调 schema 为真实完整定义");
    }

    /** sessionId 为空/缺失（理论上不发生，防御）：全量透传、不追加 expand_tool */
    @Test
    void process_nullOrBlankSessionId_passthroughWithoutExpandTool() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback real = realCallback("fetchUrl");

        List<ToolCallback> nullSession = manager.process(null, List.of(real));
        assertEquals(List.of(real), nullSession, "null 会话 ID 全量透传");
        assertTrue(nullSession.stream().noneMatch(cb -> ToolLazyManager.EXPAND_TOOL_NAME
                .equals(cb.getToolDefinition().name())), "不追加 expand_tool");

        List<ToolCallback> blankSession = manager.process("  ", List.of(real));
        assertEquals(List.of(real), blankSession, "空白会话 ID 全量透传");
    }

    /** 空工具面：原样返回、不追加 expand_tool（无工具的 agent 不给元工具） */
    @Test
    void process_emptyFace_returnsEmptyWithoutExpandTool() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        assertTrue(manager.process("s1", List.of()).isEmpty(), "空工具面不追加 expand_tool");
    }

    /* ---------------- 会话隔离 ---------------- */

    @Test
    void process_sessionIsolation_expandedSetsIndependent() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        ToolCallback real = realCallback("fetchUrl");
        byName(manager.process("sA", List.of(real)), ToolLazyManager.EXPAND_TOOL_NAME)
                .call("{\"toolName\":\"fetchUrl\"}");

        // 会话 A 已展开 → 透传；会话 B 同工具未展开 → 仍轻量包装（互不影响）
        assertSame(real, byName(manager.process("sA", List.of(real)), "fetchUrl"),
                "会话 A 已展开透传");
        ToolCallback lightB = byName(manager.process("sB", List.of(real)), "fetchUrl");
        assertNotSame(real, lightB, "会话 B 未展开仍轻量包装");
        assertEquals("{\"type\":\"object\",\"properties\":{}}",
                lightB.getToolDefinition().inputSchema(), "会话 B schema 仍置空");
        assertTrue(manager.expandedToolNames("sB").isEmpty(), "会话 B 展开集为空");
    }

    /* ---------------- 开关回退 ---------------- */

    @Test
    void process_disabled_returnsOriginalFaceWithoutExpandTool() {
        ToolLazyManager manager = new ToolLazyManager(assignments, false);
        ToolCallback real = realCallback("fetchUrl");

        List<ToolCallback> out = manager.process("s1", List.of(real));

        assertEquals(List.of(real), out, "开关关闭原样透传（全量注入现状）");
        assertTrue(out.stream().noneMatch(cb -> ToolLazyManager.EXPAND_TOOL_NAME
                .equals(cb.getToolDefinition().name())), "不注册 expand_tool");
    }

    /** 直接展开 API：幂等入集；空白入参防御不生效 */
    @Test
    void expand_directApi_idempotent_andBlankSafe() {
        ToolLazyManager manager = new ToolLazyManager(assignments, true);
        manager.expand("s1", "fetchUrl");
        manager.expand("s1", "fetchUrl");
        assertEquals(Set.of("fetchUrl"), manager.expandedToolNames("s1"), "重复展开幂等");

        manager.expand("s2", " ");
        manager.expand(" ", "fetchUrl");
        manager.expand(null, "fetchUrl");
        assertTrue(manager.expandedToolNames("s2").isEmpty(), "空白工具名不入集");
        assertTrue(manager.expandedToolNames(" ").isEmpty(), "空白会话 ID 不生效");
        assertTrue(manager.expandedToolNames(null).isEmpty(), "null 会话 ID 返回空集");
    }
}
