package com.dark.javaHarness.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 集成验证：真实 MySQL 下 MysqlSaver Checkpointer 真正落库 + 断点可恢复。
 * 依赖真实 DataSource（本地 MySQL harness 库，已在 schema.sql 建好 GRAPH_THREAD/GRAPH_CHECKPOINT）。
 */
@SpringBootTest
class MySqlCheckpointerIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void invoke_withCheckpointer_persistsAndReadsBackState() throws Exception {
        // 幂等构造 saver（可重复执行）
        MysqlSaver saver = MySqlCheckpointer.get(dataSource);
        SaverConfig saverConfig = SaverConfig.builder().register(saver).build();
        CompiledGraph graph = buildGraph()
                .compile(CompileConfig.builder().saverConfig(saverConfig).build());

        // 执行一次（线程名固定，便于稳定产生 checkpointer 数据）
        RunnableConfig config = RunnableConfig.builder().threadId("it-" + System.nanoTime()).build();
        Map<String, Object> input = Map.of("value", "checkpoint-me");
        Optional<OverAllState> result = graph.invoke(input, config);
        assertEquals("processed: checkpoint-me", result.orElseThrow().value("processed", ""));

        // 断点可读回：用同一 threadId 能取到该线程最近一次检查点
        String threadId = config.threadId().orElseThrow();
        RunnableConfig lookup = RunnableConfig.builder().threadId(threadId).build();
        Optional<com.alibaba.cloud.ai.graph.checkpoint.Checkpoint> checkpointOpt = saver.get(lookup);
        assertNotNull(checkpointOpt, "checkpointer 应能从该线程读回检查点");
        assertNotNull(checkpointOpt.orElseThrow(), "检查点本身应非空");
    }

    private static StateGraph buildGraph() throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        KeyStrategyFactory f = () -> {
            Map<String, KeyStrategy> s = new HashMap<>();
            s.put("value", new ReplaceStrategy());
            s.put("processed", new ReplaceStrategy());
            return s;
        };
        StateGraph g = new StateGraph("cp-it", f);
        g.addNode("process", node_async(state -> {
            String v = state.value("value", "");
            return Map.of("processed", "processed: " + v);
        }));
        g.addEdge(START, "process").addEdge("process", END);
        return g;
    }
}