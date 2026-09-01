-- qwen3.7-plus 免费额度耗尽，全量切换到 qwen3.7-flash：
-- 1) model_provider：模型映射改名（provider / api_url 不变，ChatClientRegistry 启动时按此表加载）
-- 2) agent：所有绑定 qwen3.7-plus 的 agent（general / multi-agent / lead / aggregator / 四专家）切到 qwen3.7-flash
UPDATE model_provider SET model = 'qwen3.7-flash' WHERE model = 'qwen3.7-plus';

UPDATE agent SET model = 'qwen3.7-flash' WHERE model = 'qwen3.7-plus';
