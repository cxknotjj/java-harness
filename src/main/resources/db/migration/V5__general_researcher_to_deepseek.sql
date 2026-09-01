-- 模型分流：general（日常聊天，调用量最大）与 researcher（调研子任务，编排中最耗 token）
-- 切到 deepseek-v4-flash（独立配额），节省 DashScope 免费额度。
-- lead / aggregator / coder / analyst 质量敏感度最高，保留 qwen3.7-flash。
UPDATE agent
SET model = 'deepseek-v4-flash',
    updated_at = NOW()
WHERE agent_name IN ('general', 'researcher');
