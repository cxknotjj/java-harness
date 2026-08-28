-- V2 - goal 表按 session_id 查询加索引（会话维度检索 Goal 时避免全表扫描）
ALTER TABLE goal ADD INDEX idx_goal_session (session_id);
