package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.RouteDecision;
import com.dark.javaHarness.service.RouteJudge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 基于 LLM 的主 Agent 路由判断器。
 *
 * <p>用一次轻量 LLM 调用判断请求简单/复杂：系统提示词要求模型严格输出
 * JSON（{@code {"route":"simple"}} 或 {@code {"route":"complex"}}），
 * 解析出 route 字段归一化为 {@link RouteDecision}。
 *
 * <p>兜底策略（TODO ⑤）：调用异常 / 超时 / 返回非 JSON / 解析失败时
 * 一律返回 {@link RouteDecision#SIMPLE}，且不向调用方抛出——宁可简单，不阻塞请求。
 */
@Service
public class LlmRouteJudge implements RouteJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmRouteJudge.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 判断用模型 key：命中注册表（model_provider 有对应行）则用轻量模型提速，
     * 未匹配时使用默认 DashScope 客户端兜底（Registry 模式兜底）。
     */
    private static final String ROUTE_MODEL = "qwen3.8-27b";

    private static final String SYSTEM_PROMPT =
            "你是 Harness 的主路由判断器。判断一条用户请求应该走「简单」还是「复杂」路径。\n"
            + "只输出一行 JSON，不要任何解释、前后缀。格式严格为："
            + "{\"route\":\"simple\"} 或 {\"route\":\"complex\"}\n"
            + "- simple：无需工具、无需拆分子任务，单次回答即可（如问候、闲聊、简短问答、讲笑话、简单解释）。\n"
            + "- complex：需联网搜索、需执行代码、多步骤处理、需拆分为多个子任务（如调研竞品并输出报告、规划并执行一个完整项目）。";

    private final ChatClientRegistry clientRegistry;

    public LlmRouteJudge(ChatClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    @Override
    public RouteDecision judge(String message) {
        if (message == null || message.isBlank()) {
            log.info("[route] message为空 -> SIMPLE");
            return RouteDecision.SIMPLE;
        }
        try {
            ChatClient client = clientRegistry.get(ROUTE_MODEL);
            String content = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(message)
                    .call()
                    .content();
            return parse(content);
        } catch (Exception e) {
            // 判断失败不阻塞请求，兜底走简单路径
            log.warn("[route] 判断失败，兜底 SIMPLE：{}", safeMessage(e));
            return RouteDecision.SIMPLE;
        }
    }

    /** 解析 LLM 返回内容中的 route 字段；非法/缺失一律兜底 SIMPLE。 */
    private RouteDecision parse(String content) {
        if (content == null || content.isBlank()) {
            log.warn("[route] 返回为空，兜底 SIMPLE");
            return RouteDecision.SIMPLE;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(content);
            String route = node.path("route").asText(null);
            RouteDecision decision = RouteDecision.fromRaw(route);
            log.info("[route] 解析结果 route='{}' -> {}", route, decision);
            return decision;
        } catch (Exception e) {
            log.warn("[route] 返回非 JSON，兜底 SIMPLE | content='{}'", content.replaceAll("[\\r\\n]+", " "));
            return RouteDecision.SIMPLE;
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}