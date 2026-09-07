package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

/**
 * GeneralAssistantAgent 响应式流式单测：
 * 核心契约 —— executeStreamReactive 必须**真·逐 token 发射**（stream 内容序列原样透传），
 * 不得退化为「同步整段生成完后一次性产出」（接口 default 的行为）。
 */
@ExtendWith(MockitoExtension.class)
class GeneralAssistantAgentTest {

    /** 文本 token → ChatResponse 流（生产流式链已切 chatResponse 通道以捕获 streamUsage 末帧） */
    private static Flux<ChatResponse> fluxOf(String... tokens) {
        return Flux.fromArray(List.of(tokens).stream()
                .map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))))
                .toArray(ChatResponse[]::new));
    }


    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private SessionService memoryStore;
    @Mock
    private AgentService agentService;
    @Mock
    private com.dark.javaHarness.tool.ToolAssignments toolAssignments;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    private GeneralAssistantAgent agent;

    @BeforeEach
    void setUp() {
        agent = new GeneralAssistantAgent("general", clientRegistry, memoryStore, agentService, toolAssignments, null);
        when(agentService.getAgentConfig("general")).thenReturn(java.util.Optional.empty());
        lenient().when(toolAssignments.forAgent(any())).thenReturn(com.dark.javaHarness.tool.ToolAssignments.ToolSet.EMPTY);
        when(clientRegistry.get(isNull())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
    }

    /** 喂 N 片 token 就应透传 N 个元素；退化实现只会产出单个整段元素 */
    @Test
    void executeStreamReactive_shouldEmitTokensProgressively() {
        List<String> tokens = List.of("你好", "，", "\n", "世界");
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(fluxOf(tokens.toArray(new String[0])));

        List<String> out = agent.executeStreamReactive(new Goal("g1", "自我介绍"))
                .collectList()
                .block();

        assertEquals(tokens, out, "应逐 token 原样透传，元素数量与顺序不变");
    }

    /**
     * 回归（streamUsage）：末帧是只含 usage 的空帧（无 generations，contentOf=null）。
     * Reactor 的 map 不允许 null 返回（「The mapper returned a null value」），空帧必须被
     * handle 跳过而非炸流——修复前该场景表现为：token 已输出、流尾报「执行失败」。
     */
    @Test
    void executeStreamReactive_usageOnlyFinalFrame_skippedWithoutError() {
        List<String> tokens = List.of("你好", "，世界");
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        // 正常 token 帧 + usage 专用空帧（streamOptions.include_usage 的末帧形态）
        when(streamSpec.chatResponse()).thenReturn(Flux.concat(
                fluxOf(tokens.toArray(new String[0])),
                Flux.just(new ChatResponse(List.of()))));

        List<String> out = agent.executeStreamReactive(new Goal("g2", "自我介绍"))
                .collectList()
                .block();

        assertEquals(tokens, out, "空 usage 末帧应被跳过，token 序列不变");
    }
}
