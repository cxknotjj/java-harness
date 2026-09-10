package com.dark.javaHarness.knowledge;

import com.dark.javaHarness.domain.dto.KnowledgeSource;
import com.dark.javaHarness.domain.dto.PageResult;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import java.util.List;

/**
 * RAG 知识库服务：knowledge/ 目录文档的增量摄取、管理与调试检索。
 *
 * <p>实现须满足降级语义：向量库不可用时 {@link #sync} 抛出可读异常（管理端点报清晰
 * 错误）、{@link #search} 返回空表——绝不影响应用启动与聊天主链路。
 */
public interface KnowledgeService {

    /**
     * 增量摄取：扫描知识目录 → 与 kb_document 按 name+mtime 比对 →
     * 变更文档删旧 chunk（确定性 id）→ 切分嵌入入库 → upsert 记录。
     *
     * @return 摄取摘要（扫描/更新/跳过文档数与本次入库 chunk 数）
     * @throws IllegalStateException 向量库/嵌入端点不可用（message 可直出给调用方）
     */
    KnowledgeSyncView sync();

    /** 删除指定文档：先删向量库全部 chunk，再删摄取记录；文档不存在返回 false */
    boolean delete(String docName);

    /** 摄取记录分页列表（doc_name 升序） */
    PageResult<KbDocumentEntity> list(long page, long size);

    /**
     * 检索：向量相似度 top-k（含调试与注入管线共用）。
     *
     * @param kbs 知识库过滤（agent 绑定的 kb 标识列表，对应 chunk metadata.kb）；
     *            null/空 = 不限，检索全部知识（兼容多库化之前行为）
     */
    List<KnowledgeHit> search(String query, List<String> kbs);

    /** 调试检索命中：出处 + 相似度 + chunk 原文 */
    record KnowledgeHit(String docName, String title, double score, String text) {

        /** 出处引用视图（编号由检索方按相关度顺序分配） */
        public KnowledgeSource toSource() {
            return new KnowledgeSource(docName, title, score);
        }
    }
}
