package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.SessionMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * session_messages 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessageEntity> {
}