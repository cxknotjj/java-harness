package com.dark.javaHarness.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SkillRepository 单测（prompt 动态加载·子项 1）：
 * - front-matter 解析：name/description/agents 显式声明与缺省回退
 * - agents 过滤：声明了 agents 的技能仅对清单内 agent 可见，缺省全可见
 * - mtime 热重载：文件修改后下一此扫描即生效（免重启）
 * - 容错：目录缺失/坏文件跳过不抛
 */
class SkillRepositoryTest {

    @TempDir
    Path dir;

    private SkillRepository repo() {
        return new SkillRepository(dir.toString());
    }

    @Test
    void explicitFrontMatter_parsed() throws Exception {
        Files.writeString(dir.resolve("a.md"), """
                ---
                name: my-skill
                description: 自定义描述
                agents: researcher, general
                ---
                正文第一行
                其余正文
                """);
        List<SkillRepository.Skill> skills = repo().skillsFor("researcher");
        assertEquals(1, skills.size());
        SkillRepository.Skill s = skills.get(0);
        assertEquals("my-skill", s.name());
        assertEquals("自定义描述", s.description());
        assertEquals("正文第一行\n其余正文", s.body());
        assertTrue(s.visibleTo("researcher"));
        assertTrue(s.visibleTo("general"));
        assertTrue(!s.visibleTo("coder"));
    }

    @Test
    void defaults_nameFromFilename_descriptionFromBody_agentsAllVisible() throws Exception {
        Files.writeString(dir.resolve("web-research.md"), """
                # 先空两行前的空行也应跳过

                第二行
                """);
        SkillRepository.Skill s = repo().skillsFor("coder").get(0);
        assertEquals("web-research", s.name(), "name 缺省=文件名去 .md");
        assertEquals("# 先空两行前的空行也应跳过", s.description(), "description 缺省=正文首个非空行");
        assertNull(s.agents(), "agents 缺省=null（全部可见）");
        assertTrue(s.visibleTo("任意agent"));
    }

    @Test
    void agentsFilter_onlyListedAgentsSee() throws Exception {
        Files.writeString(dir.resolve("only-coder.md"), """
                ---
                description: coder 专属
                agents: coder
                ---
                正文
                """);
        assertEquals(1, repo().skillsFor("coder").size());
        assertTrue(repo().skillsFor("researcher").isEmpty());
        assertTrue(repo().find("only-coder", "researcher").isEmpty(), "越权名视为不存在");
        assertTrue(repo().find("only-coder", "coder").isPresent());
    }

    @Test
    void hotReload_modifiedFile_reflectedOnNextScan() throws Exception {
        Path file = dir.resolve("s.md");
        Files.writeString(file, "---\nname: s\ndescription: v1\n---\nbody");
        assertEquals("v1", repo().skillsFor("x").get(0).description());
        // 修改内容 + 显式推进 mtime（文件系统 mtime 粒度可能秒级，同毫秒写入不可靠）
        Files.writeString(file, "---\nname: s\ndescription: v2\n---\nbody");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals("v2", repo().skillsFor("x").get(0).description(), "mtime 变化后热重载");
    }

    @Test
    void unclosedFrontMatter_treatedAsPlainBody() throws Exception {
        Files.writeString(dir.resolve("broken.md"), "---\nname: unclosed\n正文没有闭合线");
        SkillRepository.Skill s = repo().skillsFor("x").get(0);
        assertEquals("broken", s.name(), "未闭合 front-matter 整体按正文处理（meta 清空），name=文件名");
        assertEquals(1, repo().skillsFor("x").size());
    }

    @Test
    void missingDir_returnsEmpty() {
        SkillRepository missing = new SkillRepository(dir.resolve("no-such").toString());
        assertTrue(missing.skillsFor("general").isEmpty());
        assertTrue(missing.find("x", "general").isEmpty());
    }

    @Test
    void skillsSortedByName() throws Exception {
        Files.writeString(dir.resolve("b.md"), "---\nname: beta\n---\n1");
        Files.writeString(dir.resolve("a.md"), "---\nname: alpha\n---\n2");
        List<SkillRepository.Skill> skills = repo().skillsFor("x");
        assertEquals(List.of("alpha", "beta"), skills.stream().map(SkillRepository.Skill::name).toList());
    }
}
