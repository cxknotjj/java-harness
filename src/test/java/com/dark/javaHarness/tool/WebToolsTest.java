package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WebTools 单测：HTML → 文本纯函数 + 协议白名单（不实测外网）。
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
    void fetchUrl_rejectsNonHttpProtocols() {
        String result = tools.fetchUrl("file:///etc/passwd");
        assertTrue(result.startsWith("工具执行失败"), "file 协议应被拒绝: " + result);

        String ftp = tools.fetchUrl("ftp://example.com/x");
        assertTrue(ftp.startsWith("工具执行失败"), "ftp 协议应被拒绝: " + ftp);
    }

    @Test
    void fetchUrl_invalidUrlFailsGracefully() {
        String result = tools.fetchUrl("ht!tp://bad url");
        assertTrue(result.startsWith("工具执行失败"), "非法 URL 应返回错误文本: " + result);
    }
}
