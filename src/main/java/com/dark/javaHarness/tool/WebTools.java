package com.dark.javaHarness.tool;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 网页能力工具集（对标 dsh web 工具的抓取侧）。
 *
 * <p>fetchUrl 经 jsoup 抓取并做 HTML5 解析（编码自动嗅探，GBK 等站点不再乱码），
 * DOM 级剔除导航/广告噪声后做主内容启发式提取（文本密度 + 链接密度评分的简化 Readability），
 * 输出 Markdown（保留标题/列表/链接/代码块），可按查询意图只保留相关段落。
 * 成功结果进入内容缓存：30 分钟内重复抓取直接回放缓存内容（旧内容被上下文裁剪后可放心重抓）。
 * JS 渲染与页面交互场景由沙箱浏览器工具组（SandboxToolProvider）承接，此处只做轻量静态抓取。
 * 网页搜索（web search）需要搜索服务商 API key，待接入后补充 searchWeb 工具。
 */
@Component
public class WebTools {

    private static final Logger log = LoggerFactory.getLogger(WebTools.class);

    /** 抓取超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** 返回文本上限（防止长网页撑爆上下文） */
    private static final int MAX_CHARS = 12_000;
    /** 单次下载上限 2MB（jsoup maxBodySize 单位为字节） */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    /** 缓存条目内容上限（超长页缓存前 50k 字符，支持换 query 重新提取其它段落） */
    private static final int CACHE_ITEM_MAX_CHARS = 50_000;
    /** 内容缓存容量与有效期 */
    private static final int CACHE_MAX = 50;
    private static final long CACHE_TTL_MS = Duration.ofMinutes(30).toMillis();
    /** 相关段落提取预算：按查询命中保留的段落总量上限 */
    private static final int RELEVANT_MAX_CHARS = 8_000;
    /** 主内容候选块的最小文本量（低于此的容器不参与评分） */
    private static final int MIN_BLOCK_TEXT = 150;

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

    private record CachedPage(String content, long fetchedAtMs) {}

    /** 携带用户可读消息的抓取异常：classifyError 原样透传消息（如协议白名单提示） */
    private static final class WebFetchException extends RuntimeException {
        WebFetchException(String message) {
            super(message);
        }
    }

    /** URL → 缓存内容（LRU 淘汰；命中且未过期直接回放内容，而非拒绝重抓） */
    private final Map<String, CachedPage> fetchCache =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedPage> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    @Tool(description = "抓取指定 URL 网页：自动剔除导航/广告等噪声并提取正文主内容，输出 Markdown（保留标题/列表/链接/代码块）；"
            + "可传 query 只保留与查询意图相关的段落；仅支持 http/https；"
            + "同一 URL 30 分钟内重复抓取会直接返回缓存内容（不重复请求网络），内容被上下文裁剪后可放心重抓")
    public String fetchUrl(@ToolParam(description = "要抓取的完整 URL") String url,
                           @ToolParam(required = false, description = "本次抓取想了解的问题或关键词（强烈建议传入），"
                                   + "工具会只保留与该意图相关的段落") String query) {
        long start = System.currentTimeMillis();
        // 重复抓取防护：30 分钟内同 URL 直接回放缓存内容（含去 #fragment 归一化），防止模型循环请求网络
        String key = normalizeUrl(url);
        CachedPage cached = fetchCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs() < CACHE_TTL_MS) {
            log.info("[tool] fetchUrl ({}) 命中内容缓存，直接回放", url);
            return cached.content() + "\n\n（以上内容来自缓存，抓取于 "
                    + formatTime(cached.fetchedAtMs()) + "；如需其它段落可换更具体的 query 重试）";
        }
        try {
            Document doc = fetchDocument(url);
            String title = doc.title() == null ? "" : doc.title().trim();
            String body = renderBody(doc);
            if (body.isBlank()) {
                // 空内容不缓存：可能是 JS 渲染页，模型换浏览器工具重试时不应被缓存锁死
                return "（页面无可提取正文——可能为纯 JS 渲染页，可尝试沙箱浏览器工具 browser_navigate + browser_snapshot）";
            }
            String header = "【网页】" + (title.isBlank() ? "(无标题)" : title)
                    + "\n来源: " + doc.baseUri() + "\n";
            String markdown = header + "\n" + body;
            fetchCache.put(key, new CachedPage(markdown, System.currentTimeMillis()));
            String shown = applyQuery(body, query);
            String result = header + "\n" + shown;
            if (result.length() > MAX_CHARS) {
                result = result.substring(0, MAX_CHARS)
                        + "\n\n…[已截断至 " + MAX_CHARS + " 字符；页面提取正文共约 "
                        + body.length() + " 字，可带 query 参数只取相关段落]";
            }
            log.info("[tool] fetchUrl ({}, query={}) -> {} 字符, {}ms", url,
                    query == null ? "-" : query, result.length(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            // 失败不入缓存：允许模型换姿势重试
            String message = "工具执行失败: " + classifyError(e);
            log.warn("[tool] fetchUrl ({}) 失败: {}", url, message);
            return message;
        }
    }

    /** 抓取键归一化：去空白与 #fragment（同页面不同锚点视为同 URL） */
    private static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        int hash = u.indexOf('#');
        return hash >= 0 ? u.substring(0, hash) : u;
    }

