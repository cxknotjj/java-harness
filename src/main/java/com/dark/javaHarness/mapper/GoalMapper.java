package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.entity.GoalEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * goal 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface GoalMapper extends BaseMapper<GoalEntity> {
}
