package com.dark.javaHarness.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MarkdownChunker 纯函数单测：段落合并、超长硬切、重叠、边界输入。
 */
class MarkdownChunkerTest {

    @Test
    void chunk_nullOrBlank_returnsEmpty() {
        assertTrue(MarkdownChunker.chunk(null, 700, 100).isEmpty());
        assertTrue(MarkdownChunker.chunk("   \n\t ", 700, 100).isEmpty());
        assertTrue(MarkdownChunker.chunk("文本", 0, 100).isEmpty(), "maxChunkChars<=0 应返回空");
    }

    @Test
    void chunk_shortText_singleChunkKeptAsIs() {
        List<String> out = MarkdownChunker.chunk("只有一段短文本", 700, 100);
        assertEquals(1, out.size());
        assertEquals("只有一段短文本", out.get(0));
    }

    @Test
    void chunk_paragraphsMergedWithinLimit() {
        String text = "第一段。\n\n第二段。";
        List<String> out = MarkdownChunker.chunk(text, 700, 100);
        assertEquals(1, out.size(), "两段合并在预算内应输出单 chunk");
        assertTrue(out.get(0).contains("第一段。"));
        assertTrue(out.get(0).contains("第二段。"));
    }

    @Test
    void chunk_paragraphOverflow_startsNewChunk() {
        String text = "A".repeat(60) + "\n\n" + "B".repeat(60);
        List<String> out = MarkdownChunker.chunk(text, 100, 0);
        assertEquals(2, out.size(), "合并后超限应拆成两个 chunk");
        assertTrue(out.get(0).startsWith("A"));
        assertTrue(out.get(1).startsWith("B"));
    }

    @Test
    void chunk_overlongParagraph_hardSplitKeepsMaxChars() {
        String paragraph = "字".repeat(25);
        List<String> out = MarkdownChunker.chunk(paragraph, 10, 0);
        assertFalse(out.isEmpty());
        for (String piece : out) {
            assertTrue(piece.length() <= 10, "硬切块不得超上限: " + piece.length());
        }
        assertEquals(3, out.size());
    }

    @Test
    void chunk_overlap_tailCarriedToNextChunk() {
        // 第一段 20 字符 ≤ 25 上限，合并第二段后 41 > 25 → 拆块并带 5 字符重叠
        String text = "A".repeat(20) + "\n\n" + "B".repeat(20);
        List<String> out = MarkdownChunker.chunk(text, 25, 5);
        assertEquals(2, out.size());
        assertTrue(out.get(1).contains("AAAAA"), "重叠应把上一块尾部带进下一块");
        assertTrue(out.get(1).contains("B"));
    }

    @Test
    void chunk_blankParagraphs_skipped() {
        List<String> out = MarkdownChunker.chunk("第一段。\n\n \n\n\n第二段。", 700, 0);
        assertEquals(1, out.size());
    }
}
