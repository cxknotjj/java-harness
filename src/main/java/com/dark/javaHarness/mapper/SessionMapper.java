package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.SessionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * session 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface SessionMapper extends BaseMapper<SessionEntity> {
}