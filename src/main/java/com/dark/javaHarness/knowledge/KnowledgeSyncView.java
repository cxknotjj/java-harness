package com.dark.javaHarness.knowledge;

/**
 * 增量摄取结果摘要（POST /api/knowledge/sync 响应体）。
 */
public record KnowledgeSyncView(int scanned, int updated, int skipped, int chunks) {
}
