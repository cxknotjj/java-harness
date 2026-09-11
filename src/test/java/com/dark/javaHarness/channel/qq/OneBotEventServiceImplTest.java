package com.dark.javaHarness.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import com.dark.javaHarness.channel.qq.dto.OneBotEvent;
import com.dark.javaHarness.domain.dto.ChatRequest;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.dark.javaHarness.domain.entity.OneBotSessionBinding;
import com.dark.javaHarness.mapper.OneBotSessionBindingMapper;
import com.dark.javaHarness.service.ChatService;
import com.dark.javaHarness.service.SessionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OneBotEventServiceImpl 单测：自消息过滤、群聊 @ 触发（message 数组为准）、
 * 限频丢弃、会话绑定复用/新建、FAILED 静默、超长分段。
 */
@ExtendWith(MockitoExtension.class)
class OneBotEventServiceImplTest {

    private static final long SELF_ID = 3896564418L;
    private static final long UID = 10001L;
    private static final long GID = 88L;

    @Mock
    private ChatService chatService;
    @Mock
    private SessionService sessionService;
    @Mock
    private OneBotSessionBindingMapper bindingMapper;
    @Mock
    private NapCatApiClient apiClient;

    private NapCatProperties props;
    private OneBotEventServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new NapCatProperties();
        props.setEnabled(true);
        props.setSelfId(String.valueOf(SELF_ID));
        props.getGroupTrigger().setMode("at");
        props.getGroupTrigger().setPrefix("/ai");
        props.getRateLimit().setPerUserSeconds(0);
        props.getReply().setMaxLength(3000);
        service = new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
    }

    private static OneBotEvent privateMsg(long messageId, String text) {
        return privateMsg(UID, messageId, text);
    }

    private static OneBotEvent privateMsg(long uid, long messageId, String text) {
        return new OneBotEvent(1700000000L, SELF_ID, "message", "private", uid, null, messageId, text,
                List.of(new MessageSegment("text", Map.of("text", text))),
                new OneBotEvent.Sender(uid, "tester", null));
    }

    private static OneBotEvent groupMsg(long messageId, boolean withAt, String text) {
        List<MessageSegment> segments = new java.util.ArrayList<>();
        if (withAt) {
            segments.add(new MessageSegment("at", Map.of("qq", String.valueOf(SELF_ID))));
        }
        segments.add(new MessageSegment("text", Map.of("text", text)));
        return new OneBotEvent(1700000000L, SELF_ID, "message", "group", UID, GID, messageId, text,
                segments, new OneBotEvent.Sender(UID, "tester", "群名片"));
    }

    private static OneBotSessionBinding binding(long id, String key, long sessionId) {
        OneBotSessionBinding row = new OneBotSessionBinding();
        row.setId(id);
        row.setSessionKey(key);
        row.setSessionId(sessionId);
        row.setQqUserId(String.valueOf(UID));
        return row;
    }

    private void stubChatSuccess(String reply) {
        when(chatService.chat(any()))
                .thenReturn(ChatResponse.success("1", false, null, reply));
    }

    @Test
    void handle_selfMessageIgnored() {
        OneBotEvent self = new OneBotEvent(1700000000L, SELF_ID, "message", "private",
                SELF_ID, null, 1L, "hi", List.of(new MessageSegment("text", Map.of("text", "hi"))), null);
        service.handle(self);
        verifyNoInteractions(chatService, sessionService, bindingMapper, apiClient);
    }

    @Test
    void handle_privateChat_reusesBinding() {
        when(bindingMapper.selectOne(any())).thenReturn(binding(1L, "qq:private:" + UID, 5L));
        stubChatSuccess("你好");
        service.handle(privateMsg(10L, "你好，介绍一下自己"));
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatService).chat(captor.capture());
        assertEquals("你好，介绍一下自己", captor.getValue().message());
        assertEquals("5", captor.getValue().sessionId());
        verify(bindingMapper, never()).insert(any(OneBotSessionBinding.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> segCaptor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendPrivateMsg(eq(UID), segCaptor.capture());
        assertEquals("你好", segCaptor.getValue().get(0).text());
    }

    @Test
    void handle_privateChat_createsBindingOnFirstSeen() {
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(sessionService.createSession("qq:" + UID, "在吗")).thenReturn("7");
        stubChatSuccess("在");
        service.handle(privateMsg(11L, "在吗"));
        ArgumentCaptor<OneBotSessionBinding> rowCaptor = ArgumentCaptor.forClass(OneBotSessionBinding.class);
        verify(bindingMapper).insert(rowCaptor.capture());
        assertEquals(7L, rowCaptor.getValue().getSessionId());
        assertEquals("qq:private:" + UID, rowCaptor.getValue().getSessionKey());
        assertEquals(String.valueOf(UID), rowCaptor.getValue().getQqUserId());
        verify(chatService).chat(new ChatRequest("在吗", "7", null));
    }

    @Test
    void handle_groupChat_requiresAtSegment() {
        service.handle(groupMsg(20L, false, "没人理我"));
        verifyNoInteractions(chatService, apiClient);
    }

    @Test
    void handle_groupChat_repliesWithQuote() {
        props.getRateLimit().setPerUserSeconds(0);
        when(bindingMapper.selectOne(any())).thenReturn(binding(2L, "qq:group:" + GID + ":" + UID, 9L));
        stubChatSuccess("群答");
        service.handle(groupMsg(21L, true, "群友提问"));
        verify(chatService).chat(new ChatRequest("群友提问", "9", null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> segCaptor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendGroupMsg(eq(GID), segCaptor.capture());
        List<MessageSegment> segments = segCaptor.getValue();
        assertEquals("reply", segments.get(0).type(), "群聊首条带 reply 段引用原消息");
        assertEquals(21L, segments.get(0).messageId());
        assertEquals("群答", segments.get(1).text());
    }

    @Test
    void handle_groupChat_rateLimitedSecondMessageDropped() {
        // 限频间隔在服务构造时快照：必须先设间隔再重建 service（setUp 默认 0 = 不限频）
        props.getRateLimit().setPerUserSeconds(10);
        service = new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
        when(bindingMapper.selectOne(any())).thenReturn(binding(3L, "qq:group:" + GID + ":" + UID, 5L));
        stubChatSuccess("回");
        service.handle(groupMsg(30L, true, "第一问"));
        service.handle(groupMsg(31L, true, "第二问"));
        verify(chatService, times(1)).chat(any());
    }

    @Test
    void handle_privateChat_rateLimitedSecondMessageDropped() {
        // 私聊同样限频（防陌生人刷 LLM token），与群聊同一间隔口径
        props.getRateLimit().setPerUserSeconds(10);
        service = new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
        when(bindingMapper.selectOne(any())).thenReturn(binding(31L, "qq:private:" + UID, 5L));
        stubChatSuccess("回");
        service.handle(privateMsg(300L, "第一问"));
        service.handle(privateMsg(301L, "第二问"));
        verify(chatService, times(1)).chat(any());
    }

    @Test
    void handle_privateChat_allowlistBlocksStranger() {
        // 白名单非空时名单外私聊直接丢弃（名单内用户不受影响）
        props.setPrivateAllowUsers("999");
        service = new OneBotEventServiceImpl(chatService, sessionService, bindingMapper, apiClient, props);
        when(bindingMapper.selectOne(any())).thenReturn(binding(32L, "qq:private:999", 5L));
        stubChatSuccess("主人好");
        service.handle(privateMsg(UID, 310L, "陌生人私聊"));
        service.handle(privateMsg(999L, 311L, "白名单用户私聊"));
        verify(chatService, times(1)).chat(any());
        verify(apiClient, never()).sendPrivateMsg(eq(UID), any());
    }

    @Test
    void handle_resetCommand_clearsBindingAndRepliesWithoutChat() {
        service.handle(privateMsg(320L, "/reset"));
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OneBotSessionBinding>> wc =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(bindingMapper).delete(wc.capture());
        verify(chatService, never()).chat(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSegment>> segCaptor = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendPrivateMsg(eq(UID), segCaptor.capture());
        assertTrue(segCaptor.getValue().get(0).text().contains("新对话"));
    }

    @Test
    void handle_chatTimeout_givesUpSilently() {
        // 超时放弃回复且静默：防 LLM 卡死占满处理线程池
        props.setChatTimeoutSeconds(1);
        when(bindingMapper.selectOne(any())).thenReturn(binding(34L, "qq:private:" + UID, 5L));
        when(chatService.chat(any())).thenAnswer(inv -> {
            Thread.sleep(2500);
            return ChatResponse.success("1", false, null, "迟到的回复");
        });
        long start = System.currentTimeMillis();
        service.handle(privateMsg(330L, "慢问题"));
        assertTrue(System.currentTimeMillis() - start < 2000, "超时后应尽快返回而不是等聊天结束");
        verifyNoInteractions(apiClient);
    }

    @Test
    void handle_chatFailed_staysSilent() {
        when(bindingMapper.selectOne(any())).thenReturn(binding(4L, "qq:private:" + UID, 5L));
        when(chatService.chat(any()))
                .thenReturn(new ChatResponse("5", false, null, "FAILED", null, "boom", null, null));
        service.handle(privateMsg(40L, "触发失败"));
        verifyNoInteractions(apiClient);
    }

    @Test
    void handle_longReplySplitByParagraphBoundary() {
        props.getReply().setMaxLength(5);
        when(bindingMapper.selectOne(any())).thenReturn(binding(5L, "qq:private:" + UID, 5L));
        stubChatSuccess("aaa\n\nbbb");
        service.handle(privateMsg(50L, "长回复"));
        verify(apiClient, times(2)).sendPrivateMsg(eq(UID), any());
    }

    @Test
    void splitReply_hardCutsOversizedParagraph() {
        List<String> parts = OneBotEventServiceImpl.splitReply("12345678", 5);
        assertEquals(List.of("12345", "678"), parts);
    }

    @Test
    void splitReply_noLimitWhenZero() {
        assertEquals(List.of("一整段不切"), OneBotEventServiceImpl.splitReply("一整段不切", 0));
        assertTrue(OneBotEventServiceImpl.splitReply(null, 100).get(0).isEmpty());
    }

    @Test
    void handle_handlesServiceExceptionWithoutThrowing() {
        when(bindingMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));
        service.handle(privateMsg(60L, "任何异常都不外抛"));
        verify(chatService, never()).chat(any());
    }
}
