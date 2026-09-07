package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * WebTools 单测：本地 JDK HttpServer 承载 fixture 页面（不走外网），走真实 Jsoup.connect
 * 链路验证抓取、编码嗅探（GBK）、去噪主内容提取、Markdown 输出、内容缓存与错误分类。
 */
class WebToolsTest {

    private static final String ARTICLE_HTML = """
            <html><head><title>奶龙百科</title></head>
            <body>
            <nav><a href="/home">首页</a> <a href="/login">登录</a></nav>
            <div class="sidebar-banner">广告：扫码领烤肠</div>
            <article>
              <h1>奶龙简介</h1>
              <p>奶龙是一只来自异星的幼龙，肚子圆滚滚，最喜欢吃烤肠。它和小七是最好的朋友，每天 duang duang 地跳来跳去，给身边的人带来快乐，走到哪里都是欢乐的中心。</p>
              <p>外形特征：黄色的圆胖身体，短手短脚，笑起来眼睛弯成月牙。<strong>重点是爱吃</strong>，任何食物都逃不过它的肚子。</p>
              <ul>
                <li>特征一：<strong>圆滚滚</strong>的肚子</li>
                <li>特征二：爱吃<em>烤肠</em>，参见<a href="/more">详细介绍</a></li>
              </ul>
              <pre><code>print("奶龙 duang duang")</code></pre>
              <blockquote>网友评价：看完只想吃烤肠。</blockquote>
              <table><tr><th>属性</th><th>值</th></tr><tr><td>物种</td><td>异星幼龙</td></tr></table>
            </article>
            <footer>版权所有 © 奶龙百科</footer>
            </body></html>
            """;

    private static final String GBK_HTML = """
            <html><head><meta charset="gbk"><title>GBK测试</title></head><body>
            <p>奶龙爱吃烤肠，肚子圆滚滚。</p>
            <p>第二段：小七和奶龙是最好的朋友，duang duang。</p>
            </body></html>
            """;

    private static final String CACHE_HTML = """
            <html><head><title>缓存页</title></head><body>
            <p>缓存测试页面内容，用于验证二次抓取直接回放缓存而非重新请求网络。</p>
            </body></html>
            """;

