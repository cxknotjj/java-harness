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
import java.util.Set;
import java.util.stream.Collectors;
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
 * <p>增量判据：kb_document 行的 (doc_name, mtime, kb) 与文件现状比对——mtime 与 kb
 * 均未变跳过（kb 变更即重摄取：存量行 kb 为 NULL 时借下次 sync 自愈补齐 chunk
 * metadata.kb），
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
                    && Objects.equals(row.getMtime(), file.mtime())
                    && Objects.equals(row.getKb(), file.kb())) {
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
                            "chunkIndex", i,
                            "kb", file.kb())));
                }
                if (!docs.isEmpty()) {
                    addInBatches(store, docs);
                }
            } catch (Exception e) {
                // 写入失败兜底：旧 chunk 已删、新 chunk 未写全——台账行置 0（失败待重试），
                // 下次 sync 不再被「status=1 + mtime 未变」增量判据跳过，强制重摄取补齐向量；
                // 新文件（无台账行）天然重扫，无需置位
                if (row != null) {
                    row.setStatus(0);
                    mapper.updateById(row);
                }
                throw new IllegalStateException("向量库写入失败（" + file.name() + "）: " + rootMessage(e), e);
            }
            upsertRow(row, file, pieces.size());
            updated++;
            chunks += pieces.size();
            log.info("[knowledge] 已摄取 '{}'：{} chunk（旧 {} chunk 已删）", file.name(), pieces.size(), oldChunkCount);
        }
        cleanupOrphans(store, files);
        return new KnowledgeSyncView(files.size(), updated, skipped, chunks);
    }

    /**
     * 孤儿清理：台账有、本轮扫描结果中没有的行 = 文档已从磁盘删除——删除其向量
     * chunk 与台账行，避免绑定 agent 继续检索到已删内容。
     *
     * <p>安全阀：扫描结果为空时跳过清理（目录缺失/整体不可读时 scanner 返回空表，
     * 此时全量清理会误删整个库）。
     */
    private void cleanupOrphans(VectorStore store, List<KnowledgeDocumentScanner.KbFile> files) {
        if (files.isEmpty()) {
            return;
        }
        Set<String> scannedNames = files.stream()
                .map(KnowledgeDocumentScanner.KbFile::name)
                .collect(Collectors.toSet());
        List<KbDocumentEntity> rows = mapper.selectList(new QueryWrapper<KbDocumentEntity>()
                .select("id", "doc_name", "chunk_count"));
        for (KbDocumentEntity row : rows) {
            if (scannedNames.contains(row.getDocName())) {
                continue;
            }
            deleteChunks(store, row.getDocName(), row.getChunkCount() == null ? 0 : row.getChunkCount());
            mapper.deleteById(row.getId());
            log.info("[knowledge] 已清理孤儿文档 '{}'（磁盘已删除，台账 {} chunk）",
                    row.getDocName(), row.getChunkCount());
        }
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
    public List<KnowledgeHit> search(String query, List<String> kbs) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            // getIfAvailable 触发 vectorStore 首次懒创建（含 initializeSchema 建表 DDL）：
            // PG 未就绪时此处抛 BeanCreationException——必须与检索失败同样降级空表，
            // 否则首次 chat 即被 bean 创建异常打断（降级口径：检索是增强信息不阻断主链路）
            VectorStore store = storeProvider.getIfAvailable();
            if (store == null) {
                return List.of();
            }
            return store.similaritySearch(searchRequest(query, kbs)).stream()
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

    /** 相似度检索请求（top-k 0 = 不限条数；min-score 直接映射相似度阈值；kbs 非空时按 metadata.kb 过滤） */
    SearchRequest searchRequest(String query, List<String> kbs) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query);
        if (props.getTopK() > 0) {
            builder.topK(props.getTopK());
        }
        builder.similarityThreshold(props.getMinScore());
        String kbFilter = kbFilterExpression(kbs);
        if (kbFilter != null) {
            builder.filterExpression(kbFilter);
        }
        return builder.build();
    }

    /**
     * kb 过滤表达式（如 {@code kb in ['java','frontend']}）；kbs 空/全空白返回 null（不过滤）。
     * kb 取自目录名，单引号属病态输入直接剔除（filter 表达式字符串字面量分隔符）。
     */
    static String kbFilterExpression(List<String> kbs) {
        if (kbs == null || kbs.isEmpty()) {
            return null;
        }
        String tokens = kbs.stream()
                .map(kb -> kb == null ? "" : kb.replace("'", "").trim())
                .filter(kb -> !kb.isEmpty())
                .distinct()
                .map(kb -> "'" + kb + "'")
                .collect(Collectors.joining(","));
        return tokens.isEmpty() ? null : "kb in [" + tokens + "]";
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

    /**
     * 分批写入向量库（app.knowledge.embed-batch-size，0 = 不分批一次提交）：
     * DashScope 兼容模式单请求硬上限 10 条文本，默认 8 留余量——Spring AI 默认
     * BatchingStrategy 只按总 token 打包不管条数，大文档切出大量 chunk 时不分批
     * 会整批 400 失败。
     */
    private void addInBatches(VectorStore store, List<Document> docs) {
        int batchSize = props.getEmbedBatchSize();
        if (batchSize <= 0 || docs.size() <= batchSize) {
            store.add(docs);
            return;
        }
        for (int from = 0; from < docs.size(); from += batchSize) {
            store.add(docs.subList(from, Math.min(from + batchSize, docs.size())));
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
        row.setKb(file.kb());
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
