package com.dark.javaHarness.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @Tool(description = "抓取指定 URL 网页并转为纯文本（去脚本与标签，超长截断），用于阅读网页内容；仅支持 http/https")
    public String fetchUrl(@ToolParam(description = "要抓取的完整 URL") String url) {
        long start = System.currentTimeMillis();
        try {
            String result = doFetch(url);
            log.info("[tool] fetchUrl ({}) -> {} 字符, {}ms", url,
                    result == null ? 0 : result.length(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("[tool] fetchUrl ({}) 失败: {}", url, e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    private String doFetch(String url) throws Exception {
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
                .replaceAll("(?is)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();
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
