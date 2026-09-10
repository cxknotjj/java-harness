# NapCat ↔ java-harness 接入计划

> 目标：QQ 消息（私聊/群聊）→ NapCat → java-harness AI Agent → AI 回复 → QQ
> 通信方式：**HTTP POST 上报 + HTTP API 下行**（OneBot 11 协议）
>
> 本计划已对齐项目现状（376 测试基线、agent 表 knowledge 多库绑定、V14 迁移号、
> OkHttp 4.12 在依赖中、`${ENV_VAR:default}` 密钥约定、GoalExecutorConfig 线程池先例）。
> 渠道适配代码按 **channel/qq 垂直包隔离**：core 单向不依赖渠道包。

---

## 1. 现状

| 项 | 状态 |
| --- | --- |
| NapCat 容器 | 运行中（`napcat`，映射端口 3000/3001/6099），QQ 账号 `3896564418` 配置已生成 |
| NapCat OneBot 服务 | **全部未启用**（`onebot11_3896564418.json` 中 httpServers/websocketServers 等均为空） |
| java-harness | Spring Boot 3.5.14 + Java 17；`ChatService.chat(ChatRequest)` 同步聊天（自动建档、多轮记忆、SIMPLE/COMPLEX 路由）、`SessionService.createSession(creator, firstQuestion)`、OkHttp 4.12 依赖已有 |
| Agent 能力 | agent 表支持 per-agent `tools`（V10）、`internal`（V9）、**`knowledge` 知识库绑定**（V12，多库隔离检索） |
| Flyway | 已用到 **V13**，本计划新表用 **V14** |
| 宿主机 firewalld | 已放行：20/21/22/80/443/8888/41127/39000-40000；**3000/3001/6099/8080 均未放行** |

### 部署拓扑建议（避免改 firewalld）

两者都跑在本机时全部走本机回环/bridge 网关，无需对公网放行任何新端口：

```
NapCat 容器(3000)  ←──宿主机 127.0.0.1:3000──  java-harness 发消息
NapCat 容器       ──上报 http://172.17.0.1:8080/onebot/event──▶ java-harness
```

- java-harness → NapCat：用 `http://127.0.0.1:3000`（容器端口映射）
- NapCat → java-harness：上报地址写 `http://172.17.0.1:8080`（容器内访问宿主机 bridge 网关）
- 若 java-harness 部署在其他机器，才需要在 firewalld 放行对应端口

---

## 2. 总体架构

```
QQ用户消息
   │
   ▼
┌─────────────┐  HTTP POST /onebot/event   ┌──────────────────────────────┐
│   NapCat    │ ─────────────────────────▶ │  java-harness                │
│ (OneBot 11) │  {message, user_id, ...}   │  OneBotEventController       │
│             │                            │    │ 幂等去重 → 立即 200 ACK │
│             │                            │    ▼ onebotExecutor 异步     │
│             │                            │  OneBotEventService          │
│             │                            │    │ 过滤/会话绑定           │
│             │                            │    ▼ 进程内直调（非 HTTP 自调）│
│             │                            │  ChatService.chat(...)       │
│             │                            │    │ 多轮记忆+知识库+多Agent  │
│             │                            │    ▼                        │
│             │ ◀───────────────────────── │  NapCatApiClient             │
└─────────────┘  POST /send_private_msg     └──────────────────────────────┘
                 POST /send_group_msg
      │
      ▼
   QQ回复用户
```

**关键决策：进程内直调，不走 HTTP 自调。** 直接注入 `ChatService`/`SessionService`
调用，绕过回环网络、鉴权与序列化开销；`/api/harness/sessions` 等 HTTP 端点仍保留给外部客户端。

**分层定位：渠道适配层。** QQ 接入属应用层渠道适配，不属 harness 核心（编排/多 Agent/RAG/预算）。
代码按方案 B 收拢到 `channel/qq` 垂直包，对 core 的依赖仅限 `ChatService`/`SessionService`
两个端口，core 反向 `import channel` 禁止。渠道数 ≥ 2 或需独立部署时，再升级为独立
Maven module / 进程（仿 CLI 走 HTTP 的先例），core 一行不动。

---

## 3. 阶段一：NapCat 侧启用 OneBot 服务

### 3.1 修改 `onebot11_3896564418.json`（容器内 `/app/napcat/config/`）

