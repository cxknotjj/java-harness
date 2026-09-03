package com.dark.javaHarness.config.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * ThinkingSwitchChatModel 单测（思考开关的模型层注入）：
 * - 运行时 OpenAi 选项未声明 enable_thinking → 注入 false，其余字段保留
 * - 运行时选项已显式声明 enable_thinking → 原样透传（显式声明优先）
 * - 阻塞 call 与流式 stream 两通道同源生效
 * - 非 OpenAI 选项 / 无选项请求原样放行（不误伤）
 */
class ThinkingSwitchChatModelTest {

    /** 捕获入参 Prompt 的最小委托桩（call/stream 各自捕获） */
    static final class StubDelegate implements ChatModel {
        Prompt callCaptured;
        Prompt streamCaptured;
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));

        @Override
        public ChatResponse call(Prompt prompt) {
            this.callCaptured = prompt;
            return response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.streamCaptured = prompt;
            return Flux.empty();
        }

        @Override
        public OpenAiChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().build();
        }
    }

    private static OpenAiChatOptions runtimeOptions(Map<String, Object> extraBody) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("qwen3.7-flash");
        if (extraBody != null) {
            builder.extraBody(extraBody);
        }
        return builder.build();
    }

    /** 未声明 enable_thinking → 注入 false，其余运行时字段保留 */
    @Test
    void injectsIntoRuntimeOptions() {
        StubDelegate delegate = new StubDelegate();
        ThinkingSwitchChatModel model = new ThinkingSwitchChatModel(delegate);

        model.call(new Prompt("hi", runtimeOptions(null)));

        OpenAiChatOptions opts = (OpenAiChatOptions) delegate.callCaptured.getOptions();
        assertFalse((Boolean) opts.getExtraBody().get("enable_thinking"), "应注入 enable_thinking:false");
        assertEquals("qwen3.7-flash", opts.getModel(), "其余运行时字段应保留");
    }

    /** 已有 extraBody 的其他键 → 合并注入而非覆盖丢失 */
    @Test
    void mergesIntoExistingExtraBody() {
        StubDelegate delegate = new StubDelegate();
        ThinkingSwitchChatModel model = new ThinkingSwitchChatModel(delegate);

        model.call(new Prompt("hi", runtimeOptions(Map.of("top_k", 40))));

        OpenAiChatOptions opts = (OpenAiChatOptions) delegate.callCaptured.getOptions();
        assertEquals(40, opts.getExtraBody().get("top_k"), "既有 extraBody 键应保留");
        assertFalse((Boolean) opts.getExtraBody().get("enable_thinking"), "并注入 enable_thinking:false");
    }

    /** 已显式声明 enable_thinking → 原样透传不覆盖 */
    @Test
    void preservesExplicitDeclaration() {
        StubDelegate delegate = new StubDelegate();
        ThinkingSwitchChatModel model = new ThinkingSwitchChatModel(delegate);
        Prompt explicit = new Prompt("hi", runtimeOptions(Map.of("enable_thinking", true)));

        model.call(explicit);

        assertSame(explicit, delegate.callCaptured, "显式声明的请求应原样透传（同一实例）");
    }

    /** 流式通道同源生效 */
    @Test
    void streamingChannelInjectsToo() {
        StubDelegate delegate = new StubDelegate();
        ThinkingSwitchChatModel model = new ThinkingSwitchChatModel(delegate);

        model.stream(new Prompt("hi", runtimeOptions(null))).blockLast();

        OpenAiChatOptions opts = (OpenAiChatOptions) delegate.streamCaptured.getOptions();
        assertFalse((Boolean) opts.getExtraBody().get("enable_thinking"), "流式通道同样注入");
    }

    /** 非 OpenAI 选项（无 options 请求）原样放行 */
    @Test
    void passesThroughNonOpenAiOptions() {
        StubDelegate delegate = new StubDelegate();
        ThinkingSwitchChatModel model = new ThinkingSwitchChatModel(delegate);
        Prompt bare = new Prompt("hi");

        model.call(bare);

        assertNull(delegate.callCaptured.getOptions(), "无选项请求不应被改写");
    }
}
