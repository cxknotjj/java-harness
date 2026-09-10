package com.dark.javaHarness.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * KnowledgeDocumentScanner 单测：front-matter 解析、标题兜底链、目录扫描过滤与缺失降级。
 */
class KnowledgeDocumentScannerTest {

    @TempDir
    Path dir;

    @Test
    void parse_frontMatter_extractsTitleAndStripsHeader() {
        KnowledgeDocumentScanner.Parsed parsed = KnowledgeDocumentScanner.parse(
                "---\ntitle: 部署手册\n---\n\n正文第一行。\n\n正文第二行。", "fallback.md");
        assertEquals("部署手册", parsed.title());
        assertEquals("正文第一行。\n\n正文第二行。", parsed.text());
    }

    @Test
    void parse_noFrontMatter_titleFromFirstHeading() {
        KnowledgeDocumentScanner.Parsed parsed = KnowledgeDocumentScanner.parse(
                "引言段。\n\n## 安装步骤\n\n内容", "fallback.md");
        assertEquals("安装步骤", parsed.title());
        assertTrue(parsed.text().startsWith("引言段。"), "无 front-matter 时正文应原样保留");
    }

    @Test
    void parse_noTitleAnywhere_fallsBackToFileName() {
        KnowledgeDocumentScanner.Parsed parsed = KnowledgeDocumentScanner.parse("纯正文没有标题", "notes.md");
        assertEquals("notes", parsed.title(), "兜底标题应去掉扩展名");
    }

    @Test
    void parse_unclosedFrontMatter_treatedAsPlainBody() {
        // 未闭合的 front-matter：头部不剥离、不解析（meta 作废），标题走兜底
        KnowledgeDocumentScanner.Parsed parsed = KnowledgeDocumentScanner.parse(
                "---\ntitle: 未闭合\n正文", "x.md");
        assertEquals("x", parsed.title());
        assertTrue(parsed.text().startsWith("---"));
    }

    @Test
    void scan_mdAndTxtOnly_ignoresOtherExtensions() throws IOException {
        Files.writeString(dir.resolve("a.md"), "---\ntitle: A文档\n---\n\nA 正文");
        Files.writeString(dir.resolve("b.txt"), "# B 标题\n\nB 正文");
        Files.writeString(dir.resolve("c.adoc"), "被忽略的格式");
        Files.createDirectory(dir.resolve("sub")); // 空子目录不产生条目

        KnowledgeDocumentScanner scanner = new KnowledgeDocumentScanner(dir.toString());
        List<KnowledgeDocumentScanner.KbFile> files = scanner.scan();

        assertEquals(2, files.size());
        assertEquals("a.md", files.get(0).name(), "结果按名称排序");
        assertEquals("A文档", files.get(0).title());
        assertEquals("B 标题", files.get(1).title());
        assertTrue(files.get(0).mtime() > 0, "mtime 用于增量比对");
    }

    @Test
    void scan_subdirs_becomeKnowledgeBases() throws IOException {
        Files.writeString(dir.resolve("root.md"), "根目录公共文档");
        Files.createDirectory(dir.resolve("java"));
        Files.writeString(dir.resolve("java").resolve("spring.md"), "# Spring\n内容");
        Files.createDirectory(dir.resolve("frontend"));
        Files.writeString(dir.resolve("frontend").resolve("vue.md"), "Vue 内容");
        Files.createDirectory(dir.resolve("java").resolve("nested"));
        Files.writeString(dir.resolve("java").resolve("nested").resolve("deep.md"), "更深层不递归");

        KnowledgeDocumentScanner scanner = new KnowledgeDocumentScanner(dir.toString());
        List<KnowledgeDocumentScanner.KbFile> files = scanner.scan();

        assertEquals(3, files.size());
        // 结果按 name 排序：frontend/vue.md < java/spring.md < root.md
        // 子目录文档：kb=一级子目录名，name 带前缀（唯一摄取键）
        assertEquals("frontend", files.get(0).kb());
        assertEquals("frontend/vue.md", files.get(0).name());
        assertEquals("java", files.get(1).kb());
        assertEquals("java/spring.md", files.get(1).name());
        // 根目录文档：kb=default，name 无前缀
        assertEquals("default", files.get(2).kb());
        assertEquals("root.md", files.get(2).name());
    }

    @Test
    void scan_missingDir_returnsEmptyWithoutThrowing() {
        KnowledgeDocumentScanner scanner = new KnowledgeDocumentScanner(
                dir.resolve("not-exists").toString());
        assertTrue(scanner.scan().isEmpty(), "知识目录不存在应返回空（知识面为空）");
    }
}
