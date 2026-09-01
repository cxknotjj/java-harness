package com.dark.javaHarness.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 网页能力工具集（对标 dsh web 工具的抓取侧）。
 *
 * fetchUrl 抓取 URL 并把 HTML 粗提为纯文本（去 script/style/标签），供模型阅读网页内容。
 * 网页搜索（web search）需要搜索服务商 API key，待接入后补充 searchWeb 工具；
 * 原服务端 mock 天气（DemoTools.getWeather）的联网诉求由本工具替代。
 */
@Component
public class WebTools {

    private static final Logger log = LoggerFactory.getLogger(WebTools.class);

    /** 抓取超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** 返回文本上限（防止长网页撑爆上下文） */
    private static final int MAX_CHARS = 12_000;
    /** 单次下载上限 2MB */
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** 重复抓取防护：缓存容量与有效期（同 URL 有效期内只抓一次） */
    private static final int CACHE_MAX = 100;
    private static final long CACHE_TTL_MS = Duration.ofMinutes(30).toMillis();

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /** URL → 首次抓取时间（LRU 淘汰；命中且未过期则拒绝重复抓取） */
    private final java.util.Map<String, Long> fetchCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    @Tool(description = "抓取指定 URL 网页，剔除导航/广告等噪声后，仅返回与查询意图相关的正文段落；仅支持 http/https；"
            + "同一 URL 已抓取过时不要重复调用，直接基于已获取的内容作答")
    public String fetchUrl(@ToolParam(description = "要抓取的完整 URL") String url,
                           @ToolParam(required = false, description = "本次抓取想了解的问题或关键词（强烈建议传入），"
                                   + "工具会只保留与该意图相关的段落") String query) {
        long start = System.currentTimeMillis();
        // 重复抓取防护：30 分钟内同 URL 只抓一次（含去 #fragment 归一化），防止模型循环抓取
        String key = normalizeUrl(url);
        Long cachedAt = fetchCache.get(key);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            log.info("[tool] fetchUrl ({}) 命中重复抓取防护，跳过网络请求", url);
            return "该 URL 刚刚已抓取过，完整内容已在上下文中，请勿重复抓取；请直接基于已有内容继续作答。";
        }
        try {
            String result = doFetch(url);
            // 抓取成功才做内容提炼；失败结果原样返回（允许重试，不入缓存）
            if (!result.startsWith("工具执行失败")) {
                fetchCache.put(key, System.currentTimeMillis());
                result = filterJunkLines(result);
                result = extractRelevant(result, query);
            }
            log.info("[tool] fetchUrl ({}, query={}) -> {} 字符, {}ms", url,
                    query == null ? "-" : query,
                    result == null ? 0 : result.length(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("[tool] fetchUrl ({}) 失败: {}", url, e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    /** 抓取键归一化：去空白与 #fragment（同页面不同锚点视为同 URL） */
    private static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        int hash = u.indexOf('#');
        return hash >= 0 ? u.substring(0, hash) : u;
    }

    String doFetch(String url) throws Exception {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "工具执行失败: 仅支持 http/https 协议";
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "javaHarness-bot/1.0 (+https://localhost)")
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) {
            return "工具执行失败: HTTP " + resp.statusCode() + "（页面不可用或被拒）";
        }
        String contentType = resp.headers().firstValue("content-type").orElse("");
        String body = new String(
                resp.body().length > MAX_BYTES
                        ? java.util.Arrays.copyOf(resp.body(), MAX_BYTES)
                        : resp.body(),
                charsetOf(contentType));
        String text = contentType.contains("html") ? htmlToText(body) : body;
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n...[网页过长，已截断至 " + MAX_CHARS + " 字符]";
        }
        return text.isEmpty() ? "（页面无可提取文本）" : text;
    }

    /** 极简 HTML → 文本：去 script/style/注释/标签，解常见实体，压缩空白 */
    static String htmlToText(String html) {
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                // 结构噪声：导航/页头/页脚/表单/嵌入媒体整体剔除（ rarely 承载正文）
                .replaceAll("(?is)<(nav|header|footer|aside|form|iframe|svg|noscript|button|select)[^>]*>.*?</\\1>", " ")
                // class/id 带广告与模板特征的容器（非贪婪匹配，嵌套场景可容忍部分残留）
                .replaceAll("(?is)<(div|section|ul|nav)[^>]*\\s(class|id)=\"[^\"]*"
                        + "(navbar|menu|sidebar|footer|header|breadcrumb|cookie|banner|advert|ads?[-_ ]"
                        + "|promo|subscribe|social|share|related|recommend|comment|popup|modal|search-box)[^\"]*\""
                        + "[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();
    }

    /** 模板噪声行：导航链接、版权声明、Cookie 提示等（仅剔除短行，避免误伤以噪声词开头的正文段落） */
    private static final java.util.regex.Pattern JUNK_LINE = java.util.regex.Pattern.compile(
            "^(?:首页|登录|注册|搜索|导航|下一页|上一页|返回顶部|分享|收藏|打印|举报|更多"
                    + "|版权所有|免责声明|隐私政策|使用条款|联系我们|关于我们|ICP备|公网安备"
                    + "|广告|扫码|下载\\s*.?APP|关注.{0,8}(微信|公众号|微博|抖音)|订阅|阅读全文|查看更多"
                    + "|copyright|all rights reserved|terms of (service|use)|privacy policy"
                    + "|cookie|skip to (content|main)|sign in|sign up|log in).{0,60}$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 剔除模板噪声行：只处理短行（≤40 字符），长段落即使含噪声词开头也保留 */
    static String filterJunkLines(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (line.length() <= 40 && JUNK_LINE.matcher(line.trim()).matches()) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /** 相关段落提取预算：按查询命中保留的段落总量上限 */
    private static final int RELEVANT_MAX_CHARS = 8_000;

    /**
     * 按查询意图提取相关段落：把文本切块（按换行），保留与关键词有命中的块直至预算。
     * 全部未命中时回退「正文开头 + 未命中提示」，保证模型始终有可用内容。
     */
    static String extractRelevant(String text, String query) {
        if (text == null || text.isEmpty() || query == null || query.isBlank()) {
            return text;
        }
        List<String> keywords = keywordsOf(query);
        if (keywords.isEmpty()) {
            return text;
        }
        StringBuilder kept = new StringBuilder();
        int total = 0;
        boolean anyHit = false;
        for (String block : text.split("\\n+")) {
            String plain = block.trim();
            if (plain.isEmpty()) {
                continue;
            }
            if (hits(plain, keywords) > 0) {
                anyHit = true;
                if (total + plain.length() > RELEVANT_MAX_CHARS) {
                    break;
                }
                kept.append(plain).append("\n");
                total += plain.length();
            }
        }
        if (!anyHit) {
            String head = text.length() > 2000 ? text.substring(0, 2000) + "..." : text;
            return "（页面中未命中查询关键词，以下为正文开头部分）\n" + head;
        }
        return kept.toString().trim();
    }

    /** 关键词抽取：拉丁词（≥2 字符）+ 中文词；≥4 字的中文词补充二字滑窗提升命中率 */
    private static List<String> keywordsOf(String query) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\w]+|[\\u4e00-\\u9fa5]+")
                .matcher(query.toLowerCase());
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

    private static java.nio.charset.Charset charsetOf(String contentType) {
        if (contentType != null) {
            var m = java.util.regex.Pattern.compile("charset=([\\w-]+)")
                    .matcher(contentType.toLowerCase());
            if (m.find()) {
                try {
                    return java.nio.charset.Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    // 非法 charset 回退 UTF-8
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }
}
