package com.dark.javaHarness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 命令行聊天客户端：独立进程运行，通过 REST 调用主服务（8080）的 /api/chat。
 *
 * 重要：CLI 是纯 HTTP 客户端，不监听任何端口，占用的是你当前的终端进程。
 * 主服务（JavaHarnessApplication）负责监听 8080、保留日志、执行 Agent 编排。
 *
 * 启动方式（另开一个终端，在项目根目录）：
 *   mvn -q -s .mvn/settings.xml exec:java
 */
public class ChatCli {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    /** 会话ID：首轮为空（由服务端自动建档），从首次响应中获取后复用，实现多轮记忆 */
    private String sessionId;

    public ChatCli() {
        this.baseUrl = "http://localhost:8080";
    }

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
                System.out.println("  直接输入文本与 general agent 聊天");
                System.out.println("  /exit  退出");
                continue;
            }
            send(line);
        }
        System.out.println("再见！");
    }

    private void send(String message) {
        try {
            String json = mapper.writeValueAsString(new ChatRequest(message, sessionId));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.out.println("HTTP " + resp.statusCode() + ": " + resp.body());
                return;
            }
            JsonNode body = mapper.readTree(resp.body());
            String status = body.path("status").asText();
            String goalId = body.path("goalId").asText();
            // 记住服务端返回的会话ID，后续请求携带以延续多轮上下文
            String respSessionId = body.path("sessionId").asText(null);
            if (respSessionId != null && !respSessionId.isBlank()) {
                this.sessionId = respSessionId;
            }
            if ("SUCCEEDED".equals(status)) {
                System.out.println("\n千问> " + body.path("reply").asText());
                System.out.println("（会话 " + sessionId + " / " + goalId + "）");
            } else {
                System.out.println("\n[执行失败 " + goalId + "] " + body.path("error").asText());
            }
        } catch (ConnectException e) {
            System.out.println("无法连接主服务 " + baseUrl + "，请先启动主进程: mvn -s .mvn/settings.xml spring-boot:run");
        } catch (IOException | InterruptedException e) {
            System.out.println("请求失败: " + e.getMessage());
        }
    }

    private record ChatRequest(String message, String sessionId) {
    }
}