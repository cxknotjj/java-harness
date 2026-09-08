package com.dark.javaHarness.tool;

import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * HTML → Markdown 转换引擎（静态无状态）。
 *
 * <p>从网页抓取流程中独立出来的纯转换层：DOM 级剔除导航/广告噪声后做主内容启发式提取
 * （文本密度 + 链接密度评分的简化 Readability），块级元素按标签转为 Markdown
 * （保留标题/列表/链接/代码块/表格）。不感知网络与缓存，输入 Document 输出正文文本。
 */
final class HtmlToMarkdown {

    /** 主内容候选块的最小文本量（低于此的容器不参与评分） */
    private static final int MIN_BLOCK_TEXT = 150;
    /** 正文硬上限：超长页保留前 N 字符（缓存条目随之不超过该值，支持换 query 重新提取其它段落） */
    private static final int MAX_BODY_CHARS = 50_000;

    /** DOM 级噪声标签：从不承载正文（main 除外——它常是正文容器） */
    private static final String NOISE_TAGS =
            "script,style,noscript,template,iframe,svg,form,button,select,textarea,nav,header,footer,aside";
    /** class/id 带模板/广告特征的容器（正则匹配即剔除） */
    private static final Pattern NOISE_CLASS = Pattern.compile(
            "(navbar|menu|sidebar|footer|header|breadcrumb|cookie|banner|advert|ads?([-_ ]|$)"
                    + "|promo|subscribe|social|share|related|recommend|comment|popup|modal|search(-|_)?box"
                    + "|pagination|crumb|tooltip|dropdown|sitemap)");

    /** 主内容候选容器（文档序：外层在前；评分取最高） */
    private static final String CONTENT_CANDIDATES =
            "article, main, [role=main], .post, .article, .content, .entry, "
                    + ".post-content, .article-content, div, section, td";

    /** 块容器标签：Markdown 转换时递归展开其子节点 */
    private static final Set<String> BLOCK_CONTAINERS = Set.of(
            "div", "section", "article", "main", "center", "figure", "details", "dl",
            "td", "tr", "tbody", "thead", "tfoot");

    /** 行内标签：出现在文本流中时并入当前段落而非拆块 */
    private static final Set<String> INLINE_TAGS = Set.of(
            "a", "span", "strong", "b", "em", "i", "code", "small", "sub", "sup", "u",
            "s", "mark", "abbr", "cite", "q", "time", "kbd", "samp", "var", "del", "ins", "font", "big", "tt");

    private HtmlToMarkdown() {
    }

