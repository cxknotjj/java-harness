package com.dark.javaHarness.cli.render;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 底部状态行 spinner：原位刷新「帧 + 阶段标题 · 详情 (耗时)」，折叠时归档为灰色 ✓ 单行摘要
 * （{@code ✓ 编排 · 3s}），不随内容滚动刷屏。
 *
 * <p>线程契约：门面已用同一把锁串行化所有输出，{@link #start}/{@link #finishAsDone}/
 * {@link #cancel} 须在持有门面锁时调用；仅定时刷新线程自行持锁。spinner 行是唯一可被
 * 擦除重写的「底部行」，任何内容输出前由门面先折叠 spinner，保证状态行永不与正文交错。
 */
final class Spinner {

    private static final char[] FRAMES = {'|', '/', '-', '\\'};

    private final Object lock;
    /** 输出流经 supplier 取用：门面可能在运行时重定向（JLine 模式切 terminal.output()） */
    private final Supplier<PrintStream> out;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cli-spinner");
                t.setDaemon(true);
                return t;
            });

    private boolean active;
    private String stage = "";
    private String detail = "";
    private long startMs;
    private int tick;
    private ScheduledFuture<?> task;

    Spinner(Object lock, Supplier<PrintStream> out) {
        this.lock = lock;
        this.out = out;
    }

    /** 起 spinner（空标题守卫：无阶段名的行不得进入，否则折叠成空白 ✓ 行） */
    void start(String stage, String detail) {
        if (stage == null || stage.isBlank()) {
            return;
        }
        finishAsDone();
        this.stage = stage;
        this.detail = detail == null ? "" : detail;
        this.startMs = System.currentTimeMillis();
        this.tick = 0;
        this.active = true;
        refresh();
        task = timer.scheduleAtFixedRate(this::refreshScheduled, 120, 120, TimeUnit.MILLISECONDS);
    }

    /** 停止 spinner 并归档为灰色摘要行；无活动 spinner 时不输出 */
    void finishAsDone() {
        if (!active) {
            return;
        }
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        active = false;
        long sec = (System.currentTimeMillis() - startMs) / 1000;
        PrintStream o = out.get();
        o.print(Ansi.CLEAR_LINE + Ansi.GRAY + "✓ " + stage + " · " + sec + "s" + Ansi.RESET + "\n");
        o.flush();
    }

    /** 静默折叠：只擦行不归档（工具结果行自带摘要，无需重复 ✓ 行） */
    void cancel() {
        if (!active) {
            return;
        }
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        active = false;
        PrintStream o = out.get();
        o.print(Ansi.CLEAR_LINE);
        o.flush();
    }

    private void refreshScheduled() {
        synchronized (lock) {
            refresh();
        }
    }

    private void refresh() {
        if (!active) {
            return;
        }
        long sec = (System.currentTimeMillis() - startMs) / 1000;
        PrintStream o = out.get();
        o.print("\r" + Ansi.GRAY + FRAMES[tick++ % FRAMES.length]
                + " " + stage + (detail.isBlank() ? "" : " · " + detail)
                + " (" + sec + "s)" + Ansi.RESET + "\033[K");
        o.flush();
    }
}
