package com.dark.javaHarness.prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * skill Markdown 技能库（prompt 动态加载·子项 1）：扫描技能目录的 .md 文件，
 * 解析 front-matter 元数据与正文，按文件 mtime 热重载——放入/修改/删除文件，
 * 下一请求即生效，无需重启。
 *
 * <p>文件格式（Markdown + 简单 front-matter，逐行 key: value 手写解析不引依赖）：
 * <pre>
 * ---
 * name: web-research          # 可选，缺省=文件名去 .md
 * description: 一句话用途      # 可选，缺省=正文首个非空行
 * agents: researcher, general # 可选，缺省=全部 agent 可见
 * ---
 * 正文（完整技能说明，load_skill 返回的就是这段）
 * </pre>
 *
 * <p>容错：目录缺失视为空技能面（info 一次）；坏文件/坏 front-matter warn 跳过不抛，
 * 且失败结果按 mtime 缓存（文件不变不重试、不重复刷 warn）。
 */
@Component
public class SkillRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillRepository.class);

    /** 技能条目：agents 为 null/空表示对所有 agent 可见 */
    public record Skill(String name, String description, Set<String> agents, String body) {

        /** 该 agent 是否可见（agents 未声明 = 全部可见） */
        public boolean visibleTo(String agentName) {
            return agents == null || agents.isEmpty()
                    || (agentName != null && agents.contains(agentName));
        }
    }

    /** 缓存条目：mtime + 解析结果（skill 为 null 表示该 mtime 下解析失败，不重试不重复 warn） */
    private record Cached(long mtime, Skill skill) {
    }

    private final Path dir;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public SkillRepository(@Value("${app.prompt.skills.dir:skills}") String dir) {
        this.dir = Path.of(dir == null || dir.isBlank() ? "skills" : dir.trim());
        if (!Files.isDirectory(this.dir)) {
            log.info("[skill] 技能目录不存在（{}），技能面为空——创建目录并放入 .md 文件即可热装配",
                    this.dir.toAbsolutePath());
        }
    }

    /** 某 agent 可见的技能清单（按名称排序）；目录缺失/无可见技能返回空表 */
    public List<Skill> skillsFor(String agentName) {
        List<Skill> visible = new ArrayList<>();
        for (Skill skill : scan()) {
            if (skill.visibleTo(agentName)) {
                visible.add(skill);
            }
        }
        return List.copyOf(visible);
    }

    /** 按名取技能（忽略大小写；可见性校验——越权名视为不存在） */
    public Optional<Skill> find(String name, String agentName) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.trim();
        return scan().stream()
                .filter(skill -> skill.name().equalsIgnoreCase(key) && skill.visibleTo(agentName))
                .findFirst();
    }

    /** 扫描目录：mtime 变化才重新解析（热重载） */
    private List<Skill> scan() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Skill> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .forEach(p -> {
                        Skill skill = load(p);
                        if (skill != null) {
                            out.add(skill);
                        }
                    });
        } catch (IOException e) {
            log.warn("[skill] 扫描技能目录失败（{}）: {}", dir, e.toString());
        }
        out.sort(Comparator.comparing(Skill::name));
        return out;
    }

    /** 单文件加载：mtime 未变走缓存（含失败缓存），变化才重新解析 */
    private Skill load(Path file) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            log.warn("[skill] 读取文件时间失败，跳过 {}: {}", file, e.toString());
            return null;
        }
        String key = file.getFileName().toString();
        Cached cached = cache.get(key);
        if (cached != null && cached.mtime() == mtime) {
            return cached.skill();
        }
        Skill parsed = parse(file);
        cache.put(key, new Cached(mtime, parsed));
        if (parsed != null) {
            log.info("[skill] 已加载技能 '{}'（{}）", parsed.name(), key);
        }
        return parsed;
    }

    /** 解析单个 .md：front-matter（--- 包围，逐行 key: value）+ 正文；失败返回 null 并 warn */
    static Skill parse(Path file) {
        String fallbackName = file.getFileName().toString().replaceAll("(?i)\\.md$", "");
        try {
            String text = Files.readString(file).stripLeading();
            Map<String, String> meta = new HashMap<>();
            String body = text;
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
                    body = String.join("\n", Arrays.copyOfRange(lines, close + 1, lines.length)).strip();
                } else {
                    // 未闭合的 front-matter：按无 front-matter 处理，整文件为正文
                    meta.clear();
                }
            }
            String name = valueOf(meta, "name", fallbackName);
            String description = valueOf(meta, "description", firstLineOf(body));
            String agentsRaw = valueOf(meta, "agents", "");
            Set<String> agents = agentsRaw.isBlank() ? null
                    : Arrays.stream(agentsRaw.split("[,，\\s]+"))
                            .filter(s -> !s.isBlank())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new Skill(name, description == null ? "" : description, agents, body);
        } catch (Exception e) {
            log.warn("[skill] 解析技能文件失败（{}）: {}", file, e.toString());
            return null;
        }
    }

    private static String valueOf(Map<String, String> meta, String key, String fallback) {
        String value = meta.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 正文首个非空行（description 缺省值）；无内容返回空串 */
    private static String firstLineOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        for (String line : body.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }
}
