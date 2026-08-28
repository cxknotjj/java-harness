package com.dark.javaHarness.tool;

import com.dark.javaHarness.agent.ProgressLine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具调用追踪器：把 {@link ToolCallback} 装饰为「可观测」版本——
 * 调用前发 {@code tool} 起始事件（工具名 + 参数摘要），调用后发 {@code tool-done} 结果事件
 * （成功/失败 + 耗时 + 文件类变更行数摘要）。
 *
 * <p>事件复用 {@link ProgressLine} 线协议（stage=tool / tool-done），走既有 SSE 进度通道
 * 直达 CLI 的「工具调用行」展示（⏺ 工具名(参数) → ✓ 耗时 · +N/-M 行）。
 * 追踪是纯旁路：不改变工具 schema（定义原样透传，模型不可见差异）、不改写入参与结果、
 * 失败原样抛出（仅在抛出前补发失败事件）。
 *
 * <p>emitter 由调用方保证线程安全（多 Agent 并行时经 Sink 串行化发射）。
 */
public final class ToolCallTracer {

    /** 起始事件 stage：detail 形如 {@code WriteFile(/tmp/a.py)} */
    public static final String STAGE_TOOL = "tool";
    /** 结果事件 stage：detail 形如 {@code WriteFile ✓ 1.2s · +12/-3 行} */
    public static final String STAGE_TOOL_DONE = "tool-done";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 参数摘要提取的候选键（按序取第一个非空标量） */
    private static final String[] SUMMARY_KEYS = {
            "path", "file_path", "filepath", "url", "command", "code", "query", "dir", "directory",
            "text"};

    /** diff 摘要的「旧值/新值」候选键：两者皆有 → +新/-旧行数；仅新值 → +新行数 */
    private static final String[] OLD_KEYS = {"old_string", "old_text", "oldStr", "search"};
    private static final String[] NEW_KEYS = {"new_string", "new_text", "newStr", "replace",
            "content", "text"};

    private ToolCallTracer() {
    }

    /**
     * 装饰回调列表为追踪版本；emitter 为 null 时原样返回（零开销直通）。
     */
    public static List<ToolCallback> trace(List<ToolCallback> callbacks, Consumer<String> emitter) {
        if (emitter == null || callbacks.isEmpty()) {
            return callbacks;
        }
        List<ToolCallback> out = new ArrayList<>(callbacks.size());
        for (ToolCallback cb : callbacks) {
            out.add(new TracedToolCallback(cb, emitter));
        }
        return out;
    }

    /**
     * 把 {@code @Tool} 注解对象转为 ToolCallback 再装饰（追踪模式下双通道统一为回调单通道）。
     */
    public static List<ToolCallback> traceAnnotated(List<Object> annotated, Consumer<String> emitter) {
        if (emitter == null || annotated.isEmpty()) {
            return List.of();
        }
        return trace(List.of(ToolCallbacks.from(annotated.toArray())), emitter);
    }

    // ================================================================
    // 事件组装（包内可见纯函数，便于单测）
    // ================================================================

    /** 起始事件：{@code WriteFile(/tmp/a.py)}；参数摘要取候选键值或截断原文 */
    static String startDetail(String toolName, String toolInput) {
        return toolName + "(" + argSummary(toolInput) + ")";
    }

    /** 结果事件：{@code WriteFile ✓ 1.2s · +12/-3 行}；diff 摘要仅文件写入/编辑类可得 */
    static String doneDetail(String toolName, String toolInput, boolean ok, long costMillis) {
        String sec = String.format(java.util.Locale.ROOT, "%.1f", costMillis / 1000.0);
        String diff = diffSummary(toolInput);
        return toolName + (ok ? " ✓ " : " ✗ ") + sec + "s" + (diff.isEmpty() ? "" : " · " + diff);
    }

    /** 参数摘要：候选键第一个非空值（截 60 字符），兜底截断原始 JSON */
    static String argSummary(String toolInput) {
        JsonNode v = firstValue(toolInput, SUMMARY_KEYS);
        if (v != null) {
            return truncate(v.asText(), 60);
        }
        return truncate(toolInput == null ? "" : toolInput.replaceAll("\\s+", " "), 60);
    }

    /** diff 摘要：找到新旧值时 {@code +N/-M 行}，仅新值时 {@code +N 行}，找不到则空串 */
    static String diffSummary(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return "";
        }
        JsonNode oldV = firstValue(toolInput, OLD_KEYS);
        JsonNode newV = firstValue(toolInput, NEW_KEYS);
        int oldLines = oldV == null ? -1 : countLines(oldV.asText());
        int newLines = newV == null ? -1 : countLines(newV.asText());
        if (oldLines >= 0 && newLines >= 0) {
            return "+" + newLines + "/-" + oldLines + " 行";
        }
        if (newLines >= 0) {
            return "+" + newLines + " 行";
        }
        return "";
    }

    /** 按候选键序取第一个「非空文本标量」值 */
    private static JsonNode firstValue(String toolInput, String... keys) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            if (!root.isObject()) {
                return null;
            }
            for (String key : keys) {
                JsonNode v = root.path(key);
                if (v.isValueNode() && !v.asText().isBlank()) {
                    return v;
                }
            }
        } catch (Exception ignored) {
            // 入参非 JSON：摘要走截断兜底
        }
        return null;
    }

    /** 文本行数（\n 计数 +1；空文本算 0 行） */
    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ================================================================
    // 装饰器
    // ================================================================

    /** 透明装饰：schema/元数据原样透传，仅 call 前后发事件；失败原样抛出 */
    private record TracedToolCallback(ToolCallback delegate, Consumer<String> emitter)
            implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            long start = System.currentTimeMillis();
            String name = delegate.getToolDefinition().name();
            emitter.accept(ProgressLine.encode(STAGE_TOOL, startDetail(name, toolInput)));
            try {
                String result = delegate.call(toolInput);
                emitter.accept(ProgressLine.encode(STAGE_TOOL_DONE,
                        doneDetail(name, toolInput, true, System.currentTimeMillis() - start)));
                return result;
            } catch (RuntimeException e) {
                emitter.accept(ProgressLine.encode(STAGE_TOOL_DONE,
                        doneDetail(name, toolInput, false, System.currentTimeMillis() - start)));
                throw e;
            }
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            long start = System.currentTimeMillis();
            String name = delegate.getToolDefinition().name();
            emitter.accept(ProgressLine.encode(STAGE_TOOL, startDetail(name, toolInput)));
            try {
                String result = delegate.call(toolInput, toolContext);
                emitter.accept(ProgressLine.encode(STAGE_TOOL_DONE,
                        doneDetail(name, toolInput, true, System.currentTimeMillis() - start)));
                return result;
            } catch (RuntimeException e) {
                emitter.accept(ProgressLine.encode(STAGE_TOOL_DONE,
                        doneDetail(name, toolInput, false, System.currentTimeMillis() - start)));
                throw e;
            }
        }
    }
}
