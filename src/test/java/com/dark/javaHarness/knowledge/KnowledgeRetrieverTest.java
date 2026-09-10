package com.dark.javaHarness.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.KnowledgeProperties;
import com.dark.javaHarness.domain.dto.KnowledgeSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * KnowledgeRetriever 单测：角色策略跳过、查询长度下限、命中渲染与出处记录、预算截断、
 * agent 知识库绑定（kbs 透传与解析）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRetrieverTest {

    @Mock
    private KnowledgeService knowledgeService;

    private KnowledgeProperties props;
    private KnowledgeRetriever retriever;

    @BeforeEach
    void setUp() {
        props = new KnowledgeProperties();
        props.setTopK(4);
        props.setMinScore(0.5);
        props.setContextBudget(3000);
        props.setMinQueryChars(0);
        retriever = new KnowledgeRetriever(knowledgeService, props);
    }

    private KnowledgeService.KnowledgeHit hit(String doc, double score) {
        return new KnowledgeService.KnowledgeHit(doc, "标题-" + doc, score, "片段内容（" + doc + "）");
    }

    @Test
    void aggregatorRole_skippedWithoutSearch() {
        assertNull(retriever.buildKnowledgeBlock("aggregator", "s1", "这是一个足够长的问题", null));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void blankUser_skipped() {
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "   ", null));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void shortQuery_belowMinChars_skipped() {
        props.setMinQueryChars(10);
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "短问题", null));
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void noHits_returnsNull() {
        when(knowledgeService.search("这个问题需要知识吗", null)).thenReturn(List.of());
        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "这个问题需要知识吗", null));
    }

    @Test
    void hit_rendersCitationsAndRecordsSources() {
        when(knowledgeService.search("如何部署", null)).thenReturn(List.of(hit("a.md", 0.92)));

        String block = retriever.buildKnowledgeBlock("lead", "s1", "如何部署", null);

        assertNotNull(block);
        assertTrue(block.contains("【知识库检索结果】"));
        assertTrue(block.contains("【出处1】"));
        assertTrue(block.contains("《标题-a.md》"), "出处头应含文档标题");
        assertTrue(block.contains("a.md"), "出处头应含文件名");
        assertTrue(block.contains("片段内容（a.md）"));
        assertTrue(block.contains("禁止编造出处"), "应携带引用指令");

        List<KnowledgeSource> sources = retriever.recentSources("s1");
        assertEquals(1, sources.size());
        assertEquals(new KnowledgeSource("a.md", "标题-a.md", 0.92), sources.get(0));
    }

    @Test
    void boundKbs_passedThroughToSearch() {
        List<String> kbs = List.of("java", "frontend");
        when(knowledgeService.search("如何部署", kbs)).thenReturn(List.of(hit("a.md", 0.92)));

        assertNotNull(retriever.buildKnowledgeBlock("lead", "s1", "如何部署", kbs));
    }

    @Test
    void budgetExhausted_dropsLowScoreHits() {
        // 低分命中正文 1 万中文字符（≈1 万 token，远超 3000 预算）→ 累加到该条时 break 丢弃
        KnowledgeService.KnowledgeHit huge =
                new KnowledgeService.KnowledgeHit("big-b.md", "标题-big-b.md", 0.6, "长".repeat(10_000));
        when(knowledgeService.search("预算截断问题", null)).thenReturn(List.of(hit("a.md", 0.9), huge));

        String block = retriever.buildKnowledgeBlock("lead", "s1", "预算截断问题", null);

        assertNotNull(block);
        assertTrue(block.contains("a.md"), "高分命中应保留");
        assertFalse(block.contains("big-b.md"), "超预算的低分命中应被丢弃");
        // 出处记录与渲染一致（只记被引用的）
        assertEquals(1, retriever.recentSources("s1").size());
    }

    @Test
    void budgetZero_meansUnlimited() {
        props.setContextBudget(0);
        when(knowledgeService.search("不设预算", null)).thenReturn(
                List.of(hit("a.md", 0.9), hit("b.md", 0.8)));

        String block = retriever.buildKnowledgeBlock("lead", "s1", "不设预算", null);

        assertNotNull(block);
        assertTrue(block.contains("【出处2】"), "context-budget 0 = 不截断，全部命中注入");
    }

    @Test
    void budgetSmallerThanHeader_returnsNull() {
        props.setContextBudget(1); // 头尾固定开销即超限 → 一条都放不下
        when(knowledgeService.search("放不下", null)).thenReturn(List.of(hit("a.md", 0.9)));

        assertNull(retriever.buildKnowledgeBlock("lead", "s1", "放不下", null),
                "连一条都放不下时应返回 null（不注入空块）");
        assertTrue(retriever.recentSources("s1").isEmpty());
    }

    @Test
    void recentSources_unknownSession_returnsEmpty() {
        assertTrue(retriever.recentSources("no-such-session").isEmpty());
        assertTrue(retriever.recentSources(null).isEmpty());
    }

    @Test
    void hit_withoutSessionId_notRecorded() {
        when(knowledgeService.search("无会话场景", null)).thenReturn(List.of(hit("a.md", 0.9)));

        assertNotNull(retriever.buildKnowledgeBlock("lead", null, "无会话场景", null));
        assertTrue(retriever.recentSources(null).isEmpty());
    }

    @Test
    void parseBinding_blank_returnsNull() {
        assertNull(KnowledgeRetriever.parseBinding(null));
        assertNull(KnowledgeRetriever.parseBinding(""));
        assertNull(KnowledgeRetriever.parseBinding("  , ,"));
    }

    @Test
    void parseBinding_csv_trimsDedupsAndStripsQuotes() {
        assertEquals(List.of("java", "frontend"),
                KnowledgeRetriever.parseBinding("java, frontend ,java"));
        assertEquals(List.of("java"), KnowledgeRetriever.parseBinding("'java'"));
        assertEquals(List.of("a b"), KnowledgeRetriever.parseBinding(" a b "));
    }
}
