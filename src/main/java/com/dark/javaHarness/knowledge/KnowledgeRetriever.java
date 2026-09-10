package com.dark.javaHarness.knowledge;

import com.dark.javaHarness.config.KnowledgeProperties;
import com.dark.javaHarness.domain.dto.KnowledgeSource;
import com.dark.javaHarness.tool.TokenEstimator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识检索器（RAG 注入面）：按当前 user 文本做向量检索，渲染【出处N】知识块供
 * system prompt 注入（挂载点在 {@code AgentRequestSpecFactory}——路径 A/B 唯一
 * system prompt 汇合点，两条路径零各自改动）。
 *
 * <p>角色策略（仿 MemoryPolicy 先例）：aggregator 跳过——其材料是各子任务结果而非
 * 自身提问，按 user 文本检索语义不成立；lead/子任务专家按各自 user 文本检索（更精准）。
 *
 * <p>降级口径：知识库未启用/向量库不可用/无命中/查询过短均返回 null——请求规格
 * 退化为现状，绝不阻断主链路（对齐沙箱降级先例）。
 *
 * <p>出处透出：命中后按 session 记录最近来源（{@link #recentSources}，有界
 * ConcurrentHashMap，Demo 规模 pragmatic 方案：超限整体清空），ChatServiceImpl 组
 * meta.sources 时取用。
 *
 * <p>装配：由 {@code KnowledgeConfig} 条件装配（app.knowledge.enabled=true 时才有 bean），
 * 本类不加 @Component——禁用时依赖的 KnowledgeService 亦不存在。
 */
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);

    /** 不做知识检索的角色（aggregator 的材料是子任务结果，非自身提问） */
    private static final Set<String> SKIP_ROLES = Set.of("aggregator");

    /** session→来源表上限（超过整体清空：防无界增长，出处属增强信息允许丢） */
    private static final int MAX_SESSIONS = 512;

    private static final String BLOCK_HEADER =
            "【知识库检索结果】以下是从知识库中检索到的与当前问题可能相关的片段：\n";

    private static final String BLOCK_FOOTER = """

            引用要求：以上片段仅作参考；回答中引用了某片段的陈述，请在句末标注对应【出处N】；\
            片段与问题无关时忽略，禁止编造出处。""";

    private final KnowledgeService knowledgeService;
    private final KnowledgeProperties props;

    /** session → 最近一次命中的来源（保序，命中分数降序）；出处透出用 */
    private final ConcurrentHashMap<String, List<KnowledgeSource>> recentSources = new ConcurrentHashMap<>();

    public KnowledgeRetriever(KnowledgeService knowledgeService, KnowledgeProperties props) {
        this.knowledgeService = knowledgeService;
        this.props = props;
    }

    /**
     * 构建知识块（无命中/跳过/降级返回 null，调用方不注入）。
     *
     * @param agentName 角色名（aggregator 策略跳过）
     * @param sessionId 会话 ID（来源记录键；路由判定等无会话场景不记录）
     * @param user      当前 user 文本（检索 query）
     * @param kbs       该 agent 绑定的知识库列表（{@link #parseBinding} 解析 agent 表
     *                  knowledge 列；null = 不限，检索全部知识）
     */
    public String buildKnowledgeBlock(String agentName, String sessionId, String user, List<String> kbs) {
        if (agentName != null && SKIP_ROLES.contains(agentName)) {
            return null;
        }
        String query = user == null ? "" : user.strip();
        if (query.isEmpty()) {
            return null;
        }
        if (props.getMinQueryChars() > 0 && query.length() < props.getMinQueryChars()) {
            return null;
        }
        List<KnowledgeService.KnowledgeHit> hits = knowledgeService.search(query, kbs);
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        // 逐条累加渲染，超预算停止（contextBudget=0 不截断）：整条进出保语义完整，
        // 禁止半截片段；命中已按分数降序，丢弃的是尾部低分命中
        StringBuilder body = new StringBuilder();
        int used = TokenEstimator.estimateTokens(BLOCK_HEADER) + TokenEstimator.estimateTokens(BLOCK_FOOTER);
        int cited = 0;
        for (KnowledgeService.KnowledgeHit hit : hits) {
            String piece = renderCitation(cited + 1, hit);
            int cost = TokenEstimator.estimateTokens(piece);
            if (props.getContextBudget() > 0 && used + cost > props.getContextBudget()) {
                log.debug("[knowledge] 知识块达预算上限（{} token），{} 条命中截留 {} 条",
                        props.getContextBudget(), hits.size(), cited);
                break;
            }
            body.append(piece);
            used += cost;
            cited++;
        }
        if (cited == 0) {
            return null;
        }
        remember(sessionId, hits.subList(0, cited));
        return BLOCK_HEADER + body + BLOCK_FOOTER;
    }

    /**
     * 解析 agent 表 knowledge 列原文（逗号分隔 kb 标识）→ 去空去重的库列表；
     * 空白/全空段返回 null（= 不限，检索全部知识，兼容未绑定行为）。
     * 仿 ToolAssignments 的 CSV 解析口径；单引号等表达式清洗统一由
     * {@code KnowledgeServiceImpl.kbFilterExpression} 在过滤表达式生成处收口。
     */
    public static List<String> parseBinding(String binding) {
        if (binding == null || binding.isBlank()) {
            return null;
        }
        List<String> kbs = java.util.Arrays.stream(binding.split(","))
                .map(String::trim)
                .filter(kb -> !kb.isEmpty())
                .distinct()
                .toList();
        return kbs.isEmpty() ? null : kbs;
    }

    /** 该会话最近一次知识命中的来源（保序；无记录返回空列表）——meta.sources 组装用 */
    public List<KnowledgeSource> recentSources(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<KnowledgeSource> sources = recentSources.get(sessionId);
        return sources == null ? List.of() : List.copyOf(sources);
    }

    /** 单条引用渲染：出处头（序号/标题/文件名/相关度）+ 片段原文 + 空行分隔 */
    private static String renderCitation(int index, KnowledgeService.KnowledgeHit hit) {
        return String.format("【出处%d】《%s》（%s，相关度 %.2f）%n%s%n%n",
                index, hit.title(), hit.docName(), hit.score(), hit.text());
    }

    /** 记录会话来源（有界：超 MAX_SESSIONS 整体清空，出处属增强信息允许丢） */
    private void remember(String sessionId, List<KnowledgeService.KnowledgeHit> hits) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (recentSources.size() >= MAX_SESSIONS && !recentSources.containsKey(sessionId)) {
            recentSources.clear();
        }
        List<KnowledgeSource> sources = new ArrayList<>(hits.size());
        for (KnowledgeService.KnowledgeHit hit : hits) {
            sources.add(hit.toSource());
        }
        recentSources.put(sessionId, List.copyOf(sources));
    }
}
