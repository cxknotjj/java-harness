package com.dark.javaHarness.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.KnowledgeProperties;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import com.dark.javaHarness.mapper.KbDocumentMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

/**
 * KnowledgeServiceImpl 单测（Mockito 边界 mock）：增量摄取比对（mtime + kb）、旧 chunk
 * 删除、向量库不可用的降级/报错语义、kb 过滤表达式与检索请求组装。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceImplTest {

    @Mock
    private ObjectProvider<VectorStore> storeProvider;
    @Mock
    private VectorStore store;
    @Mock
    private KnowledgeDocumentScanner scanner;
    @Mock
    private KbDocumentMapper mapper;

    private KnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeServiceImpl(storeProvider, scanner, mapper, new KnowledgeProperties());
    }

    private KnowledgeDocumentScanner.KbFile file(String name, String kb, long mtime, String text) {
        return new KnowledgeDocumentScanner.KbFile(name, kb, "标题-" + name, mtime, text);
    }

    private KbDocumentEntity row(String docName, String kb, long mtime, int chunkCount, int status) {
        KbDocumentEntity row = new KbDocumentEntity();
        row.setId(1L);
        row.setDocName(docName);
        row.setKb(kb);
        row.setMtime(mtime);
        row.setChunkCount(chunkCount);
        row.setStatus(status);
        return row;
    }

    @Test
    void sync_newFile_addsChunksAndInsertsRow() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 1, 0, 1), view);
        verify(store).add(anyList());
        verify(mapper).insert(any(KbDocumentEntity.class));
    }

    @Test
    void sync_unchangedMtimeAndKb_skippedWithoutEmbedding() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", "default", 100L, 1, 1));

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 0, 1, 0), view);
        verify(store, never()).add(anyList());
        verify(mapper, never()).insert(any(KbDocumentEntity.class));
        verify(mapper, never()).updateById(any(KbDocumentEntity.class));
    }

    @Test
    void sync_legacyRowWithoutKb_reingestedForMetadataHealing() {
        // V13 迁移后存量行 kb=NULL（chunk 无 kb 元数据）：kb 不一致 → 重摄取自愈补齐
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", null, 100L, 1, 1));

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 1, 0, 1), view);
        verify(store).delete(List.of("a.md#0"));
        verify(store).add(anyList());
        verify(mapper).updateById(any(KbDocumentEntity.class));
    }

    @Test
    void sync_changedMtime_deletesOldChunksThenReEmbeds() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 200L, "新正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", "default", 100L, 2, 1));

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 1, 0, 1), view);
        verify(store).delete(List.of("a.md#0", "a.md#1"));
        verify(store).add(anyList());
        verify(mapper).updateById(any(KbDocumentEntity.class));
    }

    @Test
    void sync_subdirFile_writesKbMetadataAndLedgesIt() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("java/spring.md", "java", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<KbDocumentEntity> rowCaptor = ArgumentCaptor.forClass(KbDocumentEntity.class);

        service.sync();

        verify(store).add(docsCaptor.capture());
        assertEquals("java", docsCaptor.getValue().get(0).getMetadata().get("kb"));
        assertEquals("java/spring.md#0", docsCaptor.getValue().get(0).getId());
        verify(mapper).insert(rowCaptor.capture());
        assertEquals("java", rowCaptor.getValue().getKb());
        assertEquals("java/spring.md", rowCaptor.getValue().getDocName());
    }

    @Test
    void sync_storeUnavailable_throwsReadableError() {
        // requireStore 在扫描之前执行：store 未装配时直接抛，scanner 不会被触达
        when(storeProvider.getIfAvailable()).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::sync);
        assertTrue(ex.getMessage().contains("向量库未装配"));
        verify(mapper, never()).insert(any(KbDocumentEntity.class));
    }

    @Test
    void sync_storeWriteFailure_wrapsAsIllegalState() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", "default", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("pg down")).when(store).add(anyList());

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::sync);
        assertTrue(ex.getMessage().contains("向量库写入失败"));
        verify(mapper, never()).insert(any(KbDocumentEntity.class));
    }

    @Test
    void delete_missingDoc_returnsFalse() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertFalse(service.delete("ghost.md"));
        verify(storeProvider, never()).getIfAvailable();
    }

    @Test
    void delete_existingDoc_removesChunksAndRow() {
        when(mapper.selectOne(any())).thenReturn(row("a.md", "default", 100L, 2, 1));
        when(storeProvider.getIfAvailable()).thenReturn(store);

        assertTrue(service.delete("a.md"));

        verify(store).delete(List.of("a.md#0", "a.md#1"));
        verify(mapper).deleteById(1L);
    }

    @Test
    void search_blankQuery_returnsEmptyWithoutStore() {
        assertTrue(service.search("  ", null).isEmpty());
    }

    @Test
    void search_storeUnavailable_degradesToEmpty() {
        when(storeProvider.getIfAvailable()).thenReturn(null);
        assertTrue(service.search("问题", null).isEmpty());
    }

    @Test
    void search_failure_degradesToEmpty() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("pg down"));

        assertTrue(service.search("问题", null).isEmpty(), "检索失败应降级空表，不阻断主链路");
    }

    @Test
    void search_hits_mappedToKnowledgeHit() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        Document doc = org.mockito.Mockito.mock(Document.class);
        when(doc.getMetadata()).thenReturn(Map.of("source", "a.md", "title", "标题-a.md"));
        when(doc.getText()).thenReturn("片段内容");
        when(doc.getScore()).thenReturn(0.87);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<KnowledgeService.KnowledgeHit> hits = service.search("问题", null);

        assertEquals(1, hits.size());
        assertEquals("a.md", hits.get(0).docName());
        assertEquals("标题-a.md", hits.get(0).title());
        assertEquals(0.87, hits.get(0).score());
        assertEquals("片段内容", hits.get(0).text());
    }

    @Test
    void search_boundKbs_requestCarriesFilterExpression() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ArgumentCaptor<SearchRequest> reqCaptor = ArgumentCaptor.forClass(SearchRequest.class);

        service.search("问题", List.of("java", "frontend"));

        verify(store).similaritySearch(reqCaptor.capture());
        assertNotNull(reqCaptor.getValue().getFilterExpression(), "绑定库非空时请求应携带 filter 表达式");
    }

    @Test
    void search_unbound_requestWithoutFilterExpression() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ArgumentCaptor<SearchRequest> reqCaptor = ArgumentCaptor.forClass(SearchRequest.class);

        service.search("问题", null);

        verify(store).similaritySearch(reqCaptor.capture());
        assertNull(reqCaptor.getValue().getFilterExpression(), "未绑定 = 不限，不应携带 filter");
    }

    @Test
    void kbFilterExpression_nullWhenNoBinding() {
        assertNull(KnowledgeServiceImpl.kbFilterExpression(null));
        assertNull(KnowledgeServiceImpl.kbFilterExpression(List.of()));
        assertNull(KnowledgeServiceImpl.kbFilterExpression(List.of("  ", "")));
    }

    @Test
    void kbFilterExpression_inExpressionForBoundKbs() {
        assertEquals("kb in ['java','frontend']",
                KnowledgeServiceImpl.kbFilterExpression(List.of("java", "frontend")));
        assertEquals("kb in ['java']", KnowledgeServiceImpl.kbFilterExpression(List.of("'java'")),
                "单引号属病态输入应剔除");
    }

    @Test
    void chunkId_isDeterministic() {
        assertEquals("a.md#3", KnowledgeServiceImpl.chunkId("a.md", 3));
        assertEquals("java/spring.md#0", KnowledgeServiceImpl.chunkId("java/spring.md", 0),
                "子目录文档 name 带前缀，id 仍确定性");
    }
}
