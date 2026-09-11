package com.dark.javaHarness.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;

/**
 * {@link SkillMetaToolDecorator} 单测：元工具追加型装饰的三种路径。
 *
 * <p>契约点：不适用（manager 为 null / 无可见技能）时原样返回传入引用（零开销直通）；
 * 适用时防御性拷贝追加元工具，绝不修改传入列表。
 */
class SkillMetaToolDecoratorTest {

    private final com.dark.javaHarness.prompt.SkillManager skillManager =
            Mockito.mock(com.dark.javaHarness.prompt.SkillManager.class);

    private final ToolDecorationContext ctx =
            new ToolDecorationContext("agentA", "session-1", null, true);

    @Test
    @DisplayName("skillManager 为 null 时直通：返回传入列表同一引用")
    void decorate_returnsSameInstance_whenManagerNull() {
        List<ToolCallback> tools = List.of(mock(ToolCallback.class));

        List<ToolCallback> result = new SkillMetaToolDecorator(null).decorate(ctx, tools);

        assertThat(result).isSameAs(tools);
    }

    @Test
    @DisplayName("该 agent 无可见技能时直通：返回传入列表同一引用")
    void decorate_returnsSameInstance_whenNoSkillVisible() {
        when(skillManager.loadSkillTool("agentA")).thenReturn(Optional.empty());
        List<ToolCallback> tools = List.of(mock(ToolCallback.class));

        List<ToolCallback> result = new SkillMetaToolDecorator(skillManager).decorate(ctx, tools);

        assertThat(result).isSameAs(tools);
        Mockito.verify(skillManager).loadSkillTool("agentA");
    }

    @Test
    @DisplayName("有可见技能时追加元工具：新列表末位为该元工具且传入列表未被修改")
    void decorate_appendsMetaTool_whenSkillVisible() {
        ToolCallback realTool = mock(ToolCallback.class);
        ToolCallback metaTool = mock(ToolCallback.class);
        when(skillManager.loadSkillTool("agentA")).thenReturn(Optional.of(metaTool));
        List<ToolCallback> original = new ArrayList<>(List.of(realTool));

        List<ToolCallback> result = new SkillMetaToolDecorator(skillManager).decorate(ctx, original);

        assertThat(result).hasSize(original.size() + 1);
        assertThat(result.get(0)).isSameAs(realTool);
        assertThat(result.get(result.size() - 1)).isSameAs(metaTool);
        // 防御性拷贝：传入列表本身不被修改
        assertThat(original).hasSize(1);
        assertThat(original).containsExactly(realTool);
    }
}
