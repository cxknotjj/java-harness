package com.dark.javaHarness.prompt;

import java.util.List;

/**
 * Prompt 段抽象：system prompt 的一个组成段。
 *
 * <p>实现类声明段名与次序，渲染时从上下文取输入产出文本；
 * 返回 null 或空白视为空段——组装时跳过、不产生多余空行。
 */
public interface PromptSection {

    /** 段名（诊断/日志用） */
    String name();

    /** 段次序（小者在前） */
    int order();

    /** 渲染段文本；返回 null 或空白表示空段（组装时跳过） */
    String render(Context context);

    /** 一次组装中各段共享的渲染上下文 */
    record Context(String agentName, String rolePrompt, List<String> toolNames) {
    }
}
