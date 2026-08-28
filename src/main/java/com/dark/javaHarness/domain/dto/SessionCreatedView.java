package com.dark.javaHarness.domain.dto;

/**
 * 新建会话结果视图：返回新建会话的 ID 与占位名称。
 * 会话名首条提问后由业务流程更新（last_question），名称本身保持创建时的占位值。
 */
public record SessionCreatedView(String sessionId, String sessionName) {
}
