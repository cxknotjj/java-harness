package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.service.AgentService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * AgentChatCaller 未知工具幻觉容错单测：
 * - 模型发起不存在的工具调用（No ToolCallback found）→ 去掉工具列表重试一次，返回文本结果
 * - 其他异常照常抛出（交由上层重试/降级）
 * - disableTools=true 时不注入任何工具（request 级 toolCallbacks/tools 均不调用）
 */
@ExtendWith(MockitoExtension.class)
class AgentChatCallerTest {

    @Mock
    private ChatClientRegistry clientRegistry;
    @Mock
    private AgentService agentService;
    @Mock
    private ChatClient client;
    @Mock
    private ChatClient.ChatClientRequestSpec spec;
    @Mock
    private ChatClient.CallResponseSpec callSpec;

    private AgentChatCaller caller;

    @BeforeEach
    void setUp() {
        caller = new AgentChatCaller(clientRegistry, agentService, null, null);
        // lenient：invokeOnce_disableTools 用例直接调 invokeAndRecord，不查表
        org.mockito.Mockito.lenient().when(agentService.getAgentConfig("researcher"))
                .thenReturn(Optional.of(new AgentConfig(1L, "m1", "系统提示词")));
        when(clientRegistry.get(1L)).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any(OpenAiChatOptions.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
    }

    /** 构造一次成功响应的 chatResponse 链 */
    private ChatResponse okResponse(String text) {
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(msg.getText()).thenReturn(text);
        org.springframework.ai.chat.model.Generation generation =
                mock(org.springframework.ai.chat.model.Generation.class);
        when(generation.getOutput()).thenReturn(msg);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResult()).thenReturn(generation);
        when(resp.getMetadata()).thenReturn(null);
        return resp;
    }

    @Test
    void call_unknownToolHallucination_retriesOnceWithoutTools() {
        ChatResponse ok = okResponse("调研完成：4399 公司……");
        when(callSpec.chatResponse())
                .thenThrow(new IllegalStateException("No ToolCallback found for tool name: researcher"))
                .thenReturn(ok);

        String out = caller.call("s1", "researcher", "兜底提示", "任务内容", null);

        assertEquals("调研完成：4399 公司……", out, "幻觉工具调用应降级为无工具重试并返回文本结果");
        // 两次调用（原样 + 去工具）
        verify(spec, never()).toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class));
    }

    @Test
    void call_otherError_propagates() {
        when(callSpec.chatResponse()).thenThrow(new IllegalStateException("无关异常"));

        assertThrows(IllegalStateException.class,
                () -> caller.call("s1", "researcher", "兜底提示", "任务内容", null));
    }

    @Test
    void invokeOnce_disableTools_skipsAllToolInjection() {
        ChatResponse ok = okResponse("ok");
        when(callSpec.chatResponse()).thenReturn(ok);

        String out = caller.invokeAndRecord(
                new AgentConfig(1L, "m1", "系统提示词"), "s1", "researcher",
                "兜底提示", "任务内容", null, true, "m1", System.currentTimeMillis());

        assertEquals("ok", out);
        verify(spec, never()).toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class));
        verify(spec, never()).tools(any(Object[].class));
    }
}
