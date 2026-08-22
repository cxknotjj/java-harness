package com.dark.javaHarness.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 示例工具集（演示 Spring AI 工具调用）。
 *
 * 工具方法使用 @Tool 注解，形参用 @ToolParam 描述参数含义。
 * 接入方式：chatClient.builder().defaultTools(new DemoTools())，Spring AI 会
 * 自动将方法名/描述/参数转成模型可用的 function schema，并处理多轮工具调用循环。
 */
public class DemoTools {

    /**
     * 返回当前本地时间。
     */
    @Tool(description = "获取服务器当前本地时间")
    public String getCurrentTime() {
        return java.time.LocalDateTime.now().toString();
    }

    /**
     * 两个整数相加。
     *
     * @param a 加数
     * @param b 加数
     */
    @Tool(description = "计算两个整数相加的结果")
    public int add(
            @ToolParam(description = "第一个加数") int a,
            @ToolParam(description = "第二个加数") int b) {
        return a + b;
    }

    /**
     * 模拟查询天气（占位实现）。
     *
     * @param city 城市名
     */
    @Tool(description = "查询指定城市的天气情况")
    public String getWeather(@ToolParam(description = "城市名称") String city) {
        // 占位：真实可接入第三方天气 API
        return "%s 今日多云，气温 18~25℃，东南风 2 级。".formatted(city);
    }
}