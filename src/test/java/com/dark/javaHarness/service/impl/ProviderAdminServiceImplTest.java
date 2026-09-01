package com.dark.javaHarness.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.config.agent.ChatClientRegistry;
import com.dark.javaHarness.domain.dto.ProviderAddRequest;
import com.dark.javaHarness.domain.dto.ProviderAddResult;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ProviderAdminService 单测：
 * - add：新模型 insert，已存在模型按 model 唯一键 update，随后热刷新注册表
 * - add：provider/apiUrl/models 校验失败抛 IllegalArgumentException 且不写库
 * - list：返回全量映射行
 */
@ExtendWith(MockitoExtension.class)
class ProviderAdminServiceImplTest {

    @Mock
    private ModelProviderMapper mapper;
    @Mock
    private ChatClientRegistry registry;

    @InjectMocks
    private ProviderAdminServiceImpl service;

    private ProviderAddRequest request(String provider, String apiUrl, List<String> models) {
        return new ProviderAddRequest(provider, apiUrl, models, null);
    }

    @Test
    void add_newModels_insertsRowsAndReloadsRegistry() {
        when(mapper.selectList(any())).thenReturn(List.of()); // 无同名模型

        ProviderAddResult result = service.add(
                request("moonshot", "https://api.moonshot.cn/v1", List.of("kimi-k2", "kimi-latest")));

        assertEquals(2, result.added());
        assertEquals(0, result.updated());
        ArgumentCaptor<ModelProviderEntity> captor = ArgumentCaptor.forClass(ModelProviderEntity.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        ModelProviderEntity first = captor.getAllValues().get(0);
        assertEquals("kimi-k2", first.getModel());
        assertEquals("moonshot", first.getProvider());
        assertEquals("https://api.moonshot.cn/v1", first.getApiUrl());
        assertEquals(1, first.getStatus(), "status 缺省应为 1（启用）");
        verify(registry).reload();
    }

    @Test
    void add_existingModel_updatesRowInsteadOfInsert() {
        ModelProviderEntity existing = new ModelProviderEntity();
        existing.setId(7L);
        existing.setModel("kimi-k2");
        existing.setProvider("moonshot");
        existing.setApiUrl("https://old.example.com");
        existing.setStatus(0);
        when(mapper.selectList(any())).thenReturn(List.of(existing));

        ProviderAddResult result = service.add(
                request("moonshot", "https://api.moonshot.cn/v1", List.of("kimi-k2")));

        assertEquals(0, result.added());
        assertEquals(1, result.updated());
        verify(mapper, never()).insert(any(ModelProviderEntity.class));
        ArgumentCaptor<ModelProviderEntity> updateCaptor = ArgumentCaptor.forClass(ModelProviderEntity.class);
        verify(mapper).updateById(updateCaptor.capture());
        ModelProviderEntity updatedRow = updateCaptor.getValue();
        assertEquals(7L, updatedRow.getId());
        assertEquals("https://api.moonshot.cn/v1", updatedRow.getApiUrl());
        assertEquals(1, updatedRow.getStatus());
        verify(registry).reload();
    }

    @Test
    void add_invalidArgs_throwsWithoutWriting() {
        assertThrows(IllegalArgumentException.class,
                () -> service.add(request("", "https://x", List.of("m"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.add(request("moonshot", " ", List.of("m"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.add(request("moonshot", "https://x", List.of())));

        verify(mapper, never()).insert(any(ModelProviderEntity.class));
        verify(mapper, never()).updateById(any(ModelProviderEntity.class));
        verify(registry, never()).reload();
    }

    @Test
    void list_returnsAllRowsIncludingDisabled() {
        ModelProviderEntity e = new ModelProviderEntity();
        e.setModel("deepseek-chat");
        e.setProvider("deepseek");
        e.setApiUrl("https://api.deepseek.com");
        e.setStatus(0);
        when(mapper.selectList(null)).thenReturn(List.of(e));

        List<ModelProviderEntity> rows = service.list();

        assertEquals(1, rows.size());
        assertEquals("deepseek-chat", rows.get(0).getModel());
    }
}
