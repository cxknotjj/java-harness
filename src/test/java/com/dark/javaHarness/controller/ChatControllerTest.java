package com.dark.javaHarness.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.service.ChatService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/**
 * ChatController 流式接口 HTTP 输出层单测：
 * 验证 POST /api/chat/stream 返回的 Flux（text/plain）每个元素末尾带 {@code \n}，
 * 当串行化后每个元素独立成行，CLI 按行解析可正常工作。
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;

    @InjectMocks
    private ChatController controller;

    @Test
    void stream_eachElementEndsWithNewline() {
        // streamReactive 产出的元素本身不含换行（event: token+data 块 / event: meta+data 块）
        when(chatService.streamReactive(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Flux.just(
                        "event: token\ndata: 你好",
                        "event: token\ndata: [DONE]",
                        "event: meta\ndata: {\"sessionId\":\"1\",\"newSession\":true}"));

        ChatRequest req = new ChatRequest("你好", "1", null);
        List<String> lines = controller.stream(req).collectList().block();

        assertTrue(lines != null && !lines.isEmpty(), "流应产出元素");
        lines.forEach(l -> assertTrue(l.endsWith("\n"),
                "每个元素都应独立成行（末尾换行），但得到: " + l));

        // 串行化拼接后每个事件各自成行
        String body = String.join("", lines);
        assertTrue(body.contains("data: 你好\n"), "data 行应后跟换行");
        assertTrue(body.contains("data: [DONE]\n"), "[DONE] 行应后跟换行");
        assertTrue(body.contains("event: meta\n"), "event: meta 行应后跟换行");
        assertTrue(body.contains("\"newSession\":true}\n"), "meta data 行应后跟换行");
        assertFalse(body.contains("你好data:"), "相邻元素不得黏连");
    }

    @Test
    void stream_preservesElementOrder() {
        when(chatService.streamReactive(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Flux.just("event: token\ndata: a", "event: token\ndata: b",
                        "event: token\ndata: [DONE]", "event: meta\ndata: {}"));

        List<String> lines = controller.stream(new ChatRequest("x", "1", null))
                .map(String::trim)
                .collectList()
                .block();

        assertEquals(List.of("event: token\ndata: a", "event: token\ndata: b",
                "event: token\ndata: [DONE]", "event: meta\ndata: {}"), lines,
                "元素应保持原有顺序且逐一成行");
    }
}