package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.ToolCallLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * tool_call_log 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface ToolCallLogMapper extends BaseMapper<ToolCallLogEntity> {
}
