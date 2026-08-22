package com.dark.javaHarness.core.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话实体，对应表 session。
 * 一个会话对应一次与 Agent 的连续对话；软删除由 is_delete 标记。
 */
@Data
@TableName("session")
public class Session {

    @TableId(type = IdType.AUTO)
    private Long sessionId;

    /** 关联的 Agent（预留：当前 Agent 未编号，暂以固定值登记） */
    private Integer agentId;

    /** 会话名称（默认取首条提问截断） */
    private String sessionName;

    /** 创建者 */
    private String creator;

    /** 最近一次提问 */
    private String lastQuestion;

    /** 软删除标记：0-正常 1-已删除 */
    @TableLogic
    private Integer isDelete;
}
