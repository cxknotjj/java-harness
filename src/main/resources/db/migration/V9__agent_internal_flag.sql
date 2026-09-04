-- ============================================================
-- V9: Agent 内部角色标志（is_internal）
--
-- 背景：AgentRegistry 表驱动自动注册上线后，agent 表每个对话 Agent 行
-- （is_internal=0）都将被注册为可路由的 GeneralAssistantAgent 实例。
-- lead（拆解器）与 aggregator（聚合器）仅作为 multi-agent 编排链路的
-- 内部环节角色，不直接暴露给对话路由；multi-agent 行本身由编排 bean
-- （MultiAgentGraphAgent）承载，同样不由表行构造实例。
--
-- 处理：加 is_internal 列（0=对话 Agent，1=编排内部角色），
-- 存量内部角色行置 1；排除逻辑完全由该字段驱动，
-- 新增内部角色只需置 1，无需改代码。
-- ============================================================

ALTER TABLE `agent`
    ADD COLUMN is_internal TINYINT NOT NULL DEFAULT 0 COMMENT '内部角色标志：1=编排内部角色，不作为对话Agent注册' AFTER status;

UPDATE `agent` SET is_internal = 1 WHERE agent_name IN ('multi-agent', 'lead', 'aggregator');
