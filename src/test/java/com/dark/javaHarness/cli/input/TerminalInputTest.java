package com.dark.javaHarness.cli.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * TerminalInput 单测：降级输入路径（标准行式读取——EOF 返 null、提示符写入输出通道、
 * out() 返回配套通道）与 open() 烟雾（任意环境可打开、可安全关闭，不断言具体实现——
 * 测试环境有无 TTY 因机器而异，read() 在 JLine 实现上会阻塞故不可调用）。
 */
class TerminalInputTest {

    @Test
    void legacy_readsLine_thenEof() {
        ByteArrayInputStream in = new ByteArrayInputStream("你好\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);

        TerminalInput.LineInput input = TerminalInput.legacy(in, out);

        assertEquals("你好", input.read(), "降级输入按行读取");
        assertNull(input.read(), "EOF 返回 null（chatLoop 退出条件）");
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("你> "), "提示符写入输出通道");
    }

    @Test
    void legacy_out_returnsConfiguredChannel() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        TerminalInput.LineInput input = TerminalInput.legacy(new ByteArrayInputStream(new byte[0]), out);

        assertSame(out, input.out(), "out() 返回构造时传入的输出通道");
    }

    @Test
    void open_returnsUsableInput_andShutdownSafe() {
        TerminalInput.LineInput input = TerminalInput.open();

        assertNotNull(input, "任意环境（TTY/无 TTY）都应返回可用输入源");
        assertNotNull(input.out());
        input.shutdown(); // 不抛出（JLine flush / 降级实现空操作）
    }
}
