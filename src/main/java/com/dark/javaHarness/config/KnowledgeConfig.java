package com.dark.javaHarness.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.dark.javaHarness.knowledge.KnowledgeRetriever;
import com.dark.javaHarness.knowledge.KnowledgeService;
import com.dark.javaHarness.knowledge.KnowledgeServiceImpl;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RAG 知识库装配（app.knowledge.enabled=true 时生效）：向量库专用数据源 + 嵌入模型 +
 * PgVectorStore + 摄取/检索服务。
 *
 * <p>降级语义（对齐 SandboxToolProvider 先例）：PG / 嵌入端点不可用时应用照常启动——
 * <ul>
 *   <li>整条向量库链路 {@code @Lazy}：启动零连接（Hikari 连接池也只在首次 getConnection 时建立），
 *       {@code initializeSchema} 的建表 DDL 同样推迟到首次实际使用；</li>
 *   <li>{@link KnowledgeServiceImpl} / {@link KnowledgeRetriever} 持 {@link ObjectProvider}
 *       惰性解析 VectorStore，首次使用失败按「检索空 / 同步报清晰错误」降级，不影响主链路；</li>
 *   <li>不引 pgvector autoconfigure starter：项目主数据源是 MySQL，其「唯一 DataSource」
 *       假设不成立，故手动装配；vector_store 表由 PgVectorStore 自建，不经 Flyway（主库
 *       schema 管理仍是 MySQL）。</li>
 * </ul>
 *
 * <p>嵌入模型手动构建 OpenAI 兼容 {@link OpenAiEmbeddingModel}（端点/模型/维度来自
 * app.knowledge.embedding.*，api-key 解析同 ChatClientFactory 约定：yaml 键优先，
 * 空则回退环境变量 QWEN_API_KEY），不经 spring.ai.openai 自动配置的 ChatModel。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeConfig.class);

    /**
     * 知识库专用 PostgreSQL 数据源（真懒连接）：initializationFailTimeout=0 使 Hikari
     * 构造时不建池验证（首次 getConnection 才真实连接）；方法体 @Lazy 防「参数名与 bean 名
     * 匹配」的 byName 解析路径在装配期强实例化（否则 PG 未就绪会炸启动）。
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public HikariDataSource vectorDataSource(KnowledgeProperties props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.getPgvector().getUrl());
        cfg.setUsername(props.getPgvector().getUsername());
        cfg.setPassword(props.getPgvector().getPassword());
        cfg.setPoolName("kb-pgvector");
        cfg.setMaximumPoolSize(4);
        cfg.setConnectionTimeout(5_000);
        cfg.setInitializationFailTimeout(0);
        return new HikariDataSource(cfg);
    }

    @Bean
    @Lazy
    public JdbcTemplate vectorJdbcTemplate(@Lazy DataSource vectorDataSource) {
        return new JdbcTemplate(vectorDataSource);
    }

    /**
     * OpenAI 兼容嵌入模型（独立 OpenAiApi，不复用 ChatModel）：端点 base-url / 模型名 /
     * 维度来自 yaml，api-key 走 ChatClientFactory 同款约定（显式键 &gt; QWEN_API_KEY 环境变量）。
     */
    @Bean
    @Lazy
    public EmbeddingModel knowledgeEmbeddingModel(KnowledgeProperties props, Environment env) {
        String apiKey = props.getEmbedding().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = env.getProperty("QWEN_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "知识库嵌入未配置 api-key（app.knowledge.embedding.api-key 或环境变量 QWEN_API_KEY）");
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.getEmbedding().getBaseUrl())
                .apiKey(apiKey)
                .build();
        log.info("[knowledge] 嵌入模型就绪: {} @ {}（{} 维）",
                props.getEmbedding().getModel(), props.getEmbedding().getBaseUrl(),
                props.getEmbedding().getDimensions());
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(props.getEmbedding().getModel())
                        .dimensions(props.getEmbedding().getDimensions())
                        .build());
    }

    /**
     * pgvector 向量库（vector_store 表按 dimensions 自建，CREATE IF NOT EXISTS）：
     * TEXT 主键便于确定性 chunk id（docName#idx，增量摄取先删旧行后写入）。
     */
    @Bean
    @Lazy
    public VectorStore vectorStore(@Lazy JdbcTemplate vectorJdbcTemplate,
                                   @Lazy EmbeddingModel knowledgeEmbeddingModel,
                                   KnowledgeProperties props) {
        return PgVectorStore.builder(vectorJdbcTemplate, knowledgeEmbeddingModel)
                .dimensions(props.getEmbedding().getDimensions())
                .idType(PgVectorStore.PgIdType.TEXT)
                .initializeSchema(true)
                .build();
    }

    @Bean
    public KnowledgeService knowledgeService(ObjectProvider<VectorStore> vectorStore,
                                             com.dark.javaHarness.knowledge.KnowledgeDocumentScanner scanner,
                                             com.dark.javaHarness.mapper.KbDocumentMapper kbDocumentMapper,
                                             KnowledgeProperties props) {
        return new KnowledgeServiceImpl(vectorStore, scanner, kbDocumentMapper, props);
    }

    /** 检索增强器（挂 AgentRequestSpecFactory，路径 A/B 唯一请求组装汇合点） */
    @Bean
    public KnowledgeRetriever knowledgeRetriever(KnowledgeService knowledgeService,
                                                 KnowledgeProperties props) {
        return new KnowledgeRetriever(knowledgeService, props);
    }
}
