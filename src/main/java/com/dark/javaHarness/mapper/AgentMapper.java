package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.entity.AgentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * agent 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}
