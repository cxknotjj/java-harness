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

        // 一次查出请求中的已存在模型，避免逐个 select
        Set<String> models = new HashSet<>(request.models());
        Map<String, ModelProviderEntity> existing = mapper.selectList(
                        new LambdaQueryWrapper<ModelProviderEntity>()
                                .in(ModelProviderEntity::getModel, models)).stream()
                .collect(Collectors.toMap(ModelProviderEntity::getModel, Function.identity()));

        int added = 0;
        int updated = 0;
        for (String model : models) {
            ModelProviderEntity row = existing.get(model);
            if (row == null) {
                row = new ModelProviderEntity();
                row.setModel(model);
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
    }
}
