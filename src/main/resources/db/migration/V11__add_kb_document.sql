-- V11 - 知识库文档摄取记录表：本地 knowledge/ 目录文档的增量摄取台账（RAG 知识库）。
-- 数据来源：KnowledgeServiceImpl.sync()——扫描→与 kb_document 按 name+mtime 对比→
-- 变更文档删旧 chunk（确定性 id=docName#idx，存 pgvector，不在此库）→嵌入入库→upsert 本表。
-- 向量数据本体在 PostgreSQL pgvector 的 vector_store 表（由 Spring AI 自建），MySQL 只存台账。
CREATE TABLE kb_document (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    doc_name   VARCHAR(255) NOT NULL COMMENT '文档文件名（相对知识目录，唯一键）',
    title      VARCHAR(255) NULL COMMENT '文档标题（front-matter title 或首个标题行，缺省用文件名）',
    mtime      BIGINT       NOT NULL COMMENT '文件最后修改时间（epoch 毫秒，增量比对依据）',
    chunk_count INT         NOT NULL DEFAULT 0 COMMENT '当前向量库中的 chunk 数（删除旧 chunk 用）',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-已摄取（保留字段，预留删除/失效态）',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次摄取时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近摄取时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_name (doc_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档摄取台账';