    private static HttpServer server;
    private static String base;
    private static final AtomicInteger cacheHits = new AtomicInteger();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/article", ex ->
                serve(ex, 200, ARTICLE_HTML.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"));
        server.createContext("/gbk", ex ->
                serve(ex, 200, GBK_HTML.getBytes(Charset.forName("GBK")), "text/html"));
        server.createContext("/plain", ex ->
                serve(ex, 200, "这是一段纯文本内容，直接透传给模型。".getBytes(StandardCharsets.UTF_8),
                        "text/plain; charset=utf-8"));
        server.createContext("/pdf", ex ->
                serve(ex, 200, "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8), "application/pdf"));
        server.createContext("/notfound", ex -> serve(ex, 404, new byte[0], "text/html"));
        server.createContext("/forbidden", ex -> serve(ex, 403, new byte[0], "text/html"));
        server.createContext("/cache", ex -> {
            cacheHits.incrementAndGet();
            serve(ex, 200, CACHE_HTML.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
        });
        server.createContext("/long", ex ->
                serve(ex, 200, longPage().getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void serve(HttpExchange ex, int status, byte[] body, String contentType) throws IOException {
        try {
            ex.getResponseHeaders().set("Content-Type", contentType);
            if (body.length == 0) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.sendResponseHeaders(status, body.length);
                ex.getResponseBody().write(body);
            }
        } finally {
            ex.close();
        }
    }

    private static String longPage() {
        StringBuilder sb = new StringBuilder("<html><head><title>长页</title></head><body>");
        String para = "奶龙肚皮圆滚滚爱吃烤肠，duang duang 跳来跳去，快乐的一天又开始了，小七和奶龙一起分享美食与欢笑，生活简单又幸福。";
        for (int i = 0; i < 200; i++) {
            sb.append("<p>第").append(i).append("段：").append(para).append("</p>");
        }
        return sb.append("</body></html>").toString();
    }

    /* ---------------- 完整抓取链路（真实 Jsoup.connect → localhost） ---------------- */

    @Test
    void fetchUrl_article_extractsMainContentAsMarkdown() {
        String out = new WebTools().fetchUrl(base + "/article", null);

        assertTrue(out.contains("【网页】奶龙百科"), "元数据头含标题: " + out);
        assertTrue(out.contains("来源: " + base + "/article"), "元数据头含最终 URL: " + out);
        assertTrue(out.contains("# 奶龙简介"), "标题转 Markdown: " + out);
        assertTrue(out.contains("**圆滚滚**的肚子"), "strong 加粗保留: " + out);
        assertTrue(out.contains("爱吃*烤肠*"), "em 斜体保留: " + out);
        assertTrue(out.contains("[详细介绍](" + base + "/more)"), "相对链接转绝对: " + out);
        assertTrue(out.contains("```") && out.contains("print(\"奶龙 duang duang\")"), "pre 代码块保留: " + out);
        assertTrue(out.contains("> 网友评价：看完只想吃烤肠。"), "blockquote 保留: " + out);
        assertTrue(out.contains("| 属性 | 值 |") && out.contains("| 物种 | 异星幼龙 |"), "表格转管道表: " + out);
        // 噪声剔除
        assertFalse(out.contains("登录"), "nav 应剔除: " + out);
        assertFalse(out.contains("扫码领烤肠"), "广告侧栏应剔除: " + out);
        assertFalse(out.contains("版权所有"), "footer 应剔除: " + out);
        // 正文保留
        assertTrue(out.contains("肚子圆滚滚"), "正文段落保留: " + out);
    }

    @Test
    void fetchUrl_gbkPage_charsetSniffedFromMeta() {
        String out = new WebTools().fetchUrl(base + "/gbk", null);

        assertTrue(out.contains("奶龙爱吃烤肠"), "GBK 页面应正确解码（header 未带 charset，嗅探 meta）: " + out);
        assertTrue(out.contains("第二段"), "GBK 中文不应乱码: " + out);
    }

    @Test
    void fetchUrl_plainText_passthrough() {
        String out = new WebTools().fetchUrl(base + "/plain", null);

        assertTrue(out.contains("这是一段纯文本内容"), "text/plain 应透传: " + out);
    }

    @Test
    void fetchUrl_withQuery_keepsOnlyHitBlocks() {
        String out = new WebTools().fetchUrl(base + "/article", "烤肠");

        assertTrue(out.contains("烤肠"), "命中段落保留: " + out);
        assertFalse(out.contains("# 奶龙简介"), "未命中块应剔除: " + out);
    }

    @Test
    void fetchUrl_withQuery_noHit_fallsBackToHead() {
        String out = new WebTools().fetchUrl(base + "/article", "量子力学");

        assertTrue(out.contains("未命中查询关键词"), "未命中应有提示: " + out);
        assertTrue(out.contains("奶龙简介"), "回退正文开头: " + out);
    }

    /* ---------------- 内容缓存（内容式，非拒绝式） ---------------- */

    @Test
    void fetchUrl_sameUrlTwice_replaysCachedContent_withoutSecondRequest() {
        WebTools tools = new WebTools();

        String first = tools.fetchUrl(base + "/cache", null);
        String second = tools.fetchUrl(base + "/cache", null);
        String withFragment = tools.fetchUrl(base + "/cache#section", null);

        assertFalse(first.contains("来自缓存"), "首次应真实抓取: " + first);
        assertTrue(second.contains("缓存测试页面内容"), "二次应返回缓存内容: " + second);
        assertTrue(second.contains("来自缓存"), "二次应带缓存提示: " + second);
        assertTrue(withFragment.contains("来自缓存"), "同 URL 去锚点后仍命中缓存: " + withFragment);
        assertEquals(1, cacheHits.get(), "同 URL（含锚点变体）只应请求网络一次");
    }

    @Test
    void fetchUrl_failedFetch_notCached_canRetry() {
        WebTools tools = new WebTools();

        String first = tools.fetchUrl(base + "/notfound", null);
        String second = tools.fetchUrl(base + "/notfound", null);

        assertTrue(first.startsWith("工具执行失败"), "失败返回错误文本: " + first);
        assertTrue(second.startsWith("工具执行失败"), "失败不入缓存，允许重试: " + second);
    }

    /* ---------------- 错误分类（模型可读、可自愈） ---------------- */

    @Test
    void fetchUrl_rejectsNonHttpProtocols() {
        WebTools tools = new WebTools();
        assertTrue(tools.fetchUrl("file:///etc/passwd", null).contains("仅支持 http/https"));
        assertTrue(tools.fetchUrl("ftp://example.com/x", null).contains("仅支持 http/https"));
    }

    @Test
    void fetchUrl_httpErrors_classifiedWithStatusCode() {
        WebTools tools = new WebTools();
        assertTrue(tools.fetchUrl(base + "/notfound", null).contains("HTTP 404"), "404 分类: ");
        assertTrue(tools.fetchUrl(base + "/forbidden", null).contains("HTTP 403"), "403 分类: ");
    }

    @Test
    void fetchUrl_nonHtmlContent_classifiedAsUnsupported() {
        String out = new WebTools().fetchUrl(base + "/pdf", null);

        assertTrue(out.contains("非网页内容"), "PDF 应拒绝并提示: " + out);
        assertTrue(out.contains("application/pdf"), "错误含 Content-Type: " + out);
    }

    @Test
    void fetchUrl_connectionRefused_classified() {
        String out = new WebTools().fetchUrl("http://127.0.0.1:1/refused", null);

        assertTrue(out.contains("连接被拒绝"), "连接拒绝分类: " + out);
    }

    @Test
    void fetchUrl_unknownHost_classified() {
        String out = new WebTools().fetchUrl("http://this-host-definitely-does-not-exist.invalid/", null);

        assertTrue(out.contains("域名无法解析"), "DNS 失败分类: " + out);
    }

    @Test
    void fetchUrl_invalidUrl_classified() {
        assertTrue(new WebTools().fetchUrl("not-a-url", null).contains("仅支持 http/https"), "非法 URL 分类");
    }

    /* ---------------- 截断 ---------------- */

    @Test
    void fetchUrl_overlongPage_truncatedWithTotalLengthNote() {
        String out = new WebTools().fetchUrl(base + "/long", null);

        assertTrue(out.contains("[已截断至 12000 字符"), "超长应截断并带尾注: " + out);
        assertTrue(out.contains("可带 query 参数只取相关段落"), "尾注应引导模型换 query: " + out);
        assertFalse(out.contains("第199段"), "截断后不应包含页面末尾内容: " + out);
    }

    /* ---------------- 纯函数：错误分类与 query 提取 ---------------- */

    @Test
    void classifyError_mapsStatusAndMime() {
        var hse = new org.jsoup.HttpStatusException("fetch error", 404, "http://x");
        assertTrue(WebTools.classifyError(hse).contains("404"));
        var mte = new org.jsoup.UnsupportedMimeTypeException("mime", "application/pdf", "http://x");
        assertTrue(WebTools.classifyError(mte).contains("application/pdf"));
        assertTrue(WebTools.classifyError(new IllegalArgumentException("bad")).contains("URL 非法"));
    }

    @Test
    void applyQuery_splitByMarkdownParagraph_keepsHitsOnly() {
        String body = "## 标题\n\n段落A 提到烤肠。\n\n段落B 无关内容。";
        String kept = WebTools.applyQuery(body, "烤肠");
        assertTrue(kept.contains("段落A"));
        assertFalse(kept.contains("段落B"));
        assertFalse(kept.contains("## 标题"));
    }
}
