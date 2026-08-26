package com.dark.javaHarness.domain.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.domain.entity.SessionEntity;
import java.util.List;

/**
 * 会话分页查询响应（GET /api/harness/sessions）。
 * 携带分页元数据（page/size/total/pages）与当前页的会话列表。
 * 单条会话由嵌套的 {@link Item} 表示。
 */
public record SessionPageView(
        long page,
        long size,
        long total,
        long pages,
        List<Item> sessions) {

    /** 单条会话响应项 */
    public record Item(String id, String name, String creator, String lastQuestion) {

        /** 由会话实体转换 */
        public static Item from(SessionEntity e) {
            return new Item(
                    e.getSessionId() == null ? null : String.valueOf(e.getSessionId()),
                    e.getSessionName(),
                    e.getCreator(),
                    e.getLastQuestion());
        }
    }

    /** 由 MyBatis-Plus 分页结果转换 */
    public static SessionPageView from(Page<SessionEntity> p) {
        return new SessionPageView(
                p.getCurrent(),
                p.getSize(),
                p.getTotal(),
                p.getPages(),
                p.getRecords().stream().map(Item::from).toList());
    }
}