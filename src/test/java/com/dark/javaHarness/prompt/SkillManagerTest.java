package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.tool.TokenEstimator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

/**
 * SkillManager 单测（prompt 动态加载·子项 1）：
 * - 索引段渲染：可见技能的「名称：描述」清单 + load_skill 使用引导
 * - 空技能面：provide 返回 null、load_skill 不注册（空工具面不追加元工具）
 * - 开关关闭：app.prompt.skills.enabled=false 时索引段与元工具同时消失
 * - load_skill 元工具：命中返回全文 / 越权拒绝 / 缺参报错 / 超长正文按 tool-result-budget 截断
 */
class SkillManagerTest {

    @TempDir
    Path dir;

    private SkillManager manager(int resultBudgetTokens) {
        return new SkillManager(new SkillRepository(dir.toString()), true, resultBudgetTokens);
    }

    private void writeSkill(String file, String content) throws Exception {
        Files.writeString(dir.resolve(file), content);
    }

    // ================================================================
    // 索引段
    // ================================================================

    @Test
    void indexSection_rendersNamesAndDescriptions_withLoadSkillGuidance() throws Exception {
        writeSkill("a.md", "---\nname: alpha\ndescription: 第一个技能\n---\n正文A");
        writeSkill("b.md", "---\nname: beta\ndescription: 第二个技能\n---\n正文B");
        String section = manager(5000).provide("researcher");
        assertTrue(section.startsWith("可用技能"), "应含 load_skill 使用引导");
        assertTrue(section.contains("- alpha：第一个技能"));
        assertTrue(section.contains("- beta：第二个技能"));
        assertTrue(section.contains("load_skill"));
    }

    @Test
    void noSkills_sectionNull_andLoadSkillToolNotRegistered() {
        SkillManager m = manager(5000);
        assertNull(m.provide("researcher"), "无可见技能时索引段为 null（skill 段自动跳过）");
        assertEquals(Optional.empty(), m.loadSkillTool("researcher"),
                "无可见技能不注册元工具，避免空技能面行为漂移");
    }

    @Test
    void agentsFilter_appliesToSectionAndTool() throws Exception {
        writeSkill("c.md", "---\nname: coder-only\ndescription: 专属\nagents: coder\n---\n正文");
        SkillManager m = manager(5000);
        assertNull(m.provide("researcher"), "不可见技能不进索引段");
        assertEquals(Optional.empty(), m.loadSkillTool("researcher"), "不可见技能不注册元工具");
        assertTrue(m.provide("coder").contains("coder-only"));
        assertTrue(m.loadSkillTool("coder").isPresent());
    }

    @Test
    void disabled_noSectionNoTool() throws Exception {
        writeSkill("a.md", "---\nname: alpha\n---\n正文");
        SkillManager m = new SkillManager(new SkillRepository(dir.toString()), false, 5000);
        assertNull(m.provide("researcher"));
        assertEquals(Optional.empty(), m.loadSkillTool("researcher"));
    }

    // ================================================================
    // load_skill 元工具
    // ================================================================

    @Test
    void toolDefinition_nameAndRequiredSchema() throws Exception {
        writeSkill("a.md", "---\nname: alpha\n---\n正文");
        ToolCallback cb = manager(5000).loadSkillTool("researcher").orElseThrow();
        assertEquals("load_skill", cb.getToolDefinition().name());
        assertTrue(cb.getToolDefinition().inputSchema().contains("skillName"));
        assertTrue(cb.getToolDefinition().inputSchema().contains("required"));
    }

    @Test
    void loadSkill_hit_returnsFullBody() throws Exception {
        writeSkill("a.md", "---\nname: alpha\n---\n步骤一\n步骤二");
        ToolCallback cb = manager(5000).loadSkillTool("researcher").orElseThrow();
        String result = cb.call("{\"skillName\":\"alpha\"}");
        assertEquals("技能 alpha 完整说明：\n\n步骤一\n步骤二", result);
    }

    @Test
    void loadSkill_acceptsAliasKeysAndBareString() throws Exception {
        writeSkill("a.md", "---\nname: alpha\n---\n正文");
        ToolCallback cb = manager(5000).loadSkillTool("researcher").orElseThrow();
        assertTrue(cb.call("{\"skill_name\":\"alpha\"}").contains("完整说明"), "snake_case 别名兼容");
        assertTrue(cb.call("{\"toolName\":\"alpha\"}").contains("完整说明"), "toolName 别名兼容（与 expand_tool 同构）");
        assertTrue(cb.call("\"alpha\"").contains("完整说明"), "JSON 裸字符串兜底");
        assertTrue(cb.call("alpha").contains("完整说明"), "非 JSON 裸技能名兜底");
    }

    @Test
    void loadSkill_missingParam_returnsFixableError() throws Exception {
        writeSkill("a.md", "---\nname: alpha\n---\n正文");
        ToolCallback cb = manager(5000).loadSkillTool("researcher").orElseThrow();
        String result = cb.call("{}");
        assertTrue(result.contains("缺少 skillName 参数"), "缺参返回可自纠错误信息");
        assertTrue(cb.call("").contains("缺少 skillName 参数"), "空入参同口径");
    }

    @Test
    void loadSkill_outsideVisibleSet_rejected() throws Exception {
        writeSkill("c.md", "---\nname: coder-only\nagents: coder\n---\n机密正文");
        // researcher 的元工具：可见技能集为空 → 不注册（越权第一道防线）
        assertEquals(Optional.empty(), manager(5000).loadSkillTool("researcher"));

        // coder 与全可见技能混合场景：researcher 无法借 coder 集合外的名字加载
        writeSkill("g.md", "---\nname: shared\n---\n共享正文");
        ToolCallback cb = manager(5000).loadSkillTool("coder").orElseThrow();
        assertTrue(cb.call("{\"skillName\":\"coder-only\"}").contains("完整说明"));
        assertTrue(cb.call("{\"skillName\":\"no-such\"}").contains("拒绝加载"),
                "索引外技能按不存在拒绝");
        assertFalse(cb.call("{\"skillName\":\"no-such\"}").contains("机密正文"));
    }

    @Test
    void loadSkill_overlongBody_truncatedToBudget() throws Exception {
        // 10000 个 ASCII 字符 ≈ 2500 token（4 字符 ≈ 1 token），预算 100 必触发截断
        String body = "x".repeat(10000) + "\nEND-MARKER";
        writeSkill("big.md", "---\nname: big\n---\n" + body);
        ToolCallback cb = manager(100).loadSkillTool("researcher").orElseThrow();
        String result = cb.call("{\"skillName\":\"big\"}");
        assertTrue(result.contains("…[内容已按上下文预算截断]"), "超长正文应追加截断标记");
        assertFalse(result.contains("END-MARKER"), "尾部内容应被截掉");
        // 截断后正文部分（去掉前缀与标记）不超过预算
        String bodyPart = result.substring(result.indexOf("\n\n") + 2);
        assertTrue(TokenEstimator.estimateTokens(bodyPart) <= 100,
                "截断后正文估算 token 应 ≤ tool-result-budget");
    }

    @Test
    void loadSkill_withinBudget_notTruncated() throws Exception {
        writeSkill("s.md", "---\nname: small\n---\n短正文");
        ToolCallback cb = manager(5000).loadSkillTool("researcher").orElseThrow();
        String result = cb.call("{\"skillName\":\"small\"}");
        assertFalse(result.contains("…[内容已按上下文预算截断]"), "预算内正文不截断");
        assertTrue(result.endsWith("短正文"));
    }
}
