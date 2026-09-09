package com.dark.javaHarness.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 感知切分器（纯函数）：把文档正文切成适合嵌入检索的 chunk——
 * 段落为最小单元（空行分隔），贪心装填到 maxChunkChars 上限；
 * 超限的单段落硬切；相邻 chunk 间带 overlapChars 重叠，避免语义被切分边界截断。
 *
 * <p>确定性：同输入恒等同输出（chunk id = docName#idx 的稳定性依赖此性质，
 * 重摄取时旧 chunk id 可精确删除）。行数约为字符数的下界（中文 1 字符 ≈ 1 token，
 * ~700 字符 ≈ ~700 token，兼顾检索粒度与注入预算）。
 */
public final class MarkdownChunker {

    private MarkdownChunker() {
    }

    /**
     * 切分正文（无空文本返回空表）。
     *
     * @param text          正文（front-matter 已剥除）
     * @param maxChunkChars 单 chunk 字符上限
     * @param overlapChars  相邻 chunk 重叠字符数（0 = 不重叠）
     */
    public static List<String> chunk(String text, int maxChunkChars, int overlapChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank() || maxChunkChars <= 0) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : splitParagraphs(text)) {
            // 单段落超上限：先落袋当前累积块，再硬切该段落
            if (paragraph.length() > maxChunkChars) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current = new StringBuilder(overlapTail(out.get(out.size() - 1), overlapChars));
                }
                for (String piece : hardSplit(paragraph, maxChunkChars)) {
                    if (!piece.isBlank()) {
                        out.add(piece);
                    }
                }
                current = new StringBuilder(overlapTail(out.get(out.size() - 1), overlapChars));
                continue;
            }
            // 装填会超限：当前块落袋，新块以重叠开头
            if (current.length() > 0
                    && current.length() + paragraph.length() + 1 > maxChunkChars) {
                out.add(current.toString());
                current = new StringBuilder(overlapTail(out.get(out.size() - 1), overlapChars));
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(paragraph);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** 空行分隔的段落（去首尾空白，跳过全空段） */
    private static List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : text.split("\\r?\\n\\s*\\r?\\n")) {
            String trimmed = block.strip();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    /** 超长段落硬切（按字符上限，行边界优先对齐） */
    private static List<String> hardSplit(String paragraph, int maxChars) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + maxChars, paragraph.length());
            if (end < paragraph.length()) {
                int lineBreak = paragraph.lastIndexOf('\n', end);
                if (lineBreak > start) {
                    end = lineBreak;
                }
            }
            pieces.add(paragraph.substring(start, end).strip());
            start = end;
        }
        return pieces;
    }

    /** 取上一 chunk 的尾部重叠段（行边界对齐，越界兜底硬截） */
    private static String overlapTail(String prev, int overlapChars) {
        if (overlapChars <= 0 || prev == null || prev.isEmpty()) {
            return "";
        }
        String tail = prev.length() <= overlapChars ? prev : prev.substring(prev.length() - overlapChars);
        int lineBreak = tail.indexOf('\n');
        return lineBreak >= 0 && lineBreak < tail.length() - 1 ? tail.substring(lineBreak + 1) : tail;
    }
}
