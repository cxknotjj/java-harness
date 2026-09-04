package com.dark.javaHarness.prompt;

import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.tool.ToolAssignments;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * Prompt 组装管线：按 agent 名把 system prompt 按固定段落次序组装——
 * 角色段 → 工具索引段 → 工具纪律段 → 输出约定段 → skill 段（扩展点）。
 * 段落之间用空行分隔，空段跳过、不产生多余空行。
 *
 * <p>角色段优先级（现状查表逻辑平移）：agent 表该行 prompt &gt; 调用方传入的角色兜底
 * &gt; 默认 system prompt。lead/aggregator 兜底 prompt、子任务专家 persona 等
 * 角色兜底文本由调用方经 roleFallback 传入，本类不持有角色文案。
 *
 * <p>工具索引段基于 {@link ToolAssignments} 的用途元数据（purposeOf）渲染；
 * 工具纪律段收敛自编排子任务原先硬编码的工具使用纪律文本。
 *
 * <p>skill 段是预留扩展点：实现 {@link SkillSectionProvider} 注入本类即可追加内容
 * （后续「skill Markdown 目录装配」的衔接点），当前无实现时该段输出空串。
 */
public class PromptAssembler {

    /** 默认 system prompt（角色段兜底链的最终兜底） */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个执行任务的 AI 助手，请直接给出简洁、可执行的完成结果。";

    private final AgentService agentService;
    private final ToolAssignments toolAssignments;
    private final List<SkillSectionProvider> skillProviders;
    /** 工具 Schema 延迟加载开关（与 ToolLazyManager 同源）：开启时工具索引段追加 expand_tool 使用引导 */
    private final boolean lazyToolsEnabled;
    /** 固定段落集合（渲染时按 order 排序，次序声明即所得） */
    private final List<PromptSection> sections;

    public PromptAssembler(AgentService agentService, ToolAssignments toolAssignments) {
        this(agentService, toolAssignments, List.of(), false);
    }

    public PromptAssembler(AgentService agentService, ToolAssignments toolAssignments,
                           List<SkillSectionProvider> skillProviders) {
        this(agentService, toolAssignments, skillProviders, false);
    }

    /**
     * @param lazyToolsEnabled 延迟加载是否开启（ToolLazyManager.isEnabled() 同源传入）：
     *                         开启时工具索引段追加「先 expand_tool 获取参数说明」引导，
     *                         与轻量态工具面（schema 置空）语义对齐；关闭时维持现状渲染
     */
    public PromptAssembler(AgentService agentService, ToolAssignments toolAssignments,
                           List<SkillSectionProvider> skillProviders, boolean lazyToolsEnabled) {
        this.agentService = agentService;
        this.toolAssignments = toolAssignments;
        this.skillProviders = skillProviders == null ? List.of() : List.copyOf(skillProviders);
        this.lazyToolsEnabled = lazyToolsEnabled;
        this.sections = List.of(new RoleSection(), new ToolIndexSection(),
                new ToolDisciplineSection(), new OutputConventionSection(), new SkillSection());
    }

