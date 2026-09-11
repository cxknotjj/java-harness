package com.dark.javaHarness.tool;

import com.dark.javaHarness.prompt.SkillManager;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 技能元工具装饰器（Order 400·追加型）：该 agent 有可见技能时，在工具链末尾追加
 * {@link SkillManager#LOAD_SKILL_TOOL_NAME load_skill} 元工具（prompt 动态加载·第二段入口）。
 *
 * <p>与 lazyTools 的 expand_tool 同口径：load_skill 元工具不经观测（不产生 SSE 工具行
 * 与 tool_call_log 记录）与预算额度（元数据操作不挤占真实工具执行次数）；skill 全文
 * 在 {@link SkillManager} 内部按 tool-result-budget 同口径截断，超长技能不会一次性灌爆上下文。
 *
 * <p>直通路径（零开销）：skillManager 为 null（单测/禁用装配场景）或
 * {@link SkillManager#loadSkillTool(String)} 返回空（开关关闭/该 agent 无可见技能——
 * 与「空工具面不追加 expand_tool」同一原则）时，原样返回传入列表引用。
 *
 * <p>追加语义：适用时新建 {@link ArrayList} 防御性拷贝传入列表后追加元工具返回新列表，
 * 不修改传入列表（装饰器链共享同一 List 引用，禁止原地副作用）。
 * 非 @Component——由 {@code DefaultToolDecorators.defaults} 静态工厂统一装配。
 */
public class SkillMetaToolDecorator implements ToolCallbackDecorator {

    /** 排序值：元工具最后追加（100 观测 → 200 预算 → 300 懒加载 → 400 元工具） */
    public static final int ORDER = 400;

    /** skill 装配管理器（可为 null：单测/禁用场景） */
    private final SkillManager skillManager;

    public SkillMetaToolDecorator(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public List<ToolCallback> decorate(ToolDecorationContext ctx, List<ToolCallback> tools) {
        if (skillManager == null) {
            return tools;
        }
        // 该 agent 有可见技能时返回回调（Optional），空则不注册——避免空技能面行为漂移
        return skillManager.loadSkillTool(ctx.agentName())
                .map(metaTool -> {
                    // 防御性拷贝：新建列表追加元工具，不修改传入列表
                    List<ToolCallback> merged = new ArrayList<>(tools);
                    merged.add(metaTool);
                    return merged;
                })
                .orElse(tools);
    }
}
