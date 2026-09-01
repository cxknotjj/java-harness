-- ============================================================
-- V6: 模型路由结构重构（"部署模型"id 键，不做旧数据兼容）
--
-- 旧结构的缺陷：model_provider.model 全局唯一 + agent.model 按名字符串引用。
-- 两家供应商有同名模型（如腾讯代理的 Deepseek-v4-flash 与 DeepSeek 官方的
-- deepseek-v4-flash）时无法共存，且 agent 无法表达"用哪家的"。
--
-- 新结构：
--   model_provider 每行 = 一个可调用端点（"部署模型"），
--     唯一键改为 (provider, model)——同供应商内模型名唯一，跨供应商允许同名；
--   agent.model_provider_id 外键式引用 model_provider.id，
--     精确表达"该 Agent 用哪个端点的哪个模型"。
-- 现有数据就地改造：先按旧 model 名回填 id 绑定，再删旧列（不做向后兼容）。
-- ============================================================

-- 1. model_provider：唯一键 uk_model → uk_provider_model（跨供应商允许同名）
--    此时 model 仍全局唯一，改为复合键不会产生冲突
ALTER TABLE `model_provider`
    DROP KEY uk_model,
    ADD UNIQUE KEY uk_provider_model (provider, model);

-- 2. agent：model 字符串列 → model_provider_id（精确绑定端点）
--    2.1 先加新列
ALTER TABLE `agent`
    ADD COLUMN model_provider_id BIGINT NULL COMMENT '绑定的部署模型（model_provider.id）' AFTER description;

--    2.2 按旧 model 名回填（旧结构 model_provider.model 全局唯一，JOIN 结果无歧义；
--        若 agent.model 在 model_provider 中无对应行则保持 NULL，由默认客户端兜底）
UPDATE `agent` a
    JOIN `model_provider` m ON m.model = a.model
SET a.model_provider_id = m.id;

--    2.3 再删旧列
ALTER TABLE `agent` DROP COLUMN model;
