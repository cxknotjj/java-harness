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

    /** 文档名（知识目录文件名，唯一摄取键） */
    private String docName;

    /** 标题（front-matter title 或首个 # 标题行 / 文件名），出处展示用 */
    private String title;

    /** 文件最后修改时间（epoch 毫秒，增量摄取判据） */
    private Long mtime;

    /** 向量库 chunk 数（旧向量删除依据：docName#0..count-1） */
    private Integer chunkCount;

    /** 状态：1-已摄取有效 / 0-下线（保留扩展） */
    private Integer status;

    /** 首次摄取时间 */
    private LocalDateTime createdAt;

    /** 最近摄取时间 */
    private LocalDateTime updatedAt;
}
