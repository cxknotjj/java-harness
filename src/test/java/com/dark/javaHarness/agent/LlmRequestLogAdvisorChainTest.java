package com.dark.javaHarness.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dark.javaHarness.config.ContextBudgetProperties;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.prompt.PromptAssembler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 端到端验证：specFactory.build 挂载的 LlmRequestLogAdvisor 是否真的进入 advisor 链
 * 并在 stream() 时执行（回归背景：生产 general 路径未打出 [llm-call] 日志）。
 */
class LlmRequestLogAdvisorChainTest {

    private static Flux<ChatResponse> fluxOf(String token) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(token)))));
    }

    @Test
    void stream_invokesRequestLogAdvisor() {
        ChatModel model = mock(ChatModel.class);
        lenient().when(model.stream(any(Prompt.class))).thenReturn(fluxOf("ok"));
        lenient().when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

        ChatClient client = ChatClient.builder(model).build();

        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.get(any())).thenReturn(client);
        PromptAssembler assembler = mock(PromptAssembler.class);
        when(assembler.assemble(anyString(), anyString(), any())).thenReturn("SYS");

        AgentRequestSpecFactory factory = new AgentRequestSpecFactory(registry, assembler,
                null, null, null, null, new ContextBudgetProperties(), null, null, List.of());

        Logger logger = (Logger) LoggerFactory.getLogger(com.dark.javaHarness.advisor.LlmRequestLogAdvisor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AgentRequestSpecFactory.Assembly assembly =
                    new AgentRequestSpecFactory.Assembly(null, false, false, false, false, 0);
            ChatClient.ChatClientRequestSpec spec = factory.build(
                    null, "s1", "general", "FALLBACK", "hello", assembly);
            spec.stream().chatResponse().collectList().block();

            String joined = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(joined.contains("[llm-call] 发起 agent=general"),
                    "advisor 未被执行！实际日志：" + joined);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
