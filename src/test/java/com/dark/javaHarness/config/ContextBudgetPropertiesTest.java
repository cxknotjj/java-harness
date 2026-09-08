package com.dark.javaHarness.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * ContextBudgetProperties 配置绑定单测：
 * - 配置类零默认（int 缺省 0 = 该层预算关闭），数值唯一来源是 application.yaml——
 *   直接绑定 classpath 真实 yaml 断言生产值，yaml 被误改/漂移时由本用例报警
 * - kebab-case yaml 键（max-tokens-lead 等）正确绑定并覆盖（用非 yaml 值证明覆盖优先级）
 */
class ContextBudgetPropertiesTest {

    /** 绑定 classpath 真实 application.yaml：生产数值只在这一处，误改即测试失败 */
    @Test
    void bindsApplicationYaml_productionValues() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties props = yaml.getObject();
        assertNotNull(props, "application.yaml 必须存在且可解析");

        Map<String, String> source = new HashMap<>();
        props.forEach((k, v) -> source.put(String.valueOf(k), String.valueOf(v)));

        ContextBudgetProperties budgets = new Binder(new MapConfigurationPropertySource(source))
                .bind("app.context", Bindable.of(ContextBudgetProperties.class))
                .get();

        // 输入侧
        assertEquals(4000, budgets.getHistoryBudget());
        assertEquals(4000, budgets.getLeadBudget());
        assertEquals(12000, budgets.getAggregateBudget());
        assertEquals(5000, budgets.getToolResultBudget());
        assertEquals(8, budgets.getToolCallLimit());
        // 消费侧
        assertEquals(2000, budgets.getMaxTokensLead());
        assertEquals(8000, budgets.getMaxTokensFinal());
        assertEquals(4000, budgets.getMaxTokensExpert());
        assertEquals(60000, budgets.getOrchestrationBudget());
        assertEquals(2, budgets.getSubtaskConcurrency());
    }

    /** kebab-case yaml 键绑定且覆盖（用非 yaml 值证明绑定线路与优先级生效） */
    @Test
    void bindsYamlKebabCaseKeys_overridingValues() {
        Map<String, String> source = new HashMap<>();
        source.put("app.context.max-tokens-lead", "111");
        source.put("app.context.max-tokens-final", "222");
        source.put("app.context.max-tokens-expert", "333");
        source.put("app.context.orchestration-budget", "444");
        source.put("app.context.subtask-concurrency", "555");

        ContextBudgetProperties budgets = new Binder(new MapConfigurationPropertySource(source))
                .bind("app.context", Bindable.of(ContextBudgetProperties.class))
                .get();

        assertEquals(111, budgets.getMaxTokensLead(), "lead 档应绑定 max-tokens-lead");
        assertEquals(222, budgets.getMaxTokensFinal(), "final 档应绑定 max-tokens-final");
        assertEquals(333, budgets.getMaxTokensExpert(), "expert 档应绑定 max-tokens-expert");
        assertEquals(444, budgets.getOrchestrationBudget(), "消费上限应绑定 orchestration-budget");
        assertEquals(555, budgets.getSubtaskConcurrency(), "限并发应绑定 subtask-concurrency");
    }

    /** 配置类零默认：不绑定时全 0（全键统一 0 = 不限制，组件行为等同无预算的存量行为） */
    @Test
    void zeroDefaults_allBudgetsOff() {
        ContextBudgetProperties budgets = new ContextBudgetProperties();

        assertEquals(0, budgets.getHistoryBudget());
        assertEquals(0, budgets.getLeadBudget());
        assertEquals(0, budgets.getAggregateBudget());
        assertEquals(0, budgets.getToolResultBudget());
        assertEquals(0, budgets.getToolCallLimit());
        assertEquals(0, budgets.getMaxTokensLead());
        assertEquals(0, budgets.getMaxTokensFinal());
        assertEquals(0, budgets.getMaxTokensExpert());
        assertEquals(0, budgets.getOrchestrationBudget());
        assertEquals(0, budgets.getSubtaskConcurrency());
    }
}
