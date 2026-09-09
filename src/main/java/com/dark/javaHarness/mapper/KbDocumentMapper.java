package com.dark.javaHarness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * kb_document 表 Mapper（MyBatis-Plus 提供 CRUD）。
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocumentEntity> {
}
