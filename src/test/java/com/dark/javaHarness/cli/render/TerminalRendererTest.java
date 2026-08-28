package com.dark.javaHarness.cli.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * TerminalRenderer 单测：Markdown 行级渲染规则（纯函数）与回合计数/收尾输出。
 * spinner 定时刷新依赖真实时钟，不在单测覆盖范围。
 */
class TerminalRendererTest {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String GRAY = "\033[90m";
    private static final String RED = "\033[91m";
    private static final String CYAN = "\033[36m";
    private static final String BLUE = "\033[94m";

    // ---- renderInline：行级 Markdown 规则 ----

    @Test
    void heading_rendersBoldBlue() {
        String r = TerminalRenderer.renderInline("## 选型建议");
        assertEquals(BOLD + BLUE + "## 选型建议" + RESET, r);
    }

    @Test
    void listItem_symbolCyanBodyNormal() {
        String r = TerminalRenderer.renderInline("- 第一项要点");
        assertEquals(CYAN + "- " + RESET + "第一项要点", r);
    }

    @Test
    void orderedListItem_supported() {
        String r = TerminalRenderer.renderInline("1. 结论一");
        assertEquals(CYAN + "1. " + RESET + "结论一", r);
    }

    @Test
    void boldSpan_wrapsBold() {
        String r = TerminalRenderer.renderInline("这是**重点**内容");
        assertEquals("这是" + BOLD + "重点" + RESET + "内容", r);
    }

    @Test
    void inlineCode_rendersCyan() {
        String r = TerminalRenderer.renderInline("用 `mvn test` 验证");
        assertEquals("用 " + CYAN + "mvn test" + RESET + " 验证", r);
    }

    @Test
    void plainLine_unchanged() {
        assertEquals("普通文本一行", TerminalRenderer.renderInline("普通文本一行"));
    }

    // ---- 交互行为：进度折叠、token 输出、回合小结 ----

    @Test
    void progressFlow_archivesStagesAndEmitsTokens() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalRenderer renderer = new TerminalRenderer(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        renderer.beginTurn();
        renderer.onProgress("编排", "接收复杂目标，开始编排");     // spinner 起
        renderer.onProgress("拆解", "已布置 1 个子任务");          // 归档 + ✓ 编排
        renderer.onProgress("子任务", "第 1 个子任务完成");        // 归档
        renderer.onProgress("聚合", "汇总子任务结果");             // spinner 起
        renderer.onToken("最终");                                  // 折叠聚合 spinner，token 直出
        renderer.onToken("回答\n");                                // 整行完成 → 渲染输出
        renderer.onToken("尾行无换行");                            // 残留
        renderer.endTurn(true, null);                              // 冲刷残留 + 小结

        String out = buf.toString(StandardCharsets.UTF_8);
        // 阶段折叠归档
        assertTrue(out.contains("✓ 编排"), "编排应被归档: " + out);
        assertTrue(out.contains("✓ 已布置 1 个子任务"), "拆解应被归档: " + out);
        assertTrue(out.contains("✓ 第 1 个子任务完成"), "子任务应被归档: " + out);
        assertTrue(out.contains("✓ 聚合"), "token 到达时应折叠聚合 spinner: " + out);
        // 内容行渲染
        assertTrue(out.contains("最终回答"), "内容应完整输出: " + out);
        assertFalse(out.contains("\b"), "不应出现控制字符残留");
        // 回合小结
        assertTrue(out.contains("回合结束"), "应有回合小结: " + out);
        assertTrue(out.contains("子任务 1 个"), "小结应统计子任务数: " + out);
        assertTrue(out.contains("输出约 10 字"), "小结应统计输出字数: " + out);
    }

    @Test
    void endTurn_failure_rendersRedErrorLine() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalRenderer renderer = new TerminalRenderer(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        renderer.beginTurn();
        renderer.endTurn(false, "g1 模型调用失败");

        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains(RED), "失败行应为红色: " + out);
        assertTrue(out.contains("执行失败"), "应有失败标记: " + out);
        assertTrue(out.contains("g1 模型调用失败"), "应包含错误信息: " + out);
        assertFalse(out.contains("回合结束"), "失败不应输出成功小结: " + out);
    }

    @Test
    void codeBlock_linesRenderCyanWithBar() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalRenderer renderer = new TerminalRenderer(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        renderer.beginTurn();
        renderer.onToken("```python\nprint('hi')\n```\n");
        renderer.endTurn(true, null);

        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("│ print('hi')"), "代码块行应有 │ 前缀: " + out);
        assertTrue(out.contains(CYAN), "代码块应着青色: " + out);
    }

    // ---- 工具调用行：起始 spinner → 结果归档（±行数着色） ----

    @Test
    void toolCall_spinsThenArchivesOnceWithDiff() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalRenderer renderer = new TerminalRenderer(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        renderer.beginTurn();
        renderer.onProgress("tool", "WriteFile(/tmp/a.py)");           // ⏺ spinner 起
        renderer.onProgress("tool-done", "WriteFile ✓ 1.2s · +12/-3 行");
        renderer.endTurn(true, null);

        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("⏺ WriteFile(/tmp/a.py)"), "应展示工具调用起始行: " + out);
        // ✓ 与耗时之间隔着 ANSI 重置序列（✓ 绿色高亮、耗时灰色），分别断言
        assertTrue(out.contains("✓"), "结果行应含成功标记: " + out);
        assertTrue(out.contains("1.2s"), "结果行应含耗时: " + out);
        assertTrue(out.contains("+12"), "diff 新增行数应展示: " + out);
        assertTrue(out.contains("-3"), "diff 删除行数应展示: " + out);
        // 起始 spinner 被静默折叠：不产生 finishSpinnerAsDone 式的「✓ ⏺ …」重复归档行
        assertFalse(out.contains("✓ ⏺"), "起始行不应被二次归档: " + out);
    }

    @Test
    void toolCall_failure_rendersCrossMark() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TerminalRenderer renderer = new TerminalRenderer(
                new PrintStream(buf, true, StandardCharsets.UTF_8));

        renderer.beginTurn();
        renderer.onProgress("tool", "RunShellCommand(python x.py)");
        renderer.onProgress("tool-done", "RunShellCommand ✗ 0.3s");
        renderer.endTurn(true, null);

        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("⏺ RunShellCommand(python x.py)"), "应展示工具调用起始行: " + out);
        // ✗ 红色高亮后跟灰色耗时，ANSI 序列隔断连续匹配，分别断言
        assertTrue(out.contains("✗"), "失败结果行应含 ✗: " + out);
        assertTrue(out.contains("0.3s"), "失败结果行应含耗时: " + out);
    }
}
