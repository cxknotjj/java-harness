package com.dark.javaHarness.prompt;

import com.dark.javaHarness.tool.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * skill 装配管理器（prompt 动态加载·子项 1）：两段式技能暴露，与子项 6 工具 Schema
 * 延迟加载（{@link ToolLazyManager} expand_tool）同构——
 * <ul>
 *   <li>第一段：system prompt 只注入「名称 + 描述」索引段（实现 {@link SkillSectionProvider}，
 *       经 {@link PromptAssembler} 的 skill 段追加），压缩请求侧固定 token 开销；</li>
 *   <li>第二段：模型需要某技能时调用 {@link #LOAD_SKILL_TOOL_NAME load_skill(skillName)}
 *       元工具取完整 Markdown 正文（工具结果进上下文，供本轮直接遵循执行）。</li>
 * </ul>
 *
 * <p>预算口径：load_skill 不经 tracer（元工具不产生工具行噪声）、不占工具次数额度
 * （元数据操作不挤占真实工具额度，同 expand_tool）；但返回全文按 tool-result-budget
 * 同口径截断——超长技能防一次性灌爆上下文（与 ToolCallBudget 结果预算同价值）。
 *
 * <p>越权防护：元工具按构建时的「该 agent 可见技能集」校验 skillName，索引外技能拒绝加载
 * （服务端硬边界，与工具分配权限一致）。开关 app.prompt.skills.enabled（默认 true）：
 * 关闭时索引段为空、元工具不注册（回退无技能现状）。
 */
@Component
public class SkillManager implements SkillSectionProvider {

    /** 内置元工具名：按技能名加载完整说明 */
    public static final String LOAD_SKILL_TOOL_NAME = "load_skill";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** load_skill 的参数定义（skillName: string, required） */
    static final String LOAD_SKILL_INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"skillName\":{\"type\":\"string\","
                    + "\"description\":\"要加载的技能名\"}},\"required\":[\"skillName\"]}";

    /** 截断标记（追加在被裁剪的技能正文末尾，提示模型内容不完整） */
    static final String TRUNCATED_SUFFIX = "…[内容已按上下文预算截断]";

    private final SkillRepository repository;
    /** 装配开关（app.prompt.skills.enabled） */
    private final boolean enabled;
    /** 单次 load_skill 返回正文的 token 上限（与 app.context.tool-result-budget 同口径） */
    private final int resultBudgetTokens;

    public SkillManager(SkillRepository repository,
                        @Value("${app.prompt.skills.enabled:true}") boolean enabled,
                        @Value("${app.context.tool-result-budget:5000}") int resultBudgetTokens) {
        this.repository = repository;
        this.enabled = enabled;
        this.resultBudgetTokens = Math.max(resultBudgetTokens, 1);
    }

    /**
     * skill 索引段：该 agent 可见技能的「名称：描述」清单 + load_skill 使用引导；
     * 开关关闭/无可见技能返回 null（skill 段自动跳过，不产生多余空行）。
     */
    @Override
    public String provide(String agentName) {
        if (!enabled) {
            return null;
        }
        List<SkillRepository.Skill> skills = repository.skillsFor(agentName);
        if (skills.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("可用技能（需要执行某技能时，先调用 load_skill 工具")
                .append("（参数 skillName=技能名）获取完整说明再遵循执行）：");
        for (SkillRepository.Skill skill : skills) {
            sb.append("\n- ").append(skill.name());
            if (!skill.description().isBlank()) {
                sb.append("：").append(skill.description());
            }
        }
        return sb.toString();
    }

    /**
     * load_skill 元工具：该 agent 有可见技能时返回回调（无可见技能不注册——
     * 与「空工具面不追加 expand_tool」同一原则，避免空技能面场景行为漂移）。
     */
    public Optional<ToolCallback> loadSkillTool(String agentName) {
        if (!enabled) {
            return Optional.empty();
        }
        List<SkillRepository.Skill> skills = repository.skillsFor(agentName);
        if (skills.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LoadSkillCallback(skills));
    }

    // ================================================================
    // load_skill 元工具
    // ================================================================

    /** load_skill 元工具：按技能名返回完整 Markdown 正文；可见性按构建时的技能集校验 */
    private final class LoadSkillCallback implements ToolCallback {

        /** 该 agent 可见技能集（name 小写 → skill，越权校验 + 内容来源） */
        private final Map<String, SkillRepository.Skill> skillsByName;

        private LoadSkillCallback(List<SkillRepository.Skill> skills) {
            this.skillsByName = new HashMap<>();
            for (SkillRepository.Skill skill : skills) {
                skillsByName.putIfAbsent(skill.name().toLowerCase(java.util.Locale.ROOT), skill);
            }
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(LOAD_SKILL_TOOL_NAME)
                    .description("按技能名加载该技能的完整 Markdown 说明（用法/步骤/纪律）。"
                            + "技能索引中列出的技能，正式执行前先调用本工具获取全文。参数 skillName 为技能名。")
                    .inputSchema(LOAD_SKILL_INPUT_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return doLoad(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return doLoad(toolInput);
        }

        private String doLoad(String toolInput) {
            String skillName = extractSkillName(toolInput);
            if (skillName == null) {
                return "load_skill 调用失败：缺少 skillName 参数（string 类型，值为要加载的技能名），请修正后重试。";
            }
            SkillRepository.Skill skill = skillsByName.get(skillName.toLowerCase(java.util.Locale.ROOT));
            if (skill == null) {
                // 越权防护：不在该 agent 可见技能集内的技能拒绝加载
                return "拒绝加载：技能 " + skillName + " 不在当前可用技能列表中，"
                        + "只能加载技能索引中列出的技能。";
            }
            // 单技能全文 token 上限：超长截断 + 标记（防一次性灌爆上下文）
            String body = skill.body() == null ? "" : skill.body();
            int est = TokenEstimator.estimateTokens(body);
            if (est > resultBudgetTokens) {
                int target = Math.max(0, resultBudgetTokens - TokenEstimator.estimateTokens(TRUNCATED_SUFFIX));
                body = truncateByTokens(body, target) + TRUNCATED_SUFFIX;
            }
            return "技能 " + skill.name() + " 完整说明：\n\n" + body;
        }

        /** 从工具入参 JSON 提取 skillName（兼容别名键与裸字符串兜底，口径同 expand_tool） */
        private String extractSkillName(String toolInput) {
            if (toolInput == null || toolInput.isBlank()) {
                return null;
            }
            try {
                JsonNode root = MAPPER.readTree(toolInput);
                if (root.isObject()) {
                    for (String key : new String[]{"skillName", "skill_name", "toolName", "tool_name"}) {
                        JsonNode v = root.path(key);
                        if (v.isValueNode() && !v.asText().isBlank()) {
                            return v.asText().trim();
                        }
                    }
                    return null;
                }
                if (root.isTextual() && !root.asText().isBlank()) {
                    return root.asText().trim();
                }
                return null;
            } catch (Exception ignored) {
                // 非 JSON 入参兜底：去引号后当作裸技能名
                String bare = toolInput.trim();
                if (bare.length() >= 2 && bare.startsWith("\"") && bare.endsWith("\"")) {
                    bare = bare.substring(1, bare.length() - 1).trim();
                }
                return bare.isEmpty() ? null : bare;
            }
        }

        /** 二分查找最大前缀长度，使估算 token 数 ≤ limit（口径同 ToolCallBudget） */
        private static String truncateByTokens(String text, int limit) {
            int lo = 0;
            int hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (TokenEstimator.estimateTokens(text.substring(0, mid)) <= limit) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return text.substring(0, lo);
        }
    }
}
