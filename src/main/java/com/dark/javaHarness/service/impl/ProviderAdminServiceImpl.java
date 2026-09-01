package com.dark.javaHarness.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.dto.ProviderAddRequest;
import com.dark.javaHarness.domain.dto.ProviderAddResult;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import com.dark.javaHarness.service.ProviderAdminService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模型供应商管理实现。
 *
 * 模型名是 model_provider 的唯一键（uk_model）：add 时按模型名分流——
 * 已存在的行做 update（换供应商/换端点/重启用），不存在的行 insert，
 * 全部落库后热刷新 ChatClientRegistry 立即生效。
 */
@Service
public class ProviderAdminServiceImpl implements ProviderAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProviderAdminServiceImpl.class);

    private final ModelProviderMapper mapper;
    private final ChatClientRegistry registry;

    public ProviderAdminServiceImpl(ModelProviderMapper mapper, ChatClientRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
    }

    @Override
    public List<ModelProviderEntity> list() {
        return mapper.selectList(null);
    }

    @Override
    public ProviderAddResult add(ProviderAddRequest request) {
        validate(request);
        int status = request.resolvedStatus();

        // 一次查出请求涉及的已存在端点，避免逐个 select。
        // 判定键 = (provider, model) 小写复合（与 uk_provider_model 对齐）：
        // 跨供应商允许同名模型（腾讯与官方的 deepseek-v4-flash 各自成行），
        // 同供应商内同名 = 更新既有端点（换 api_url / 重启用）
        Set<String> models = new HashSet<>(request.models());
        Map<String, ModelProviderEntity> existing = mapper.selectList(
                        new LambdaQueryWrapper<ModelProviderEntity>()
                                .in(ModelProviderEntity::getModel, models)).stream()
                .filter(e -> request.provider().equalsIgnoreCase(e.getProvider()))
                .collect(Collectors.toMap(
                        e -> key(e.getProvider(), e.getModel()), Function.identity()));

        int added = 0;
        int updated = 0;
        for (String model : models) {
            String normalized = model.trim();
            ModelProviderEntity row = existing.get(key(request.provider(), normalized));
            if (row == null) {
                row = new ModelProviderEntity();
                row.setModel(normalized);
                row.setProvider(request.provider());
                row.setApiUrl(request.apiUrl());
                row.setStatus(status);
                mapper.insert(row);
                added++;
            } else {
                row.setProvider(request.provider());
                row.setApiUrl(request.apiUrl());
                row.setStatus(status);
                mapper.updateById(row);
                updated++;
            }
        }

        log.info("[provider] {} 供应商映射已落库（新增 {} / 更新 {}），热刷新注册表",
                request.provider(), added, updated);
        registry.reload();
        return new ProviderAddResult(added, updated);
    }

    /** 端点判定键：provider + model 统一小写（MySQL ci collation 语义对齐） */
    private static String key(String provider, String model) {
        return provider.toLowerCase() + "|" + model.toLowerCase();
    }

    private void validate(ProviderAddRequest request) {
        if (request == null || request.provider() == null || request.provider().isBlank()) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        if (request.apiUrl() == null || request.apiUrl().isBlank()) {
            throw new IllegalArgumentException("apiUrl 不能为空");
        }
        if (request.models() == null || request.models().isEmpty()
                || request.models().stream().anyMatch(m -> m == null || m.isBlank())) {
            throw new IllegalArgumentException("models 不能为空且不能含空项");
        }
        // 模型名防呆：只允许字母/数字/点/下划线/连字符——方括号、反引号等符号
        // 多为 CLI 误输入（如 markdown 包裹残留），入库后 agent 永远不会引用
        for (String m : request.models()) {
            if (!m.trim().matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException(
                        "模型名 '" + m.trim() + "' 含非法字符（仅允许字母/数字/./_/-）");
            }
        }
    }
}
