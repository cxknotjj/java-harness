-- V13 - 知识库多库化：kb_document 台账新增 kb 列，记录文档所属知识库
-- （knowledge/ 一级子目录名；根目录散文件为 default），与向量 chunk 的
-- metadata.kb 冗余一致。
-- 存量行 kb 置 NULL（多库化前摄取，向量 chunk 无 kb 元数据）：sync 时
-- kb 与文件现状不一致即触发重摄取，自动补齐 chunk metadata.kb（自愈，
-- 无需手工清库）。
ALTER TABLE `kb_document`
    ADD COLUMN `kb` VARCHAR(64) NULL
    COMMENT '所属知识库（knowledge/ 一级子目录名，根目录文档为 default）；NULL=多库化前存量行，下次 sync 重摄取补齐';
