package com.dark.javaHarness.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dark.javaHarness.config.KnowledgeProperties;
import com.dark.javaHarness.domain.dto.PageResult;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import com.dark.javaHarness.mapper.KbDocumentMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * RAG 知识库服务实现：增量摄取 / 删除 / 分页 / 调试检索。
 *
 * <p>增量判据：kb_document 行的 (doc_name, mtime) 与文件现状比对——mtime 未变跳过，
 * 变更文档先删旧 chunk（确定性 id = {@code docName#idx}，重摄取可精确覆盖）再重新
 * 切分嵌入入库，最后 upsert 摄取记录。
 *
 * <p>降级语义：VectorStore 经 {@link ObjectProvider} 惰性解析（启动零 PG 连接）；
 * sync 在向量库不可用时抛可读 {@link IllegalStateException}（管理端点直出），
 * search 降级返回空表——对齐 SandboxToolProvider「不影响主链路」先例。
 */
public class KnowledgeServiceImpl implements KnowledgeService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServiceImpl.class);

    /** 单 chunk 字符上限（中文 1 字符 ≈ 1 token，~700 字符兼顾检索粒度与注入预算） */
    static final int CHUNK_CHARS = 700;

    /** 相邻 chunk 重叠字符数（语义边界缓冲） */
    static final int OVERLAP_CHARS = 100;

    private final ObjectProvider<VectorStore> storeProvider;
    private final KnowledgeDocumentScanner scanner;
    private final KbDocumentMapper mapper;
    private final KnowledgeProperties props;

    public KnowledgeServiceImpl(ObjectProvider<VectorStore> storeProvider,
                                KnowledgeDocumentScanner scanner,
                                KbDocumentMapper mapper,
                                KnowledgeProperties props) {
        this.storeProvider = storeProvider;
        this.scanner = scanner;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public KnowledgeSyncView sync() {
        VectorStore store = requireStore();
        List<KnowledgeDocumentScanner.KbFile> files = scanner.scan();
        int updated = 0;
        int skipped = 0;
        int chunks = 0;
        for (KnowledgeDocumentScanner.KbFile file : files) {
            KbDocumentEntity row = rowOf(file.name());
            if (row != null && row.getStatus() != null && row.getStatus() == 1
                    && Objects.equals(row.getMtime(), file.mtime())) {
                skipped++;
                continue;
            }
            int oldChunkCount = row == null || row.getChunkCount() == null ? 0 : row.getChunkCount();
            List<String> pieces = MarkdownChunker.chunk(file.text(), CHUNK_CHARS, OVERLAP_CHARS);
            // 先删旧向量再写入（同 id 覆盖语义由删除保证；chunk 数减少时不留孤儿行）
            deleteChunks(store, file.name(), oldChunkCount);
            try {
                List<Document> docs = new ArrayList<>(pieces.size());
                for (int i = 0; i < pieces.size(); i++) {
                    docs.add(new Document(chunkId(file.name(), i), pieces.get(i), Map.of(
                            "source", file.name(),
                            "title", file.title(),
                            "chunkIndex", i)));
                }
                if (!docs.isEmpty()) {
                    store.add(docs);
                }
            } catch (Exception e) {
                throw new IllegalStateException("向量库写入失败（" + file.name() + "）: " + rootMessage(e), e);
            }
            upsertRow(row, file, pieces.size());
            updated++;
            chunks += pieces.size();
            log.info("[knowledge] 已摄取 '{}'：{} chunk（旧 {} chunk 已删）", file.name(), pieces.size(), oldChunkCount);
        }
        return new KnowledgeSyncView(files.size(), updated, skipped, chunks);
    }

    @Override
    public boolean delete(String docName) {
        if (docName == null || docName.isBlank()) {
            return false;
        }
        KbDocumentEntity row = rowOf(docName.trim());
        if (row == null) {
            return false;
        }
        VectorStore store = requireStore();
        deleteChunks(store, row.getDocName(), row.getChunkCount() == null ? 0 : row.getChunkCount());
        mapper.deleteById(row.getId());
        log.info("[knowledge] 已删除 '{}'（{} chunk）", row.getDocName(), row.getChunkCount());
        return true;
    }

    @Override
    public PageResult<KbDocumentEntity> list(long page, long size) {
        Page<KbDocumentEntity> result = mapper.selectPage(
                Page.of(Math.max(1, page), Math.min(Math.max(1, size), 200)),
                new QueryWrapper<KbDocumentEntity>().orderByAsc("doc_name"));
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<KnowledgeHit> search(String query) {
        VectorStore store = storeProvider.getIfAvailable();
        if (query == null || query.isBlank() || store == null) {
            return List.of();
        }
        try {
            return store.similaritySearch(searchRequest(query)).stream()
                    .map(doc -> new KnowledgeHit(
                            String.valueOf(doc.getMetadata().getOrDefault("source", "")),
                            String.valueOf(doc.getMetadata().getOrDefault("title", "")),
                            doc.getScore() == null ? 0 : doc.getScore(),
                            doc.getText()))
                    .toList();
        } catch (Exception e) {
            // 调试检索降级空表：PG 不可用不影响主链路（与 KnowledgeRetriever 同口径）
            log.warn("[knowledge] 检索失败（向量库不可用？）: {}", rootMessage(e));
            return List.of();
        }
    }

    /** 启动自动增量摄取（app.knowledge.auto-sync-on-startup，默认关）：失败仅告警不影响启动 */
    @Override
    public void run(ApplicationArguments args) {
        if (!props.isAutoSyncOnStartup()) {
            return;
        }
        try {
            KnowledgeSyncView view = sync();
            log.info("[knowledge] 启动自动摄取完成：扫描 {} 更新 {} 跳过 {}", view.scanned(), view.updated(), view.skipped());
        } catch (Exception e) {
            log.warn("[knowledge] 启动自动摄取失败（不影响启动）: {}", rootMessage(e));
        }
    }

    /* ---------------- 内部 ---------------- */

    /** 向量库惰性解析：未装配（依赖缺失/初始化失败）抛可读异常 */
    private VectorStore requireStore() {
        VectorStore store = storeProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("知识库向量库未装配（检查 app.knowledge.* 配置与 pgvector 依赖）");
        }
        return store;
    }

    /** 相似度检索请求（top-k 0 = 不限条数；min-score 直接映射相似度阈值） */
    SearchRequest searchRequest(String query) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query);
        if (props.getTopK() > 0) {
            builder.topK(props.getTopK());
        }
        builder.similarityThreshold(props.getMinScore());
        return builder.build();
    }

    private void deleteChunks(VectorStore store, String docName, int oldChunkCount) {
        if (oldChunkCount <= 0) {
            return;
        }
        List<String> ids = new ArrayList<>(oldChunkCount);
        for (int i = 0; i < oldChunkCount; i++) {
            ids.add(chunkId(docName, i));
        }
        try {
            store.delete(ids);
        } catch (Exception e) {
            throw new IllegalStateException("向量库旧 chunk 删除失败（" + docName + "）: " + rootMessage(e), e);
        }
    }

    /** 确定性 chunk id：重摄取时旧向量可精确删除（PgVectorStore TEXT 主键） */
    static String chunkId(String docName, int index) {
        return docName + "#" + index;
    }

    private KbDocumentEntity rowOf(String docName) {
        return mapper.selectOne(new QueryWrapper<KbDocumentEntity>().eq("doc_name", docName));
    }

    private void upsertRow(KbDocumentEntity existing, KnowledgeDocumentScanner.KbFile file, int chunkCount) {
        KbDocumentEntity row = existing != null ? existing : new KbDocumentEntity();
        row.setDocName(file.name());
        row.setTitle(file.title());
        row.setMtime(file.mtime());
        row.setChunkCount(chunkCount);
        row.setStatus(1);
        if (existing == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
