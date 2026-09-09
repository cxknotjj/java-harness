package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.dto.KnowledgeSource;
import com.dark.javaHarness.domain.dto.PageResult;
import com.dark.javaHarness.domain.entity.KbDocumentEntity;
import com.dark.javaHarness.knowledge.KnowledgeService;
import com.dark.javaHarness.knowledge.KnowledgeSyncView;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库管理接口（RAG 知识面，CLI/调试用）：
 *
 * <p>POST   /api/knowledge/sync            增量摄取 knowledge/ 目录（扫描→比对 mtime→切分→嵌入→入向量库）
 * <p>GET    /api/knowledge/documents       摄取台账分页（doc_name/title/mtime/chunk_count/status）
 * <p>GET    /api/knowledge/search?q=       调试用向量检索（返回命中片段与相关度，不注入 prompt）
 * <p>DELETE /api/knowledge/documents/{name} 删除指定文档（向量 chunk + 台账行）
 *
 * <p>知识库未启用（app.knowledge.enabled=false）时依赖经 ObjectProvider 解析为空，
 * 请求返回 503（GlobalExceptionHandler 统一 {code,message}）；向量库不可用时 sync 抛
 * IllegalStateException、search 静默空（降级口径对齐 KnowledgeServiceImpl）。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeAdminController {

    private final ObjectProvider<KnowledgeService> knowledgeService;

    public KnowledgeAdminController(ObjectProvider<KnowledgeService> knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 增量摄取：knowledge/ 目录 .md/.txt 全量扫描，仅 mtime 变更的文档重嵌入 */
    @PostMapping("/sync")
    public KnowledgeSyncView sync() {
        return requireService().sync();
    }

    /** 摄取台账分页（page 从 1 起，size 默认 20 上限 200） */
    @GetMapping("/documents")
    public PageResult<KbDocumentEntity> documents(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size) {
        return requireService().list(page, size);
    }

    /** 调试检索：直接向量检索（不走 prompt 注入），观察 top-k 命中与相关度 */
    @GetMapping("/search")
    public List<KnowledgeSource> search(@RequestParam("q") String query) {
        return requireService().search(query).stream()
                .map(KnowledgeService.KnowledgeHit::toSource)
                .toList();
    }

    /** 删除文档：向量 chunk（确定性 id 推导）+ 台账行一并删除；不存在返回 404 语义（false） */
    @DeleteMapping("/documents/{name}")
    public boolean delete(@PathVariable String name) {
        return requireService().delete(name);
    }

    /** 知识库服务解析：未启用时 503（服务不可用），文案含开启方式 */
    private KnowledgeService requireService() {
        KnowledgeService service = knowledgeService.getIfAvailable();
        if (service == null) {
            throw new KnowledgeDisabledException();
        }
        return service;
    }

    /** 知识库未启用（app.knowledge.enabled=false）：503 + 开启指引 */
    @org.springframework.web.bind.annotation.ResponseStatus(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
    static class KnowledgeDisabledException extends RuntimeException {
        KnowledgeDisabledException() {
            super("知识库未启用：application.yaml 设置 app.knowledge.enabled=true 并配置 pgvector/嵌入端点后重启");
        }
    }
}
