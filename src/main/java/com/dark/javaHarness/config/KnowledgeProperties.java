package com.dark.javaHarness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库配置（app.knowledge.*）：knowledge/ 目录文档的增量摄取参数、
 * 检索注入参数、嵌入端点与向量库连接。
 *
 * <p>数值唯一来源是 application.yaml（app.knowledge 块）——本类只做绑定载体；
 * 数值键遵循全项目「0 = 不限制」口径（top-k 0 / context-budget 0 / min-query-chars 0
 * 均表示关闭该项约束）。向量库与嵌入仅在 {@code enabled=true} 且首次实际使用时才连接
 * （KnowledgeConfig 全链 @Lazy + ObjectProvider 惰性解析），PG 不可用不影响应用启动。
 */
@Component
@ConfigurationProperties(prefix = "app.knowledge")
public class KnowledgeProperties {

    /** 知识库总开关（false 时 KnowledgeConfig 不装配任何 bean，检索/管理端点报「未启用」） */
    private boolean enabled;

    /** 知识文档目录（相对工作目录），扫描 .md/.txt */
    private String dir;

    /** 检索返回条数（top-k）；0 = 不限条数（实际由相似度阈值兜底） */
    private int topK;

    /** 相似度阈值（0~1，越大越严，低于该值的命中丢弃） */
    private double minScore;

    /** 注入 prompt 的知识片段总预算（token，口径同 tool-result-budget）；0 = 不截断 */
    private int contextBudget;

    /** 触发检索的最短 user 文本长度（字符）；0 = 不设下限 */
    private int minQueryChars;

    /** 单次嵌入请求的 chunk 数上限（DashScope 兼容模式批次硬上限 10 条）；0 = 不分批一次提交 */
    private int embedBatchSize;

    /** 启动时自动增量摄取（需嵌入端点可达，默认关：离线启动不应报错） */
    private boolean autoSyncOnStartup;

    private final Embedding embedding = new Embedding();
    private final Pgvector pgvector = new Pgvector();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public int getContextBudget() {
        return contextBudget;
    }

    public void setContextBudget(int contextBudget) {
        this.contextBudget = contextBudget;
    }

    public int getMinQueryChars() {
        return minQueryChars;
    }

    public void setMinQueryChars(int minQueryChars) {
        this.minQueryChars = minQueryChars;
    }

    public int getEmbedBatchSize() {
        return embedBatchSize;
    }

    public void setEmbedBatchSize(int embedBatchSize) {
        this.embedBatchSize = embedBatchSize;
    }

    public boolean isAutoSyncOnStartup() {
        return autoSyncOnStartup;
    }

    public void setAutoSyncOnStartup(boolean autoSyncOnStartup) {
        this.autoSyncOnStartup = autoSyncOnStartup;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Pgvector getPgvector() {
        return pgvector;
    }

    /** 嵌入端点配置（OpenAI 兼容，key 解析同 ChatClientFactory 约定） */
    public static class Embedding {

        /** OpenAI 兼容嵌入端点 base-url（DashScope 兼容模式） */
        private String baseUrl;

        /** 嵌入模型名（text-embedding-v4） */
        private String model;

        /** 向量维度（PgVectorStore 建列依据；更换嵌入模型/维度需重建 vector_store 表） */
        private int dimensions;

        /** 嵌入 api-key（空则回退环境变量 QWEN_API_KEY，敏感信息不落库） */
        private String apiKey;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /** 知识库专用 PostgreSQL 连接（与业务主库 MySQL 相互独立） */
    public static class Pgvector {

        /** JDBC url（jdbc:postgresql://…），库需预先创建并启用 vector 扩展 */
        private String url;

        private String username;

        private String password;

        /** 向量表名（PgVectorStore initializeSchema 按此自建，与库内其他项目向量表隔离） */
        private String tableName;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
    }
}
