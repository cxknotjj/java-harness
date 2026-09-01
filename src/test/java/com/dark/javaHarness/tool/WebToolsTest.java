package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WebTools 单测：HTML → 文本纯函数 + 噪声过滤 + 查询相关提取 + 协议白名单（不实测外网）。
 */
class WebToolsTest {

    private WebTools tools;

    @BeforeEach
    void setUp() {
        tools = new WebTools();
    }

    @Test
    void htmlToText_stripsTagsScriptAndStyle() {
        String html = "<html><head><style>.x{color:red}</style>"
                + "<script>alert(1)</script></head>"
                + "<body><h1>标题</h1><p>段落&amp;实体</p><!-- 注释 --></body></html>";
        String text = WebTools.htmlToText(html);
        assertTrue(text.contains("标题"), text);
        assertTrue(text.contains("段落&实体"), text);
        assertFalse(text.contains("alert"), "script 内容应被去除: " + text);
        assertFalse(text.contains("color:red"), "style 内容应被去除: " + text);
        assertFalse(text.contains("注释"), text);
        assertFalse(text.contains("<"), "不应残留标签: " + text);
    }

    @Test
    void htmlToText_stripsStructuralNoise() {
        String html = "<html><body>"
                + "<nav><a>首页</a><a>产品</a></nav>"
                + "<header><h1>站名</h1></header>"
                + "<div class=\"main-content\"><p>这是正文中段</p></div>"
                + "<aside class=\"sidebar\">热门推荐</aside>"
                + "<footer><p>版权所有 XX 公司</p></footer>"
                + "</body></html>";
        String text = WebTools.htmlToText(html);
        assertFalse(text.contains("首页"), "nav 应整体剔除: " + text);
        assertFalse(text.contains("站名"), "header 应整体剔除: " + text);
        assertFalse(text.contains("热门推荐"), "aside 应整体剔除: " + text);
        assertFalse(text.contains("版权所有"), "footer 应整体剔除: " + text);
        assertTrue(text.contains("这是正文中段"), "正文应保留: " + text);
    }

    @Test
    void filterJunkLines_removesShortTemplateLines_keepsParagraphs() {
        String text = "这是第一段有效内容，包含较多信息。\n"
                + "首页\n"
                + "版权所有 © 2026 某某公司\n"
                + "Privacy Policy\n"
                + "这是第二段有效内容，同样包含较多信息，不应被误删。";
        String filtered = WebTools.filterJunkLines(text);
        assertFalse(filtered.contains("首页"), "模板行应被剔除: " + filtered);
        assertFalse(filtered.contains("版权所有"), "版权行应被剔除: " + filtered);
        assertFalse(filtered.contains("Privacy Policy"), "英文模板行应被剔除: " + filtered);
        assertTrue(filtered.contains("这是第一段"), "正文应保留: " + filtered);
        assertTrue(filtered.contains("这是第二段"), "正文应保留: " + filtered);
    }

    @Test
    void fetchUrl_rejectsNonHttpProtocols() {
        String result = tools.fetchUrl("file:///etc/passwd", null);
        assertTrue(result.startsWith("工具执行失败"), "file 协议应被拒绝: " + result);

        String ftp = tools.fetchUrl("ftp://example.com/x", null);
        assertTrue(ftp.startsWith("工具执行失败"), "ftp 协议应被拒绝: " + ftp);
    }

    @Test
    void fetchUrl_invalidUrlFailsGracefully() {
        String result = tools.fetchUrl("ht!tp://bad url", null);
        assertTrue(result.startsWith("工具执行失败"), "非法 URL 应返回错误文本: " + result);
    }

    /** 固定返回给定正文的桩：不发起真实网络请求 */
    private static class StubPageWebTools extends WebTools {
        private final String page;

        StubPageWebTools(String page) {
            this.page = page;
        }

        @Override
        String doFetch(String url) {
            return page;
        }
    }

    @Test
    void fetchUrl_withQuery_keepsOnlyRelevantBlocks() {
        StubPageWebTools stub = new StubPageWebTools("奶茶品牌 A 的门店数达到三万家。\n"
                + "品牌 A 的海外门店扩张至越南与印尼。\n"
                + "今日天气预报：晴，25 度。\n"
                + "某明星的演唱会门票售罄。");
        String out = stub.fetchUrl("https://example.com/news", "门店规模 门店数");

        assertTrue(out.contains("三万家"), "命中段应保留: " + out);
        assertTrue(out.contains("海外门店"), "命中段应保留: " + out);
        assertFalse(out.contains("天气预报"), "无关段应被剔除: " + out);
        assertFalse(out.contains("演唱会"), "无关段应被剔除: " + out);
    }

    @Test
    void fetchUrl_withQuery_noHit_fallsBackToHead() {
        StubPageWebTools stub = new StubPageWebTools("完全不相关的开头内容。\n后续内容。");
        String out = stub.fetchUrl("https://example.com/x", "量子计算");

        assertTrue(out.startsWith("（页面中未命中查询关键词"), "未命中应回退正文开头: " + out);
        assertTrue(out.contains("完全不相关的开头内容"), "回退应包含开头: " + out);
    }

    @Test
    void fetchUrl_withoutQuery_returnsFullText() {
        StubPageWebTools stub = new StubPageWebTools("第一段。\n第二段。");
        String out = stub.fetchUrl("https://example.com/x", null);
        assertEquals("第一段。\n第二段。", out, "不传 query 应原样返回（仅噪声过滤）");
    }

    /** 统计 doFetch 调用次数的桩：不发起真实网络请求 */
    private static class CountingWebTools extends WebTools {
        int invocations;

        @Override
        String doFetch(String url) {
            invocations++;
            return "内容[" + url + "]";
        }
    }

    @Test
    void fetchUrl_sameUrlTwice_secondHitDedupeGuard() {
        CountingWebTools counting = new CountingWebTools();
        String first = counting.fetchUrl("https://example.com/page", null);
        assertTrue(first.contains("内容["), "首次应真实抓取: " + first);

        String second = counting.fetchUrl("https://example.com/page#section", null);
        assertTrue(second.contains("已抓取过"), "同 URL（含锚点差异）第二次应命中防护: " + second);
        assertEquals(1, counting.invocations, "第二次不应发起网络请求");
    }

    @Test
    void fetchUrl_failedFetch_notCached_canRetry() {
        CountingWebTools counting = new CountingWebTools() {
            @Override
            String doFetch(String url) {
                invocations++;
                return "工具执行失败: HTTP 503（页面不可用或被拒）";
            }
        };
        counting.fetchUrl("https://example.com/down", null);
        counting.fetchUrl("https://example.com/down", null);
        assertEquals(2, counting.invocations, "失败的抓取不应入缓存，允许重试");
    }
}
