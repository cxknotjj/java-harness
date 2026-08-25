package com.dark.javaHarness.graph;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import javax.sql.DataSource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MysqlSaver 的单例持有者。
 *
 * <p>MysqlSaver 构造时会按 CreateOption 幂等建表（GRAPH_THREAD / GRAPH_CHECKPOINT），
 * 且每个 saver 实例维护内部缓存。为复用同一 saver（避免每次编译图都重建 + 重复建表），
 * 这里按 DataSource 缓存单例；不同 DataSource 各自持有独立 saver。
 */
public final class MySqlCheckpointer {

    private static final ConcurrentMap<DataSource, MysqlSaver> SAVERS = new ConcurrentHashMap<>();

    private MySqlCheckpointer() {
    }

    /** 获取（必要时创建）绑定指定 DataSource 的 MysqlSaver 单例。CREATE_IF_NOT_EXISTS 保证幂等建表。 */
    public static MysqlSaver get(DataSource dataSource) {
        return SAVERS.computeIfAbsent(dataSource, ds -> MysqlSaver.builder()
                .dataSource(ds)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build());
    }
}