    /** jsoup 抓取：15s 超时、2MB 下载上限、跟随重定向；编码由 jsoup 嗅探（header + meta charset + 兜底） */
    private Document fetchDocument(String url) throws Exception {
        String trimmed = url == null ? "" : url.trim();
        String lower = trimmed.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new WebFetchException("仅支持 http/https 协议");
        }
        URI.create(trimmed); // 明显非法的 URL 在此提前失败（错误归入「URL 非法」）
        return Jsoup.connect(trimmed)
                .timeout((int) TIMEOUT.toMillis())
                .maxBodySize(MAX_BODY_BYTES)
                .userAgent("Mozilla/5.0 (compatible; javaHarness-bot/1.0; +https://github.com/cxknotjj/java-harness)")
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .followRedirects(true)
                .get();
    }

    /**
     * 错误分类：把底层异常映射为模型可读、可自愈的中文提示。
     * jsoup 会把底层网络异常多层包裹（WSL DNS 隧道下域名失败也表现为 ConnectException 链），
     * 故对整条 cause 链做特征扫描，而非只看最深一层。
     */
    static String classifyError(Throwable e) {
        if (e instanceof WebFetchException wfe) {
            return wfe.getMessage();
        }
        if (e instanceof HttpStatusException hse) {
            int code = hse.getStatusCode();
            if (code == 403) {
                return "HTTP 403（站点拒绝访问，疑似反爬）——可稍后重试，或改用沙箱浏览器工具 browser_navigate";
            }
            if (code == 404) {
                return "HTTP 404（页面不存在）——请检查 URL 是否正确";
            }
            if (code == 429) {
                return "HTTP 429（请求过于频繁）——请稍后重试";
            }
            if (code >= 500) {
                return "HTTP " + code + "（站点服务端错误）——可稍后重试";
            }
            return "HTTP " + code + "（页面不可用或被拒）";
        }
        if (e instanceof UnsupportedMimeTypeException mte) {
            return "非网页内容（Content-Type: " + mte.getMimeType()
                    + "），无法提取正文——如需该资源请改用其它方式获取";
        }
        if (e instanceof IllegalArgumentException) {
            return "URL 非法或包含未转义字符——请检查 URL 格式后重试";
        }
        boolean unknownHost = false;
        boolean timeout = false;
        boolean refused = false;
        boolean ssl = false;
        Throwable c = e;
        while (c != null) {
            if (c instanceof UnknownHostException || c instanceof UnresolvedAddressException) {
                unknownHost = true;
            } else if (c instanceof SocketTimeoutException) {
                timeout = true;
            } else if (c instanceof ConnectException) {
                refused = true;
            } else if (c instanceof SSLException) {
                ssl = true;
            }
            c = c.getCause() == c ? null : c.getCause();
        }
        if (unknownHost) {
            return "域名无法解析——请检查拼写，或该站点已下线";
        }
        if (timeout) {
            return "连接或读取超时——站点响应慢或不可达，可稍后重试";
        }
        if (refused) {
            return "连接被拒绝——目标端口无服务或被防火墙拦截";
        }
        if (ssl) {
            return "TLS/证书异常——请检查站点证书";
        }
        return "网络异常——" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    /** 页面 → Markdown 正文：去噪 → 主内容评分 → 块级转换，硬上限 CACHE_ITEM_MAX_CHARS */
    private String renderBody(Document doc) {
        Element root = doc.body() != null ? doc.body() : doc;
        removeNoise(root);
        Element main = bestContentBlock(root);
        Element scope = main != null ? main : root;
        StringBuilder body = new StringBuilder();
        appendBlocks(scope, body);
        String text = body.toString().replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() > CACHE_ITEM_MAX_CHARS) {
            text = text.substring(0, CACHE_ITEM_MAX_CHARS)
                    + "\n\n…[页面正文过长，已保留前 " + CACHE_ITEM_MAX_CHARS + " 字符]";
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

    /**
     * 按查询意图提取相关段落：把 Markdown 按空行分块，保留与关键词有命中的块直至预算。
     * 全部未命中时回退「正文开头 + 未命中提示」，保证模型始终有可用内容。
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

    private static String formatTime(long epochMs) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs));
    }
}
