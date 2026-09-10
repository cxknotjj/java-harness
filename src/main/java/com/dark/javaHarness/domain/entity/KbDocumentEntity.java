package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * RAG 知识库文档摄取记录，对应表 kb_document（MySQL 主库）。
 * 由 KnowledgeService.sync 增量维护：name + mtime 未变的文档跳过，
 * 变更文档先删旧 chunk（chunk_count 条）再重新嵌入入库。
 */
@Data
@TableName("kb_document")
public class KbDocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档名（相对知识目录路径，子目录文档带 kb 前缀如 java/spring.md，唯一摄取键） */
    private String docName;

    /** 所属知识库（knowledge/ 一级子目录名，根目录文档为 default；与向量 chunk metadata.kb 一致） */
    private String kb;

    /** 标题（front-matter title 或首个 # 标题行 / 文件名），出处展示用 */
    private String title;

    /** 文件最后修改时间（epoch 毫秒，增量摄取判据） */
    private Long mtime;

    /** 向量库 chunk 数（旧向量删除依据：docName#0..count-1） */
    private Integer chunkCount;

    /** 状态：1-已摄取有效 / 0-下线或摄取失败（失败行下次 sync 强制重试补齐向量） */
    private Integer status;

    /** 首次摄取时间 */
    private LocalDateTime createdAt;

    /** 最近摄取时间 */
    private LocalDateTime updatedAt;
}
