package com.dark.javaHarness.service;

import com.dark.javaHarness.domain.dto.ProviderAddRequest;
import com.dark.javaHarness.domain.dto.ProviderAddResult;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import java.util.List;

/**
 * 模型供应商管理：供 CLI / REST 增补 model_provider 映射并热刷新注册表，免去手写 SQL 与重启。
 */
public interface ProviderAdminService {

    /** 全量映射行（含禁用行，status 字段区分），按 id 升序 */
    List<ModelProviderEntity> list();

    /**
     * 新增供应商映射：models 逐个落库，模型名已存在则更新（provider/api_url/status），
     * 完成后热刷新 ChatClientRegistry（免重启生效）。
     */
    ProviderAddResult add(ProviderAddRequest request);
}
