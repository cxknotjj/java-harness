-- ============================================================
-- V8: 修复存量库乱码注释（goal / model_provider / session 三表）
--
-- 根因：这三张表建于 spring.sql.init 时代，当时执行链路编码错误
-- （UTF-8 字节按错误字符集写入），列/表注释以乱码固化在库中；
-- 接入 Flyway 后 baseline=1 跳过 V1，不会重建已存在的表，
-- 乱码注释便一直残留。V6/V7 经 Flyway 执行的列注释均正常，
-- 证明现执行链路（Flyway UTF-8 + JDBC characterEncoding=utf8）无恙。
--
-- 处理：按 V1 权威定义重述乱码列（MODIFY 完整重述类型/约束/默认值，
-- 不改任何数据与索引）；status 列注释为纯 ASCII 未受污染，跳过不动。
-- 全新空库从 V1 建表注释本就正确，本脚本重述同义注释，幂等无损。
-- 注释文案按 V6 后的结构微调（model 引用已由 agent.model 改为
-- agent.model_provider_id）。
-- ============================================================

-- goal：目标表
ALTER TABLE `goal`
    MODIFY COLUMN id           VARCHAR(64) NOT NULL COMMENT '目标ID（goal-UUID）',
    MODIFY COLUMN objective    TEXT        NOT NULL COMMENT '目标描述',
    MODIFY COLUMN session_id   VARCHAR(64) NULL COMMENT '关联会话ID，无会话记忆为空',
    MODIFY COLUMN summary      TEXT        NULL COMMENT '执行结论/结果摘要',
    MODIFY COLUMN created_at   DATETIME    NOT NULL COMMENT '创建时间',
    MODIFY COLUMN finished_at  DATETIME    NULL COMMENT '结束时间',
    COMMENT='Agent 目标表';

-- model_provider：模型-服务商映射表（disable_thinking 为 V7 新列，注释正常不动）
ALTER TABLE `model_provider`
    MODIFY COLUMN id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    MODIFY COLUMN model       VARCHAR(64)  NOT NULL COMMENT '模型名（与 provider 组成部署模型唯一键，agent.model_provider_id 引用本表）',
    MODIFY COLUMN provider    VARCHAR(32)  NOT NULL COMMENT '服务商标识：dashscope / deepseek / ...',
    MODIFY COLUMN api_url     VARCHAR(255) NULL COMMENT '服务商端点 base-url（如 https://dashscope.aliyuncs.com/compatible-mode）',
    MODIFY COLUMN status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    MODIFY COLUMN created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN updated_at  DATETIME     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    COMMENT='模型-服务商映射表';

-- session：会话表
ALTER TABLE `session`
    MODIFY COLUMN session_id    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    MODIFY COLUMN agent_id      INT          NOT NULL COMMENT '关联的 Agent（agent.agent_id）',
    MODIFY COLUMN session_name  VARCHAR(300) NOT NULL COMMENT '会话名称',
    MODIFY COLUMN creator       VARCHAR(30)  NOT NULL COMMENT '创建者',
    MODIFY COLUMN last_question VARCHAR(200) NULL COMMENT '最近一次提问',
    MODIFY COLUMN is_delete     TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除标记：0-正常 1-已删除',
    COMMENT='会话表';
