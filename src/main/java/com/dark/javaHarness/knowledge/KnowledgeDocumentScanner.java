package com.dark.javaHarness.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识文档扫描器：扫描知识目录的 .md/.txt 文件，解析 front-matter 标题与正文，
 * 供增量摄取（KnowledgeService.sync）与 chunk 切分消费。
 *
 * <p>模式照抄 {@link com.dark.javaHarness.prompt.SkillRepository}（目录扫描、mtime、
 * 坏文件 warn 跳过不抛），但不在本层缓存——增量判据（name + mtime）在 kb_document 表，
 * 服务层按表比对决定是否重新摄取，文件级缓存无意义。
 *
 * <p>文件格式（Markdown / 纯文本 + 可选 front-matter）：
 * <pre>
 * ---
 * title: 文档标题          # 可选；缺省取首个 # 标题行，再缺省用文件名
 * ---
 * 正文（按标题/段落切分入库）
 * </pre>
 */
@Component
public class KnowledgeDocumentScanner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentScanner.class);

    /** 单个待摄取文档：name 为唯一摄取键（文件名），title 用于出处展示 */
    public record KbFile(String name, String title, long mtime, String text) {
    }

    private final Path dir;

    public KnowledgeDocumentScanner(@Value("${app.knowledge.dir:knowledge}") String dir) {
        this.dir = Path.of(dir == null || dir.isBlank() ? "knowledge" : dir.trim());
        if (!Files.isDirectory(this.dir)) {
            log.info("[knowledge] 知识目录不存在（{}），知识面为空——创建目录放入 .md/.txt 后调 POST /api/knowledge/sync",
                    this.dir.toAbsolutePath());
        }
    }

    /** 扫描知识目录全部有效文档（按名称排序）；目录缺失/为空返回空表 */
    public List<KbFile> scan() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<KbFile> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> Files.isRegularFile(p))
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".md") || n.endsWith(".txt");
                    })
                    .forEach(p -> {
                        KbFile file = load(p);
                        if (file != null) {
                            out.add(file);
                        }
                    });
        } catch (IOException e) {
            log.warn("[knowledge] 扫描知识目录失败（{}）: {}", dir, e.toString());
        }
        out.sort(java.util.Comparator.comparing(KbFile::name));
        return out;
    }

    /** 单文件加载：失败 warn 跳过返回 null（对齐 SkillRepository 容错口径） */
    private KbFile load(Path file) {
        String name = file.getFileName().toString();
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            Parsed parsed = parse(Files.readString(file), name);
            return new KbFile(name, parsed.title(), mtime, parsed.text());
        } catch (Exception e) {
            log.warn("[knowledge] 解析知识文件失败（{}）: {}", name, e.toString());
            return null;
        }
    }

    record Parsed(String title, String text) {
    }

    /** 解析 front-matter（--- 包围，逐行 key: value）取 title，正文剥掉 front-matter 块 */
    static Parsed parse(String raw, String fallbackName) {
        String fallbackTitle = fallbackName.replaceAll("(?i)\\.(md|txt)$", "");
        String text = raw.stripLeading();
        Map<String, String> meta = new HashMap<>();
        if (text.startsWith("---")) {
            String[] lines = text.split("\r?\n", -1);
            int close = -1;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.equals("---")) {
                    close = i;
                    break;
                }
                int idx = line.indexOf(':');
                if (idx > 0) {
                    meta.putIfAbsent(line.substring(0, idx).trim().toLowerCase(Locale.ROOT),
                            line.substring(idx + 1).trim());
                }
            }
            if (close > 0) {
                text = String.join("\n", java.util.Arrays.copyOfRange(lines, close + 1, lines.length)).strip();
            } else {
                meta.clear(); // 未闭合 front-matter：整文件按正文处理
            }
        }
        String title = meta.getOrDefault("title", "");
        if (title.isBlank()) {
            title = firstHeading(text);
        }
        return new Parsed(title.isBlank() ? fallbackTitle : title, text);
    }

    /** 首个 Markdown 一级/二级标题行（# / ## 开头，去井号）作标题兜底 */
    private static String firstHeading(String text) {
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                return trimmed.replaceAll("^#+\\s*", "").trim();
            }
        }
        return "";
    }
}
