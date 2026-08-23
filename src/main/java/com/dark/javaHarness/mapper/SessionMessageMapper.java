package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.entity.SessionMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * session_messages 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessage> {
}