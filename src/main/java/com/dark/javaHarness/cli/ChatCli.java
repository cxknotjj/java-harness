package com.dark.javaHarness.cli;

import com.dark.javaHarness.cli.api.ChatApiClient;
import com.dark.javaHarness.domain.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * 当前选中的 Agent ID：null 表示交由服务端「主 Agent 前置判断」分流
     * （SIMPLE → general 单模型；COMPLEX → multi-agent 编排并推送进度事件）。
     * 注意：一旦非空（如默认带 1），服务端会绕过 RouteJudge 直接路由，
     * 永远走单 Agent 且无任何 progress 事件——所以默认必须保持 null。
     * 可用 /agent <id> 显式切换；/agent off 恢复分流。
     */
    private Long agentId = null;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        loadExistingSession();

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
                System.out.println("  直接输入文本与 AI 聊天（默认由服务端智能分流：简单→general / 复杂→multi-agent）");
                System.out.println("  /new         新建会话（后续对话使用新上下文，旧会话保留）");
                System.out.println("  /agent <id>  切换到指定 Agent（agent 表主键，此后不走分流）");
                System.out.println("  /agent off   取消指定，恢复服务端自动分流");
                System.out.println("  /agent       查看当前 Agent");
                System.out.println("  /exit        退出");
                continue;
            } else if ("/new".equals(line)) {
                handleNewSession();
                continue;
            } else if (line.startsWith("/agent")) {
                handleAgentCommand(line);
                continue;
            }
            send(line);
        }
        System.out.println("再见！");
    }

    /** 进入对话前，先调 /api/harness/sessions 取一个已有会话，后续 stream 请求携带其 sessionId 延续上下文 */
    private void loadExistingSession() {
        try {
            String existing = api.firstSessionId();
            if (existing != null && !existing.isBlank()) {
                this.sessionId = existing;
                System.out.println("已加载会话 " + sessionId + "（后续请求携带该 sessionId 延续上下文）");
            } else {
                System.out.println("暂无历史会话，将新建会话");
            }
        } catch (IOException e) {
            System.out.println("获取会话列表失败（将新建会话）: " + e.getMessage());
        }
    }

    /** 处理 /new 命令：调用服务端新建空会话并切换当前会话（旧会话保留，可随时通过会话列表找回） */
    private void handleNewSession() {
        try {
            String newId = api.createSession();
            this.sessionId = newId;
            System.out.println("已开启新会话 " + newId + "（后续对话不再携带旧会话上下文）");
        } catch (IOException e) {
            System.out.println("新建会话失败: " + e.getMessage());
        }
    }

    /** 处理 /agent 命令：切换、查看或取消（off）当前 Agent */
    private void handleAgentCommand(String line) {
        String arg = line.substring("/agent".length()).trim();
        if (arg.isEmpty()) {
            System.out.println("当前 Agent: " + (agentId == null ? "自动分流（服务端按复杂度选择）" : agentId));
            return;
        }
        if ("off".equalsIgnoreCase(arg)) {
            this.agentId = null;
            System.out.println("已恢复服务端自动分流");
            return;
        }
        try {
            this.agentId = Long.parseLong(arg);
            System.out.println("已切换到 Agent #" + agentId
                    + "（此后请求固定路由到该 Agent，不再自动分流；/agent off 可恢复）");
        } catch (NumberFormatException e) {
            System.out.println("agent 编号无效，用法: /agent <数字Id> | /agent off | /agent");
        }
    }

    /** 解析并打印一条进度事件（{@code {"stage":..,"detail":..}}），多 Agent 编排的分阶段反馈 */
    private void printProgress(String data) {
        String stage = "";
        String detail = data;
        try {
            var node = MAPPER.readTree(data);
            stage = node.path("stage").asText("");
            detail = node.path("detail").asText("");
        } catch (IOException e) {
            // 解析失败则原样打印 data，不中断
        }
        System.out.print("\n[" + stage + "] " + detail);
        System.out.flush();
    }

    /** 发送一条消息到主服务 /api/chat/stream（SSE 流式），边收 token 边打印。
     *  多 Agent 编排的阶段反馈（event:progress）以进度行形式实时提示，避免等待无反馈。 */
    private void send(String message) {
        try {
            System.out.print("\n千问> ");
            ChatResponse resp = api.chatStream(message, sessionId, agentId, token -> {
                System.out.print(token);
                System.out.flush();
            }, data -> printProgress(data));
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