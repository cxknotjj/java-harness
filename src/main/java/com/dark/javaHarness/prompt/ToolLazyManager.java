package com.dark.javaHarness.prompt;

import com.dark.javaHarness.tool.ToolAssignments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具 Schema 延迟加载管理器：会话级两段式工具暴露（spec 子项 6）。
 *
 * <p>首轮请求只注入「轻量态」工具（名称 + 一句话用途，参数 schema 置空），压缩请求级
 * 工具 schema 的固定 token 开销（MCP 大 schema 随每次 LLM 调用全量重发的问题）；模型需要
 * 某工具时调用内置元工具 {@link #EXPAND_TOOL_NAME expand_tool(toolName)}：服务端把该工具
 * 加入会话级已展开集合并返回完整参数说明；已展开工具在其后请求中按完整 schema 注入。
 * 未展开工具被直接调用时返回引导文本（提示先 expand），不执行真实逻辑、不抛异常——模型可自愈。
 *
 * <p>装配说明：本类只依赖 {@link ToolAssignments}（纯工具面数据与用途元数据）与开关布尔值，
 * 不依赖 AgentService/Agent bean，做成共享 bean 无循环依赖（AgentServiceImpl 急切注入全部
 * Agent bean 亦不受影响）；路径 A 与编排路径 B 共用同一实例 → 同一会话的展开集跨路径通用。
 *
 * <p>包装顺序约定（与 {@link ToolCallTracer} / {@link ToolCallBudget} 的关系）：
 * tracer/预算装饰真实工具在前，本类的 {@link #process} 在最外层加工——已展开工具透传的
 * 是装饰后 callback（工具行/预算照常生效）；expand_tool 元工具由本类追加、不经 tracer
 * （元工具不产生工具行噪声）、不经预算（纯元数据操作，不占真实执行额度）。
 *
 * <p>开关 app.prompt.lazy-tools.enabled（默认 true）：关闭时 {@link #process} 原样透传、
 * 不注册 expand_tool，回退全量注入现状。会话展开集存内存（应用重启需重新 expand，
 * expand 零成本可接受）；expand 重复调用幂等。
 */
public class ToolLazyManager {

    /** 内置元工具名：按工具名展开完整参数说明 */
    public static final String EXPAND_TOOL_NAME = "expand_tool";

    /** 轻量态参数 schema：properties 置空的最小 object 定义（模型端可接受） */
    static final String EMPTY_INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    /** expand_tool 的最小参数定义（toolName: string, required） */
    static final String EXPAND_INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"toolName\":{\"type\":\"string\","
                    + "\"description\":\"要展开的工具名\"}},\"required\":[\"toolName\"]}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具用途元数据来源（可 null：单测/防御场景，描述回退真实 ToolDefinition） */
    private final ToolAssignments toolAssignments;
    /** 延迟加载开关（app.prompt.lazy-tools.enabled） */
    private final boolean enabled;

    /** 会话级已展开集合：sessionId → 已展开工具名（ConcurrentHashMap.newKeySet；单用户场景无限增长可接受） */
    private final ConcurrentHashMap<String, Set<String>> expandedBySession = new ConcurrentHashMap<>();

    public ToolLazyManager(ToolAssignments toolAssignments, boolean enabled) {
        this.toolAssignments = toolAssignments;
        this.enabled = enabled;
    }

    /** 开关状态（PromptAssembler 工具索引段据此决定是否追加 expand 引导） */
    public boolean isEnabled() {
        return enabled;
    }

    /** 会话已展开工具名快照（只读；无记录/空会话返回空集） */
    public Set<String> expandedToolNames(String sessionId) {
        Set<String> set = sessionId == null ? null : expandedBySession.get(sessionId);
        return set == null || set.isEmpty() ? Set.of() : Set.copyOf(set);
    }

    /**
     * 直接展开（幂等）：把工具名加入会话已展开集合。
     * 越权校验（是否在该请求分配面内）由 expand_tool 元工具负责，本方法面向已知合法调用方不做校验。
     */
    public void expand(String sessionId, String toolName) {
        if (sessionId == null || sessionId.isBlank() || toolName == null || toolName.isBlank()) {
            return;
        }
        expandedBySession.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(toolName);
    }

    /**
     * 核心 API：加工请求级工具面——已展开工具原样透传完整 schema，未展开工具轻量包装，
     * 末尾追加 expand_tool 元工具（元素工具按当前请求真实工具面构建，作为越权校验依据）。
     *
     * <p>透传规则：开关关闭、sessionId 缺失（理论上不发生的防御）、或原始面为空时原样返回；
     * 空面不追加元工具——无工具的 agent 不需要 expand 能力，也避免空工具用例行为漂移。
     */
    public List<ToolCallback> process(String sessionId, List<ToolCallback> callbacks) {
        if (!enabled || callbacks == null || callbacks.isEmpty()
                || sessionId == null || sessionId.isBlank()) {
            return callbacks == null ? List.of() : callbacks;
        }
        Set<String> expandedNames =
                expandedBySession.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        List<ToolCallback> out = new ArrayList<>(callbacks.size() + 1);
        for (ToolCallback cb : callbacks) {
            out.add(expandedNames.contains(cb.getToolDefinition().name())
                    ? cb : new LazyToolCallback(sessionId, cb));
        }
        out.add(new ExpandToolCallback(sessionId, callbacks));
        return out;
    }

    // ================================================================
    // 轻量态包装
    // ================================================================

    /**
     * 轻量态包装：保留工具名与一句话用途（ToolAssignments.purposeOf，未登记回退真实描述）、
     * 参数 schema 置空；call 默认不执行真实工具、返回引导文本。
     *
     * <p>例外（同请求自愈）：会话内已展开的工具（如同请求内刚 expand 过）直接透传执行
     * 真实工具——本请求工具面在请求发出时已定格为轻量 schema，若 expand 后的正式调用
     * 仍返回引导文本，模型会陷入「已 expand 仍被拒」的循环；调用时按会话展开集动态放行。
     */
    private final class LazyToolCallback implements ToolCallback {

        private final String sessionId;
        private final ToolCallback delegate;

        private LazyToolCallback(String sessionId, ToolCallback delegate) {
            this.sessionId = sessionId;
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            ToolDefinition real = delegate.getToolDefinition();
            return ToolDefinition.builder()
                    .name(real.name())
                    .description(descriptionOf(real))
                    .inputSchema(EMPTY_INPUT_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return expandedNow() ? delegate.call(toolInput) : guidance();
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return expandedNow() ? delegate.call(toolInput, toolContext) : guidance();
        }

        /** 调用时点该工具是否已加入会话展开集（跨请求已展开的不会走到这里，process 已透传） */
        private boolean expandedNow() {
            String name = delegate.getToolDefinition().name();
            Set<String> set = sessionId == null ? null : expandedBySession.get(sessionId);
            return set != null && set.contains(name);
        }

        /** 引导文本（中文、面向模型可自愈）：提示先 expand 获取参数说明再正式调用 */
        private String guidance() {
            String name = delegate.getToolDefinition().name();
            return "工具 " + name + " 未展开（轻量态，参数说明未注入），本次调用未执行。"
                    + "请先调用 expand_tool 工具（参数 toolName=\"" + name
                    + "\"）展开获取完整参数说明，再正式调用。";
        }

        /** 轻量态一句话描述：优先 ToolAssignments 用途元数据，未登记回退真实描述 */
        private String descriptionOf(ToolDefinition real) {
            String purpose = toolAssignments == null ? "" : toolAssignments.purposeOf(real.name());
            return purpose.isBlank() ? real.description() : purpose;
        }
    }

    // ================================================================
    // expand_tool 元工具
    // ================================================================

    /**
     * expand_tool 元工具：展开指定工具并返回完整参数说明。
     * 每次 {@link #process} 按当前请求的真实工具面构建——越权校验依据即「该会话当前请求
     * 分配的工具面」，未分配工具拒绝展开（服务端硬边界，与工具分配权限一致）。
     */
    private final class ExpandToolCallback implements ToolCallback {

        private final String sessionId;
        /** 当前请求分配的真实工具面（name → callback，先到者优先；越权校验 + 完整说明来源） */
        private final Map<String, ToolCallback> allowedTools;

        private ExpandToolCallback(String sessionId, List<ToolCallback> realCallbacks) {
            this.sessionId = sessionId;
            this.allowedTools = new HashMap<>();
            for (ToolCallback cb : realCallbacks) {
                allowedTools.putIfAbsent(cb.getToolDefinition().name(), cb);
            }
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(EXPAND_TOOL_NAME)
                    .description("按工具名展开指定工具的完整参数说明（当前工具面为轻量态）："
                            + "正式调用某工具前，先调用本工具展开它。参数 toolName 为工具名。")
                    .inputSchema(EXPAND_INPUT_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return doExpand(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return doExpand(toolInput);
        }

        private String doExpand(String toolInput) {
            String toolName = extractToolName(toolInput);
            if (toolName == null) {
                return "expand_tool 调用失败：缺少 toolName 参数（string 类型，值为要展开的工具名），请修正后重试。";
            }
            ToolCallback target = allowedTools.get(toolName);
            if (target == null) {
                // 越权防护：不在该请求分配面内的工具拒绝展开、不入会话集合
                return "拒绝展开：工具 " + toolName + " 不在当前分配的工具面内，"
                        + "只能展开工具索引中列出的工具。";
            }
            // Set.add 幂等：重复展开不重复入集，仍返回完整说明（模型多轮重试无损）
            boolean added = expandedBySession
                    .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                    .add(toolName);
            ToolDefinition real = target.getToolDefinition();
            String purpose = toolAssignments == null ? "" : toolAssignments.purposeOf(toolName);
            if (purpose.isBlank()) {
                purpose = real.description();
            }
            String head = added
                    ? "已展开工具 " + toolName + "："
                    : "工具 " + toolName + " 此前已展开（幂等），完整说明如下：";
            return head + "\n"
                    + "- 用途：" + purpose + "\n"
                    + "- 完整参数 schema：" + real.inputSchema() + "\n"
                    + "该工具已加入本会话已展开集合，后续请求将按完整 schema 注入，"
                    + "现在即可按上述参数说明正式调用。";
        }

        /** 从工具入参 JSON 提取 toolName（兼容 tool_name 别名、字符串入参与非 JSON 裸名兜底） */
        private String extractToolName(String toolInput) {
            if (toolInput == null || toolInput.isBlank()) {
                return null;
            }
            try {
                JsonNode root = MAPPER.readTree(toolInput);
                if (root.isObject()) {
                    for (String key : new String[]{"toolName", "tool_name"}) {
                        JsonNode v = root.path(key);
                        if (v.isValueNode() && !v.asText().isBlank()) {
                            return v.asText().trim();
                        }
                    }
                    return null;
                }
                if (root.isTextual() && !root.asText().isBlank()) {
                    return root.asText().trim();
                }
                return null;
            } catch (Exception ignored) {
                // 非 JSON 入参兜底：去引号后当作裸工具名
                String bare = toolInput.trim();
                if (bare.length() >= 2 && bare.startsWith("\"") && bare.endsWith("\"")) {
                    bare = bare.substring(1, bare.length() - 1).trim();
                }
                return bare.isEmpty() ? null : bare;
            }
        }
    }
}