```json
{
  "network": {
    "httpServers": [
      {
        "name": "java-harness",
        "enable": true,
        "host": "0.0.0.0",
        "port": 3000,
        "enableCors": false,
        "enableWebsocket": false,
        "messagePostFormat": "array",
        "token": "<自定义token，如 napcat-token-123>",
        "debug": false
      }
    ],
    "httpClients": [
      {
        "name": "report-to-harness",
        "enable": true,
        "url": "http://172.17.0.1:8080/onebot/event",
        "messagePostFormat": "array",
        "reportSelfMessage": false,
        "token": "",
        "debug": false
      }
    ],
    "httpSseServers": [],
    "websocketServers": [],
    "websocketClients": [],
    "plugins": []
  }
}
```

关键点：

- `httpServers`：java-harness 调用的下行 API（`/send_private_msg`、`/send_group_msg` 等），token 必填
- `httpClients`：NapCat → java-harness 的消息上报，`url` 指向宿主机 bridge 网关
- `messagePostFormat: array` → 上行/下行统一用消息段数组；**@ 触发解析优先查 `message` 数组里的
  `at` 段（`data.qq == self_id`），`raw_message` 的 CQ 码字符串仅作兜底**，不作为主判据
- 上报端 `token` 先留空，联调通过后再加验签（见 4.6）

### 3.2 操作步骤

1. 改配置：`docker exec` 直接改文件，或通过 WebUI（`http://<IP>:6099/webui`，token `df4aef91da42`）→ 网络配置 → 添加 HTTP 服务器 / HTTP 客户端
2. 重启容器：`docker restart napcat`
3. 验证：`curl -H "Authorization: Bearer <token>" http://127.0.0.1:3000/get_login_info` 返回 `{"data":{"user_id":3896564418,...}}` 即正常

### 3.3 前置检查

- [ ] QQ 已扫码登录成功（`docker logs napcat` 无登录等待提示）
- [ ] NapCat 容器与宿主机 8080 连通（`docker exec napcat curl -s -o /dev/null -w '%{http_code}' http://172.17.0.1:8080/api/harness/agents`）

---

## 4. 阶段二：java-harness 侧新增代码

包路径沿用 `com.dark.javaHarness`。**先复用，再新增**：

### 4.0 直接复用的现有能力（不重复造）

| 现有能力 | 用法 |
| --- | --- |
| `ChatService.chat(ChatRequest)` | 同步聊天主链路：无 sessionId 自动建档、写回多轮记忆、SIMPLE/COMPLEX 自动路由、失败返回 `status=FAILED` |
| `SessionService.createSession(creator, firstQuestion)` | QQ 首次消息建会话，`creator` 传 `qq:<uid>`（区别于 chat 自动建档的 `anonymous`），返回字符串形式的自增主键 |
| `ChatRequest(message, sessionId, agentId)` | `agentId` 对应 agent 表主键，可空走默认 Agent —— napcat 全局 `agent-id` 配置直接对上 |
| agent 表 `knowledge` 列（V12） | 绑定的 agent 自动获得多库隔离 RAG 检索，QQ 问答零改造带知识库能力 |
| `ChatResponse.sources` | 知识命中出处（docName/title/score），QQ 回复可选拼接「出处」尾巴 |
| `GoalExecutorConfig` 双池先例 | 新增 `onebotExecutor` 照此模式：ThreadPoolTaskExecutor + 有界队列 + 拒绝策略 |
| `${ENV_VAR:default}` 密钥约定 | `napcat.api-token: ${NAPCAT_API_TOKEN:}`，不落 git |
| DTO record 风格 | OneBot DTO 一律 record（对齐 ChatRequest/ChatResponse） |
| `KnowledgeProperties` 先例 | `NapCatProperties` 用 `@Component + @ConfigurationProperties(prefix = "napcat")` 纯绑定载体 |

### 4.1 新增文件（channel/qq 垂直包隔离）

渠道适配代码全部收拢在 `channel/qq` 包内，core 包（controller/service/agent/...）保持不动：

```
src/main/java/com/dark/javaHarness/
├── channel/qq/                        # 渠道适配层（单向依赖：只进不出）
│   ├── NapCatChannelConfig.java       # 装配门控（napcat.enabled）+ onebotExecutor 线程池
│   ├── NapCatProperties.java          # @ConfigurationProperties("napcat")
│   ├── OneBotEventController.java     # POST /onebot/event：去重 → 200 ACK → 异步
│   ├── OneBotEventService.java        # 事件处理接口（过滤/去重/限频/绑定）
│   ├── OneBotEventServiceImpl.java
│   ├── NapCatApiClient.java           # NapCat HTTP API 客户端接口
│   ├── NapCatApiClientImpl.java       # OkHttp 调用
│   └── dto/
│       ├── OneBotEvent.java           # 上报事件 record（post_type/message_type/user_id/...）
│       ├── MessageSegment.java        # 消息段 record {type, data}
│       ├── SendMsgRequest.java        # /send_*_msg 请求体
│       └── ApiResult.java             # {status, retcode, data}
├── domain/entity/
│   └── OneBotSessionBinding.java      # QQ会话 ↔ harness session 映射（按项目惯例放共享位置）
└── mapper/
    └── OneBotSessionBindingMapper.java# 同上：@MapperScan 固定扫 mapper 包
src/main/resources/db/migration/
└── V14__create_onebot_session_binding.sql
```

