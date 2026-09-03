-- ============================================================
-- V7: model_provider 增加"关闭思考"开关
--
-- 背景：qwen3 系思考型模型（qwen3.7-flash-2026-07-15 等）在非流式调用下
-- 单轮推理可达数分钟、content 常为空（输出全在 reasoning_content）、
-- 长思考响应还会被截断（实测 Jackson end-of-input）。
-- dashscope 兼容模式支持请求级 enable_thinking:false 关闭思考（实测
-- 同任务从 5m53s 降至 1s 且正常吐出 tool_calls）。
--
-- disable_thinking=1 时，ChatClientFactory 为该端点的阻塞调用注入
-- enable_thinking:false；默认 0 保持模型自身默认行为，其他供应商不受影响。
-- ============================================================

ALTER TABLE `model_provider`
    ADD COLUMN disable_thinking TINYINT NOT NULL DEFAULT 0
        COMMENT '是否关闭思考：1-注入 enable_thinking:false（dashscope 思考模型）0-模型默认' AFTER api_url;

-- 存量 dashscope 行（qwen3 系思考模型）默认关闭思考
UPDATE `model_provider` SET disable_thinking = 1 WHERE provider = 'dashscope';
