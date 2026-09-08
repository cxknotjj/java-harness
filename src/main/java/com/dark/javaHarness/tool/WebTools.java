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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 网页能力工具集（对标 dsh web 工具的抓取侧）。
 *
 * <p>fetchUrl 经 jsoup 抓取并做 HTML5 解析（编码自动嗅探，GBK 等站点不再乱码），
 * 噪声剔除、主内容提取与 Markdown 转换委托 {@link HtmlToMarkdown}，
 * 按查询意图的相关段落裁剪委托 {@link ContentRelevance}。
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
    /** 内容缓存容量与有效期 */
    private static final int CACHE_MAX = 50;
    private static final long CACHE_TTL_MS = Duration.ofMinutes(30).toMillis();

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
            String body = HtmlToMarkdown.renderBody(doc);
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

    /** 按查询意图提取相关段落（委托 ContentRelevance；包可见入口为既有测试直连保留） */
    static String applyQuery(String body, String query) {
        return ContentRelevance.applyQuery(body, query);
    }

    private static String formatTime(long epochMs) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs));
    }
}