    /** 页面 → Markdown 正文：去噪 → 主内容评分 → 块级转换，硬上限 MAX_BODY_CHARS */
    static String renderBody(Document doc) {
        Element root = doc.body() != null ? doc.body() : doc;
        removeNoise(root);
        Element main = bestContentBlock(root);
        Element scope = main != null ? main : root;
        StringBuilder body = new StringBuilder();
        appendBlocks(scope, body);
        String text = body.toString().replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() > MAX_BODY_CHARS) {
            text = text.substring(0, MAX_BODY_CHARS)
                    + "\n\n…[页面正文过长，已保留前 " + MAX_BODY_CHARS + " 字符]";
        }
        return text;
    }

    /** DOM 级去噪：噪声标签整除 + class/id 带模板/广告特征的容器剔除 */
    private static void removeNoise(Element root) {
        root.select(NOISE_TAGS).remove();
        for (Element el : root.select("[class],[id]")) {
            String meta = (el.className() + " " + el.id()).toLowerCase();
            if (!meta.isBlank() && NOISE_CLASS.matcher(meta).find()) {
                el.remove();
            }
        }
    }

    /**
     * 主内容启发式评分（简化 Readability）：在候选容器中选
     * 「文本量 ×(1-链接密度) + 段落数加权」最高者；链接密度过高（导航/目录页）与
     * 噪声容器不参与；无达标容器（短页/首页）返回 null，由调用方回退全 body。
     */
    private static Element bestContentBlock(Element root) {
        Element best = null;
        long bestScore = 0;
        for (Element el : root.select(CONTENT_CANDIDATES)) {
            String meta = (el.className() + " " + el.id()).toLowerCase();
            if (NOISE_CLASS.matcher(meta).find()) {
                continue;
            }
            String text = el.text();
            int len = text.length();
            if (len < MIN_BLOCK_TEXT) {
                continue;
            }
            int linkLen = 0;
            for (Element a : el.select("a")) {
                linkLen += a.text().length();
            }
            double linkDensity = (double) linkLen / Math.max(1, len);
            if (linkDensity > 0.4) {
                continue;
            }
            int paragraphs = el.select("p").size();
            long score = Math.round(len * (1 - linkDensity)) + paragraphs * 150L;
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        return best;
    }

    /** 块级转换：遍历容器子节点，文本流（含行内元素）聚为段落，块级元素按标签分派 */
    private static void appendBlocks(Element container, StringBuilder md) {
        StringBuilder run = new StringBuilder();
        for (Node node : container.childNodes()) {
            if (node instanceof TextNode t) {
                run.append(t.getWholeText());
            } else if (node instanceof Element child) {
                if (run.length() > 0 && INLINE_TAGS.contains(child.tagName())) {
                    run.append(inlineMarkdown(child));
                } else {
                    flushRun(run, md);
                    appendBlock(child, md);
                }
            }
        }
        flushRun(run, md);
    }

    /** 把当前文本流落为一个段落 */
    private static void flushRun(StringBuilder run, StringBuilder md) {
        String text = run.toString().replaceAll("\\s+", " ").trim();
        run.setLength(0);
        if (!text.isEmpty()) {
            md.append('\n').append(text).append('\n');
        }
    }

    /** 单个块级元素 → Markdown */
    private static void appendBlock(Element el, StringBuilder md) {
        switch (el.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = Math.min(6, el.tagName().charAt(1) - '0');
                md.append('\n').append("#".repeat(level)).append(' ')
                        .append(inlineMarkdown(el).trim()).append('\n');
            }
            case "p" -> md.append('\n').append(inlineMarkdown(el).trim()).append('\n');
            case "ul" -> appendList(el, md, false, 0);
            case "ol" -> appendList(el, md, true, 0);
            case "pre" -> md.append("\n```\n").append(stripEdges(el.wholeText())).append("\n```\n");
            case "blockquote" -> md.append('\n').append(prefixLines(el.text().trim(), "> ")).append('\n');
            case "table" -> appendTable(el, md);
            case "hr" -> md.append("\n---\n");
            case "br" -> { /* 行内 br 由 inlineMarkdown 处理；块级孤 br 无意义 */ }
            default -> {
                if (BLOCK_CONTAINERS.contains(el.tagName())) {
                    appendBlocks(el, md);
                } else {
                    md.append('\n').append(inlineMarkdown(el).trim()).append('\n');
                }
            }
        }
    }

    /** 列表 → Markdown（支持嵌套缩进；有序/无序标记） */
    private static void appendList(Element list, StringBuilder md, boolean ordered, int depth) {
        String indent = "  ".repeat(depth);
        int index = 1;
        for (Element li : list.children()) {
            if (!"li".equals(li.tagName())) {
                continue;
            }
            String marker = ordered ? (index++) + "." : "-";
            Element copy = li.clone();
            copy.select("ul,ol").remove(); // 嵌套列表单独递归，不混入本行文本
            String text = inlineMarkdown(copy).replaceAll("\\s+", " ").trim();
            md.append('\n').append(indent).append(marker).append(' ').append(text);
            for (Element sub : li.children()) {
                if ("ul".equals(sub.tagName())) {
                    appendList(sub, md, false, depth + 1);
                } else if ("ol".equals(sub.tagName())) {
                    appendList(sub, md, true, depth + 1);
                }
            }
        }
        md.append('\n');
    }

    /** 表格 → Markdown 管道表（首行作表头；最多 40 行防巨表刷屏） */
    private static void appendTable(Element table, StringBuilder md) {
        var rows = table.select("tr");
        if (rows.isEmpty()) {
            return;
        }
        md.append('\n');
        int written = 0;
        for (int r = 0; r < rows.size() && written < 40; r++) {
            var cells = rows.get(r).select("th,td");
            if (cells.isEmpty()) {
                continue;
            }
            md.append('|');
            for (Element c : cells) {
                md.append(' ').append(clip(c.text(), 120)).append(" |");
            }
            md.append('\n');
            if (written == 0) {
                md.append('|');
                for (Element c : cells) {
                    md.append(" --- |");
                }
                md.append('\n');
            }
            written++;
        }
        if (rows.size() > 40) {
            md.append("…[表格过长，仅保留前 40 行]\n");
        }
    }

    /** 行内内容 → Markdown 文本（a/strong/em/code/br/img，其余递归取文本） */
    private static String inlineMarkdown(Element el) {
        StringBuilder sb = new StringBuilder();
        for (Node node : el.childNodes()) {
            if (node instanceof TextNode t) {
                sb.append(t.getWholeText());
            } else if (node instanceof Element child) {
                appendInline(child, sb);
            }
        }
        return sb.toString();
    }

    private static void appendInline(Element child, StringBuilder sb) {
        switch (child.tagName()) {
            case "a" -> {
                String text = child.text().trim();
                if (text.isEmpty()) {
                    return;
                }
                String href = child.absUrl("href");
                sb.append('[').append(text).append("](")
                        .append(href.isBlank() ? child.attr("href") : href).append(')');
            }
            case "strong", "b" -> sb.append("**").append(inlineMarkdown(child).trim()).append("**");
            case "em", "i" -> sb.append('*').append(inlineMarkdown(child).trim()).append('*');
            case "code", "kbd", "samp" -> sb.append('`')
                    .append(child.text().replaceAll("\\s+", " ")).append('`');
            case "pre" -> sb.append('`').append(child.text().replaceAll("\\s+", " ")).append('`');
            case "br" -> sb.append('\n');
            case "img" -> sb.append("[图片: ").append(child.attr("alt").isBlank() ? "无描述" : child.attr("alt")).append(']');
            case "script", "style" -> { /* 去噪冗余保险 */ }
            default -> sb.append(inlineMarkdown(child));
        }
    }

    private static String prefixLines(String text, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append(prefix).append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static String stripEdges(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && (s.charAt(b) == '\n' || s.charAt(b) == ' ')) {
            b++;
        }
        while (e > b && (s.charAt(e - 1) == '\n' || s.charAt(e - 1) == ' ')) {
            e--;
        }
        return s.substring(b, e);
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
