-- ============================================================
-- V12 - Agent 知识库绑定（RAG 多库隔离）：agent 表新增 knowledge 列，
-- 每 agent 行声明自己可检索的知识库（kb 标识，逗号分隔），改库即生效。
--
-- kb 标识语义（KnowledgeRetriever/KnowledgeServiceImpl 解析）：
--   知识库 = knowledge/ 目录下的一级子目录名（如 java / frontend）；
--   根目录散文件属于公共库 default。
--   NULL/空白 = 不限（检索全部知识，兼容多库化之前的行为）；
--   绑定后（如 'java,frontend'）只查绑定的库，防止 agent 间读串知识。
-- ============================================================

ALTER TABLE `agent`
    ADD COLUMN `knowledge` VARCHAR(255) NULL
    COMMENT '绑定的知识库（逗号分隔 kb 标识=knowledge/ 一级子目录名；NULL/空白不限，检索全部知识）'
    AFTER `tools`;
