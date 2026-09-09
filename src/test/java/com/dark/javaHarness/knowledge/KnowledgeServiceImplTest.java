package com.dark.javaHarness.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

/**
 * KnowledgeServiceImpl 单测（Mockito 边界 mock）：增量摄取比对、旧 chunk 删除、
 * 向量库不可用的降级/报错语义。
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

    private KnowledgeDocumentScanner.KbFile file(String name, long mtime, String text) {
        return new KnowledgeDocumentScanner.KbFile(name, "标题-" + name, mtime, text);
    }

    private KbDocumentEntity row(String docName, long mtime, int chunkCount, int status) {
        KbDocumentEntity row = new KbDocumentEntity();
        row.setId(1L);
        row.setDocName(docName);
        row.setMtime(mtime);
        row.setChunkCount(chunkCount);
        row.setStatus(status);
        return row;
    }

    @Test
    void sync_newFile_addsChunksAndInsertsRow() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(null);

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 1, 0, 1), view);
        verify(store).add(anyList());
        verify(mapper).insert(any(KbDocumentEntity.class));
    }

    @Test
    void sync_unchangedMtime_skippedWithoutEmbedding() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", 100L, "短正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", 100L, 1, 1));

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 0, 1, 0), view);
        verify(store, never()).add(anyList());
        verify(mapper, never()).insert(any(KbDocumentEntity.class));
        verify(mapper, never()).updateById(any(KbDocumentEntity.class));
    }

    @Test
    void sync_changedMtime_deletesOldChunksThenReEmbeds() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(scanner.scan()).thenReturn(List.of(file("a.md", 200L, "新正文")));
        when(mapper.selectOne(any())).thenReturn(row("a.md", 100L, 2, 1));

        KnowledgeSyncView view = service.sync();

        assertEquals(new KnowledgeSyncView(1, 1, 0, 1), view);
        verify(store).delete(List.of("a.md#0", "a.md#1"));
        verify(store).add(anyList());
        verify(mapper).updateById(any(KbDocumentEntity.class));
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
        when(scanner.scan()).thenReturn(List.of(file("a.md", 100L, "短正文")));
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
        when(mapper.selectOne(any())).thenReturn(row("a.md", 100L, 2, 1));
        when(storeProvider.getIfAvailable()).thenReturn(store);

        assertTrue(service.delete("a.md"));

        verify(store).delete(List.of("a.md#0", "a.md#1"));
        verify(mapper).deleteById(1L);
    }

    @Test
    void search_blankQuery_returnsEmptyWithoutStore() {
        assertTrue(service.search("  ").isEmpty());
    }

    @Test
    void search_storeUnavailable_degradesToEmpty() {
        when(storeProvider.getIfAvailable()).thenReturn(null);
        assertTrue(service.search("问题").isEmpty());
    }

    @Test
    void search_failure_degradesToEmpty() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("pg down"));

        assertTrue(service.search("问题").isEmpty(), "检索失败应降级空表，不阻断主链路");
    }

    @Test
    void search_hits_mappedToKnowledgeHit() {
        when(storeProvider.getIfAvailable()).thenReturn(store);
        Document doc = org.mockito.Mockito.mock(Document.class);
        when(doc.getMetadata()).thenReturn(Map.of("source", "a.md", "title", "标题-a.md"));
        when(doc.getText()).thenReturn("片段内容");
        when(doc.getScore()).thenReturn(0.87);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        List<KnowledgeService.KnowledgeHit> hits = service.search("问题");

        assertEquals(1, hits.size());
        assertEquals("a.md", hits.get(0).docName());
        assertEquals("标题-a.md", hits.get(0).title());
        assertEquals(0.87, hits.get(0).score());
        assertEquals("片段内容", hits.get(0).text());
    }

    @Test
    void chunkId_isDeterministic() {
        assertEquals("a.md#3", KnowledgeServiceImpl.chunkId("a.md", 3));
    }
}
