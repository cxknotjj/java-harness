package com.dark.javaHarness.cli;

import com.dark.javaHarness.cli.api.ChatApiClient;
import com.dark.javaHarness.domain.dto.ChatResponse;
import java.io.IOException;
import java.net.ConnectException;

/**
 * 命令行聊天客户端：独立进程运行，通过 REST 调用主服务（8080）的 /api/chat/stream（SSE 流式）。
 *
 * 重要：CLI 是纯 HTTP 客户端，不监听任何端口，占用的是你当前的终端进程。
 * 主服务（JavaHarnessApplication）负责监听 8080、保留日志、执行 Agent 编排。
 *
 * 启动方式（另开一个终端，在项目根目录）：
 *   mvn -q -s .mvn/settings.xml exec:java
 */
public class ChatCli {

    private final ChatApiClient api;
    private final String baseUrl;

    /** 会话ID：首轮为空（由服务端自动建档），从首次响应中获取后复用，实现多轮记忆 */
    private String sessionId;

    /** 当前选中的 Agent ID：为空表示使用默认 Agent（general），可用 /agent <id> 切换 */
    private Long agentId = 1L;

    /** 默认连本机主服务 8080 */
    public ChatCli() {
        this.baseUrl = "http://localhost:8080";
        this.api = new ChatApiClient(baseUrl);
    }

    /** 程序入口：启动交互式聊天循环 */
    public static void main(String[] args) {
        new ChatCli().chatLoop();
    }

    /** 交互式循环（独立终端使用） */
    public void chatLoop() {
        System.out.println("==============================================");
        System.out.println(" javaHarness CLI - 聊天客户端 (主服务: 8080)");
        System.out.println(" 直接输入文本对话，/help 帮助，/exit 退出");
        System.out.println("==============================================");

        java.io.BufferedReader br =
                new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        while (true) {
            System.out.print("你> ");
            System.out.flush();
            String line;
            try {
                line = br.readLine();
            } catch (IOException e) {
                break;
            }
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if ("/exit".equals(line) || "/quit".equals(line)) {
                break;
            } else if ("/help".equals(line)) {
                System.out.println("  直接输入文本与当前 agent 聊天（默认 general）");
                System.out.println("  /agent <id>  切换到指定 Agent（agent 表主键）");
                System.out.println("  /agent       查看当前 Agent");
                System.out.println("  /exit        退出");
                continue;
            } else if (line.startsWith("/agent")) {
                handleAgentCommand(line);
                continue;
            }
            send(line);
        }
        System.out.println("再见！");
    }

    /** 处理 /agent 命令：切换或查看当前 Agent */
    private void handleAgentCommand(String line) {
        String arg = line.substring("/agent".length()).trim();
        if (arg.isEmpty()) {
            System.out.println("当前 Agent: " + (agentId == null ? "默认(general)" : agentId));
            return;
        }
        try {
            this.agentId = Long.parseLong(arg);
            System.out.println("已切换到 Agent #" + agentId + "（后续请求携带该 agentId）");
        } catch (NumberFormatException e) {
            System.out.println("agent 编号无效，用法: /agent <数字Id> 或 /agent");
        }
    }

    /** 发送一条消息到主服务 /api/chat/stream（SSE 流式），边收 token 边打印 */
    private void send(String message) {
        try {
            System.out.print("\n千问> ");
            ChatResponse resp = api.chatStream(message, sessionId, agentId, token -> {
                System.out.print(token);
                System.out.flush();
            });
            System.out.println();
            // 记住服务端返回的会话ID，后续请求携带以延续多轮上下文
            if (resp.sessionId() != null && !resp.sessionId().isBlank()) {
                this.sessionId = resp.sessionId();
            }
            if ("SUCCEEDED".equals(resp.status())) {
                System.out.println("（会话 " + sessionId + " / " + resp.goalId() + "）");
            } else {
                String err = resp.error() == null ? "（无详细信息）" : resp.error();
                System.out.println("\n[执行失败 " + resp.goalId() + "] " + err);
            }
        } catch (ConnectException e) {
            System.out.println("无法连接主服务 " + baseUrl + "，请先启动主进程: mvn -s .mvn/settings.xml spring-boot:run");
        } catch (IOException e) {
            System.out.println("请求失败: " + e.getMessage());
        }
    }
}