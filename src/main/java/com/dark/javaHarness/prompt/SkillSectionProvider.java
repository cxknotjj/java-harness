package com.dark.javaHarness.prompt;

/**
 * skill 段内容提供者（扩展点）：为指定 agent 追加 skill 段文本。
 *
 * <p>后续「skill Markdown 目录装配」实现本接口并注入 {@link PromptAssembler}
 * 即可，无需改动组装管线；当前无实现时 skill 段输出空串。
 */
public interface SkillSectionProvider {

    /** 返回该 agent 的 skill 段文本；返回 null 或空白表示无内容 */
    String provide(String agentName);
}
