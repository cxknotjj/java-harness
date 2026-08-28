package com.dark.javaHarness.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.service.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * HarnessController 会话接口单测：
 * POST /api/harness/sessions 新建会话为纯转发——确认参数传递与返回视图组装。
 */
@ExtendWith(MockitoExtension.class)
class HarnessControllerTest {

    @Mock
    private SessionService sessionService;

    @InjectMocks
    private HarnessController controller;

    @Test
    void createSession_returnsNewSessionId() {
        when(sessionService.createSession("cli", "新会话")).thenReturn("51");

        var view = controller.createSession("新会话");

        assertEquals("51", view.sessionId(), "应返回服务端新建的会话 ID");
        assertEquals("新会话", view.sessionName(), "应回显占位会话名");
        verify(sessionService).createSession("cli", "新会话");
    }
}
