package com.dark.javaHarness.channel.qq;

import com.dark.javaHarness.channel.qq.dto.ApiResult;
import com.dark.javaHarness.channel.qq.dto.MessageSegment;
import java.util.List;

/**
 * NapCat HTTP API 客户端（OneBot 11 下行接口，最小子集）。
 * 网络失败重试 1 次；业务失败（retcode != 0）不重试；一切失败只记日志不抛异常
 * ——消息送达是尽力而为，不打断主流程。
 */
public interface NapCatApiClient {

    /** 登录信息（启动自检用） */
    ApiResult getLoginInfo();

    /** 私聊发送（segments 通常为单个 text 段） */
    void sendPrivateMsg(long userId, List<MessageSegment> segments);

    /** 群聊发送（首段可带 reply 段引用原消息） */
    void sendGroupMsg(long groupId, List<MessageSegment> segments);
}
