package com.dark.javaHarness.core.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * session 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {
}
