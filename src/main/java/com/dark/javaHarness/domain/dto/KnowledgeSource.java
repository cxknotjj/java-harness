package com.dark.javaHarness.domain.dto;

/**
 * 知识库检索命中的出处引用（SseMeta.sources / ChatResponse.sources 元素，
 * CLI 回合尾「来源」注释与回答内联【出处N】共用此编号语义）。
 */
public record KnowledgeSource(String docName, String title, double score) {
}