**依赖规则**：`channel/qq → ChatService/SessionService 端口 ✓`；core 任何包
`import channel ✗`（包边界即文档，暂不引入 ArchUnit）。

**实体/Mapper 放共享位置的理由**：绑定表虽是渠道私有状态，但
`@MapperScan("com.dark.javaHarness.mapper")` 固定扫描 mapper 包（JavaHarnessApplication），
且 KbDocumentEntity 已开「功能私有表放共享位置」先例，为一个表改扫描配置得不偿失。

### 4.2 配置（application.yaml）

```yaml
napcat:
  enabled: true
  api-base-url: http://127.0.0.1:3000   # NapCat HTTP API 地址
  api-token: ${NAPCAT_API_TOKEN:}       # 与 onebot11 httpServers.token 一致，走环境变量不落 git
  self-id: "3896564418"                 # 机器人自身 QQ 号
  agent-id:                             # 空 = 默认 Agent；填 agent 表主键（该 agent 的 knowledge 绑定自动生效）
  event-secret: ${NAPCAT_EVENT_SECRET:} # 上报验签密钥，联调后再启用
  group-trigger:
    mode: at            # at=仅@机器人 | prefix=命令前缀 | all=全部响应
    prefix: "/ai"
  rate-limit:
    per-user-seconds: 10  # 同一用户回复最小间隔（群聊防风控）；0 = 不限制（项目口径）
  reply:
    max-length: 3000      # 超长按段落边界分段
```

配置类 `NapCatProperties` 只做绑定载体（数值唯一来源 application.yaml，`0 = 不限制` 遵循项目口径）；
`napcat.enabled=false` 时整链不装配（对齐 KnowledgeProperties 的开关语义）。

### 4.3 事件接收流程（OneBotEventController + Service）

```
POST /onebot/event
  1. message_id 幂等去重（内存过期 Set，TTL 5min；NapCat 上报失败会重发，
     不去重会重复回复）——重复上报直接 200 丢弃
  2. 立即返回 200（NapCat 上报有超时约 4~10s，必须快速 ACK）
  3. 交给 onebotExecutor 异步处理：
     a. 过滤：post_type != "message" 忽略；user_id == self-id 忽略（防自循环，
        配合 reportSelfMessage=false 双保险）
     b. 群聊触发判断：mode=at 时 message 数组含 {type:"at", data:{qq:<self-id>}}
        才处理，且剥掉 at 段拼接剩余 text 段得到纯文本；无 @ 直接忽略
     c. 私聊：全量响应
     d. 空文本忽略
  4. 群聊限频：rate-limit.per-user-seconds > 0 时，内存滑动窗
     （ConcurrentHashMap<uid, lastTs>）间隔内直接丢弃，零外部依赖
  5. 会话绑定：
     私聊 key = "qq:private:<user_id>"
     群聊 key = "qq:group:<group_id>:<user_id>"（群内按用户独立上下文）
     binding 表查 session_key：命中 → 复用 sessionId；未命中 →
     sessionService.createSession("qq:<uid>", text) 建档并落库
  6. 构造 ChatRequest(message=text, sessionId=绑定会话, agentId=napcat.agent-id)
     调 chatService.chat(request)，拿完整回复（含 sources）
  7. 调 NapCatApiClient 发送回复；超长按段落边界按 max-length 分段发送，
     可选群聊带 reply 段引用原消息；异常捕获 + 日志，失败不打断主流程
```

### 4.4 NapCatApiClient（OkHttp，依赖已有）

| API | 用途 |
| --- | --- |
| `GET /get_login_info` | 启动自检（enabled 时 ApplicationRunner 校验，失败仅告警不阻断启动） |
| `POST /send_private_msg` `{user_id, message:[{type:"text",data:{text}}]}` | 私聊回复 |
| `POST /send_group_msg` `{group_id, message:[{type:"reply",...},{type:"text",...}]}` | 群聊回复（reply 段可选） |
| `POST /delete_msg` `{message_id}` | 可选：撤回 |

- 鉴权：`Authorization: Bearer <api-token>`
- 超时：连接 3s / 读取 10s，失败重试 1 次（消息送达是尽力而为，不打断主流程）

### 4.5 上报验签（后续加固）

