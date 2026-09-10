package com.dark.javaHarness.channel.qq;

import com.dark.javaHarness.channel.qq.dto.OneBotEvent;

/**
 * OneBot 上报事件处理：过滤（非消息/自消息）、群聊触发判断（@/前缀/全部）、
 * 限频、QQ 会话 ↔ harness 会话绑定，直调 {@code ChatService} 后经
 * {@code NapCatApiClient} 回复（超长分段）。全链路异常自捕获，失败不打断主流程。
 */
public interface OneBotEventService {

    /** 处理一条已通过幂等去重的上报事件（在 onebotExecutor 异步线程上执行） */
    void handle(OneBotEvent event);
}
