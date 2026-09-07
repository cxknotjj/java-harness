-- ============================================================
-- V10 - Agent 工具分配数据化（prompt 动态加载·子项 2）：agent 表新增 tools 列，
-- 每 agent 行声明自己的工具面（组名或精确工具名，逗号分隔），改库即生效、无需重启。
--
-- token 语义（ToolAssignments 解析）：
--   组名：web / demo / sandbox.base / sandbox.read / sandbox.write / sandbox.browser
--   精确名：任意已注册工具名（自研 fetchUrl、沙箱 run_ipython_cell、MCP browser_click 等）
--   未识别 token 跳过并 warn；NULL/空白回退代码内置分配（legacy switch，现状语义）。
--
-- 以下种子数据与代码内置分配语义完全一致（平移为数据，非权限变更——
-- P0「工具分配最小权限化」后续只需 UPDATE 本列）。
-- ============================================================

ALTER TABLE `agent`
    ADD COLUMN `tools` TEXT NULL
    COMMENT '分配的工具（逗号分隔：组名 web/demo/sandbox.base/sandbox.read/sandbox.write/sandbox.browser 或精确工具名；NULL/空白回退代码内置分配）'
    AFTER `prompt`;

UPDATE `agent` SET `tools` = 'web, sandbox.read, sandbox.browser, browser_click, browser_type, browser_press_key, browser_scroll'
WHERE agent_name = 'researcher';

UPDATE `agent` SET `tools` = 'sandbox.base, sandbox.write'
WHERE agent_name = 'coder';

UPDATE `agent` SET `tools` = 'sandbox.base, sandbox.read'
WHERE agent_name = 'analyst';

UPDATE `agent` SET `tools` = 'web, sandbox.base, sandbox.read, sandbox.write, sandbox.browser, browser_click, browser_type, browser_press_key, browser_scroll'
WHERE agent_name = 'general';
