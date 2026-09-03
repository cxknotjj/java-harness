package com.dark.javaHarness.config.agent;

import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * 思考开关模型包装器：给思考型端点（model_provider.disable_thinking=1）的每次请求
 * 注入 {@code enable_thinking:false}（dashscope qwen3 系供应商私有参数）。
 *
 * <p>为什么是包装器而不是 defaultOptions：经实测（ExtraBodyMergeTest 阶段），
 * OpenAiChatModel.createRequest 对 extraBody 的合并仅在运行时选项**已携带** extraBody
 * 时触发，defaultOptions.extraBody 在"每次请求都带运行时选项"的调用模式下传不进请求体。
 * 因此在模型层收口——ChatModel 公开接口（1.1.4 起已含流式能力），阻塞与流式天然同源。
 *
 * <p>请求体序列化由框架完成：OpenAiChatOptions.extraBody 经 @JsonAnyGetter 顶层
 * 展开进 JSON，无需任何 HTTP 层改写。
 *
 * <p>显式声明优先：运行时选项已携带 enable_thinking 时原样透传，不做覆盖。
 */
public final class ThinkingSwitchChatModel implements ChatModel {

    private final ChatModel delegate;

    public ThinkingSwitchChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(inject(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(inject(prompt));
    }

    @Override
    public OpenAiChatOptions getDefaultOptions() {
        return (OpenAiChatOptions) delegate.getDefaultOptions();
    }

    /** 运行时选项注入 enable_thinking:false；已显式声明时透传，非 OpenAI 选项原样放行 */
    private Prompt inject(Prompt prompt) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions runtime)) {
            return prompt;
        }
        Map<String, Object> extraBody = runtime.getExtraBody() == null
                ? new HashMap<>() : new HashMap<>(runtime.getExtraBody());
        if (extraBody.containsKey("enable_thinking")) {
            return prompt;
        }
        extraBody.put("enable_thinking", false);
        OpenAiChatOptions injected = runtime.copy();
        injected.setExtraBody(extraBody);
        return new Prompt(prompt.getInstructions(), injected);
    }
}
