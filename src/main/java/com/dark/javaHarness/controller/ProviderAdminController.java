package com.dark.javaHarness.controller;

import com.dark.javaHarness.domain.dto.ProviderAddRequest;
import com.dark.javaHarness.domain.dto.ProviderAddResult;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.service.ProviderAdminService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型供应商管理接口：供 CLI 增补 model_provider 映射，免去手写 SQL 与重启。
 *
 * <p>GET  /api/providers          全量映射行（含禁用行）
 * <p>POST /api/providers          新增供应商/模型映射并热刷新注册表（免重启生效）
 * <p>校验失败返回 400（GlobalExceptionHandler 统一 {code,message}）。
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderAdminController {

    private final ProviderAdminService providerAdminService;

    public ProviderAdminController(ProviderAdminService providerAdminService) {
        this.providerAdminService = providerAdminService;
    }

    @GetMapping
    public List<ModelProviderEntity> list() {
        return providerAdminService.list();
    }

    @PostMapping
    public ProviderAddResult add(@RequestBody ProviderAddRequest request) {
        return providerAdminService.add(request);
    }
}