NapCat httpClients 配置 `token` 后，上报头带 `X-Signature: sha1=<HMAC-SHA1(secret, body)>`，
Controller 用 `@RequestBody byte[]` 接 raw body 校验（不能先反序列化再验签）；secret 为空跳过校验，
校验失败直接 403。联调阶段先留空。

### 4.6 数据库迁移（Flyway V14）

```sql
CREATE TABLE onebot_session_binding (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_key VARCHAR(128) NOT NULL COMMENT 'qq:private:<uid> 或 qq:group:<gid>:<uid>',
  session_id BIGINT NOT NULL COMMENT 'session 表自增主键（DTO 层以字符串交换）',
  qq_user_id VARCHAR(32) NOT NULL,
  group_id VARCHAR(32) NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_session_key (session_key)
) COMMENT 'OneBot QQ会话与 harness 会话绑定';
```

绑定查询走 MyBatis-Plus QueryWrapper（项目既有风格），无需 XML。

---

## 5. 阶段三：联调测试

1. **单测**（JUnit5 + Mockito，对齐现有 376 用例风格，不依赖网络）
   - `OneBotEventServiceImplTest`：事件过滤、message 数组 @ 触发、自消息过滤、
     message_id 去重、限频窗口、会话绑定复用/新建、分段发送
   - `NapCatApiClientImplTest`：请求组装、鉴权头、分段切分、错误处理
2. **模拟上报**（不依赖 QQ，直接测 java-harness）：

```bash
curl -X POST http://127.0.0.1:8080/onebot/event -H "Content-Type: application/json" -d '{
  "post_type":"message","message_type":"private","user_id":10001,
  "raw_message":"你好，介绍一下你自己","message":[{"type":"text","data":{"text":"你好，介绍一下你自己"}}],
  "sender":{"user_id":10001,"nickname":"tester"},"time":1700000000,"self_id":3896564418
}'
```

3. **真机验证**：
   - 私聊机器人发消息 → 收回复；连续发多条无重复回复（幂等生效）
   - 群内 @机器人 → 收回复；未 @ → 无响应；同用户 10s 内连发只回一次（限频生效）
   - **知识库联动**：给 agent-id 对应的 agent 配 `knowledge=java` → QQ 里问 Spring 问题
     回复带【出处】；问 Vue 问题检索不到（多库隔离在 QQ 链路同样生效）
4. **稳定性**：COMPLEX 路径耗时长的消息，确认 200 先行后 AI 回复仍能送达（异步池承接）

## 6. 任务清单

| # | 任务 | 涉及 |
| --- | --- | --- |
| 1 | NapCat 启用 OneBot HTTP Server + HTTP 上报 | 容器配置 + 重启 |
| 2 | 验证容器 ↔ 宿主机连通 | curl 冒烟 |
| 3 | V14 迁移 + 绑定实体/Mapper + NapCatChannelConfig（enabled + onebotExecutor）+ application.yaml | java-harness |
| 4 | channel/qq 包骨架 + OneBot DTO（record）+ OneBotEventController（去重 + 快速 ACK + 异步） | java-harness |
| 5 | OneBotEventServiceImpl（过滤/@ 触发/限频/绑定/直调 ChatService） | java-harness |
| 6 | NapCatApiClientImpl（OkHttp 发消息客户端 + 启动自检） | java-harness |
| 7 | 单元测试（事件流 + API 客户端） | java-harness |
| 8 | 模拟上报联调 → 真机验证 → 知识库联动验证 | 全链路 |

## 7. 风险与注意事项

- **上报超时**：NapCat HTTP 上报有超时（约 4~10s），Controller 必须先 200 再异步处理；
  COMPLEX 多 Agent 编排耗时长也没问题（onebotExecutor 有界队列 + 拒绝策略兜底，
  拒绝时记日志——对应 QQ 侧表现为该条消息无回复）
- **重复回复**：NapCat 上报失败会重发，**message_id 幂等去重必须做在 ACK 之前**
- **风控**：自动回复频率过高易触发 QQ 风控（限流/冻结）；群聊按 per-user-seconds 限频
  （yaml 配置、0=不限），上线初期建议置 enabled=false 灰度观察
- **自循环**：过滤 `user_id == self_id` + `reportSelfMessage=false` 双保险，缺一不可
- **内容安全**：AI 输出直接进 QQ 群，注意规避敏感内容；max-length 分段兼作输出上限
- **token 安全**：`api-token`/`event-secret` 走 `${NAPCAT_API_TOKEN:}`/`${NAPCAT_EVENT_SECRET:}`
  环境变量，不落 git（对齐 QWEN_API_KEY 等既有约定）
- **部署位置**：若 java-harness 不在本机，上报地址改为其内网 IP 并放行 8080；
  NapCat 3000 端口建议仅限对端 IP 访问
