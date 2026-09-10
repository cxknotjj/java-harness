package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.McpServerLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * mcp_server_log 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface McpServerLogMapper extends BaseMapper<McpServerLogEntity> {
}
