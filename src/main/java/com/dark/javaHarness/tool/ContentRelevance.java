package com.dark.javaHarness.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 查询相关性段落提取（纯静态）：把 Markdown 正文按空行分块，
 * 保留与查询关键词有命中的块直至预算，用于长网页按意图裁剪上下文。
 * 全部未命中时回退「正文开头 + 未命中提示」，保证模型始终有可用内容。
 */
final class ContentRelevance {

    /** 相关段落提取预算：按查询命中保留的段落总量上限 */
    private static final int RELEVANT_MAX_CHARS = 8_000;

    private ContentRelevance() {
    }

    /**
     * 按查询意图提取相关段落：把 Markdown 按空行分块，保留与关键词有命中的块直至预算。
     */
    static String applyQuery(String body, String query) {
        if (body == null || body.isEmpty() || query == null || query.isBlank()) {
            return body;
        }
        List<String> keywords = keywordsOf(query);
        if (keywords.isEmpty()) {
            return body;
        }
        StringBuilder kept = new StringBuilder();
        int total = 0;
        boolean anyHit = false;
        for (String block : body.split("\\n\\s*\\n")) {
            String plain = block.trim();
            if (plain.isEmpty()) {
                continue;
            }
            if (hits(plain, keywords) > 0) {
                anyHit = true;
                if (total + plain.length() > RELEVANT_MAX_CHARS) {
                    break;
                }
                kept.append(plain).append("\n\n");
                total += plain.length();
            }
        }
        if (!anyHit) {
            String head = body.length() > 2000 ? body.substring(0, 2000) + "..." : body;
            return "（页面中未命中查询关键词，以下为正文开头部分）\n\n" + head;
        }
        return kept.toString().trim();
    }

    /** 关键词抽取：拉丁词（≥2 字符）+ 中文词；≥4 字的中文词补充二字滑窗提升命中率 */
    private static List<String> keywordsOf(String query) {
        List<String> out = new ArrayList<>();
        var m = Pattern.compile("[\\w]+|[\\u4e00-\\u9fa5]+").matcher(query.toLowerCase());
        while (m.find()) {
            String term = m.group();
            if (term.matches("[\\u4e00-\\u9fa5]+")) {
                if (term.length() >= 4) {
                    for (int i = 0; i + 2 <= term.length(); i++) {
                        out.add(term.substring(i, i + 2));
                    }
                } else if (term.length() >= 2) {
                    out.add(term);
                }
            } else if (term.length() >= 2) {
                out.add(term);
            }
        }
        return out;
    }

    /** 块内关键词命中总数（大小写已归一） */
    private static int hits(String block, List<String> keywords) {
        String lower = block.toLowerCase();
        int n = 0;
        for (String k : keywords) {
            int idx = 0;
            while ((idx = lower.indexOf(k, idx)) >= 0) {
                n++;
                idx += k.length();
            }
        }
        return n;
    }
}
