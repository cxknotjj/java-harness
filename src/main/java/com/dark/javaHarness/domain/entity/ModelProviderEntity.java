package com.dark.javaHarness.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 模型-服务商映射实体，对应表 model_provider。
 * 记录每个模型名（agent.model 引用的值）绑定到哪个服务商客户端。
 */
@Data
@TableName("model_provider")
public class ModelProviderEntity {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型名（agent.model 引用的值） */
    private String model;

    /** 服务商标识：dashscope / deepseek / ... */
    private String provider;

    /** 服务商端点 base-url（库驱动，构建 ChatClient 时使用） */
    private String apiUrl;

    /** 是否关闭思考：1-该端点请求注入 enable_thinking:false（dashscope 思考模型）0-模型默认 */
    private Integer disableThinking;

    /** 状态：1-启用 0-禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}