    /**
     * 组装 system prompt：段落按固定次序以空行拼接，空段跳过。
     *
     * @param agentName    agent 名（查 agent 表角色 prompt 与工具分配面）
     * @param roleFallback 角色段兜底文本（可空）：如 lead/aggregator 兜底 prompt、子任务专家 persona
     */
    public String assemble(String agentName, String roleFallback) {
        PromptSection.Context ctx = new PromptSection.Context(
                agentName, resolveRolePrompt(agentName, roleFallback), toolNamesOf(agentName));
        return sections.stream()
                .sorted(Comparator.comparingInt(PromptSection::order))
                .map(section -> section.render(ctx))
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 子任务专家 persona（角色段兜底文本）：专家身份说明。
     * predictSubtask 以此作为 fallbackSystem 传入调用器，工具纪律/输出约定等段
     * 由 {@link #assemble} 统一追加；「专家名只是身份不是工具」约束保留原文，
     * 防止模型把专家名误当工具调用（原 No ToolCallback found 幻觉的防线）。
     */
    public String subtaskPersona(String expert) {
        return "你是「" + expert + "」专家 Agent，以该领域专家的方式执行子任务。"
                + "只能调用系统提供的工具列表中的工具；专家名（researcher/coder/analyst/writer 等）"
                + "只是你的身份标识，绝不是可调用的工具。";
    }

    /** 角色段取值：agent 表 prompt &gt; 调用方兜底 &gt; 默认 system prompt */
    private String resolveRolePrompt(String agentName, String roleFallback) {
        String tablePrompt = agentName == null || agentService == null ? null
                : agentService.getAgentConfig(agentName)
                        .map(AgentConfig::prompt)
                        .filter(prompt -> !prompt.isBlank())
                        .orElse(null);
        if (tablePrompt != null) {
            return tablePrompt;
        }
        return roleFallback == null || roleFallback.isBlank() ? DEFAULT_SYSTEM_PROMPT : roleFallback;
    }

    /** 该 agent 分配到的工具名清单（回调 + @Tool 注解双通道；无工具/未登记返回空表） */
    private List<String> toolNamesOf(String agentName) {
        if (toolAssignments == null || agentName == null) {
            return List.of();
        }
        ToolAssignments.ToolSet toolSet = toolAssignments.forAgent(agentName);
        if (toolSet.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (ToolCallback cb : toolSet.callbacks()) {
            names.add(cb.getToolDefinition().name());
        }
        for (ToolCallback cb : ToolCallbacks.from(toolSet.annotated().toArray())) {
            names.add(cb.getToolDefinition().name());
        }
        return List.copyOf(names);
    }

    /* ---------------- 内置段落（按 order 固定次序渲染） ---------------- */

    /** 角色段：角色 prompt 原文（来源解析在组装入口完成，恒非空） */
    private class RoleSection implements PromptSection {

        @Override
        public String name() {
            return "角色";
        }

        @Override
        public int order() {
            return 100;
        }

        @Override
        public String render(PromptSection.Context context) {
            return context.rolePrompt();
        }
    }

    /** 工具索引段：按分配到的工具渲染「名称：用途」清单（未分配工具时为空段） */
    private class ToolIndexSection implements PromptSection {

        @Override
        public String name() {
            return "工具索引";
        }

        @Override
        public int order() {
            return 200;
        }

        @Override
        public String render(PromptSection.Context context) {
            if (context.toolNames().isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("可用工具索引（名称：用途）：");
            for (String toolName : context.toolNames()) {
                String purpose = toolAssignments == null ? "" : toolAssignments.purposeOf(toolName);
                sb.append("\n- ").append(toolName);
                if (!purpose.isBlank()) {
                    sb.append("：").append(purpose);
                }
            }
            // 延迟加载模式补充 expand 引导：索引只给用途不给参数，模型需先 expand 获取完整参数说明
            if (lazyToolsEnabled) {
                sb.append("\n以上工具当前为轻量态（参数说明未随请求注入）：需要调用某工具时，")
                        .append("先调用 expand_tool 工具（参数 toolName=\"工具名\"）展开获取完整参数说明，再正式调用。");
            }
            return sb.toString();
        }
    }

    /** 工具纪律段：抓取/调用频次纪律（原编排子任务硬编码文本平移；未分配工具时为空段） */
    private class ToolDisciplineSection implements PromptSection {

        @Override
        public String name() {
            return "工具纪律";
        }

        @Override
        public int order() {
            return 300;
        }

        @Override
        public String render(PromptSection.Context context) {
            if (context.toolNames().isEmpty()) {
                return null;
            }
            return "工具使用纪律：网络类工具（fetchUrl/browser_navigate 等抓取与浏览）合计调用不超过 8 次；"
                    + "同一 URL 只抓取一次；优先一次抓取多角度提取信息，"
                    + "材料足以支撑结论时立即停止调用工具并输出结果。";
        }
    }

    /** 输出约定段：直接产出最终完成结果（恒非空） */
    private class OutputConventionSection implements PromptSection {

        @Override
        public String name() {
            return "输出约定";
        }

        @Override
        public int order() {
            return 400;
        }

        @Override
        public String render(PromptSection.Context context) {
            return "输出约定：直接给出完成结果。";
        }
    }

    /** skill 段（扩展点）：拼接各提供者内容（无实现/全空时空段） */
    private class SkillSection implements PromptSection {

        @Override
        public String name() {
            return "skill";
        }

        @Override
        public int order() {
            return 500;
        }

        @Override
        public String render(PromptSection.Context context) {
            return skillProviders.stream()
                    .map(provider -> provider.provide(context.agentName()))
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n"));
        }
    }
}
