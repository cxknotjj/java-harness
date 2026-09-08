package com.dark.javaHarness.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ResumeStateStore 单测：状态文件读写往返（临时目录注入）、null 空串化、
 * 文件缺失与未知行宽容语义（与存量手工解析一致）。
 */
class ResumeStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void save_then_load_roundTrip() throws IOException {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("state"));
        store.save("sess-1", "goal-1");

        ResumeStateStore.State state = store.load();

        assertEquals("sess-1", state.sessionId());
        assertEquals("goal-1", state.goalId());
    }

    @Test
    void save_null_writesEmptyLine() throws IOException {
        ResumeStateStore store = new ResumeStateStore(tempDir.resolve("state"));

        store.save(null, null);

        ResumeStateStore.State state = store.load();
        assertEquals("", state.sessionId(), "null 应写为空串（存量语义）");
        assertEquals("", state.goalId());
    }

    @Test
    void load_missingFile_returnsNull() throws IOException {
        assertNull(new ResumeStateStore(tempDir.resolve("nope")).load());
    }

    @Test
    void load_toleratesUnknownLines() throws IOException {
        Path file = tempDir.resolve("state");
        Files.writeString(file, "foo=bar\nsessionId=s2\ngoalId=g2\nextra\n");

        ResumeStateStore.State state = new ResumeStateStore(file).load();

        assertEquals("s2", state.sessionId());
        assertEquals("g2", state.goalId());
    }
}
