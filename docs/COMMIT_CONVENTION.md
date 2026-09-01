# Commit 提交规范

> 本项目遵循 [Conventional Commits](https://www.conventionalcommits.org/) 格式，**描述用中文**。
> 与 [.trae/rules/git-workflow.md](../.trae/rules/git-workflow.md) 配套使用。

## 格式

```
<type>(<scope>): <中文描述>

<body>

<footer>
```

- **type**（必填，英文）：见下表
- **scope**（可选，英文）：影响范围，如 `mcp`、`agent`、`cli`、`sandbox`、`route`
- **subject**（必填，中文）：一句话说明改了什么
- **body**（可选，中文）：说明**为什么**改，而非罗列做了什么
- **footer**（可选）：`Closes #123`、`BREAKING CHANGE: ...`

## 中文乱码规避（重要）

Windows 控制台默认 GBK 代码页，**命令行内联中文**（`git commit -m "中文"`）易乱码。
规避方式（二选一）：

1. **推荐：文件方式提交**——把 message 写入 UTF-8 文本文件后 `git commit -F <文件>`（AI 助手执行提交时一律用此方式）
2. 配置编码后仍可用内联：`git config --global i18n.commitEncoding utf-8` + 终端切 UTF-8 代码页（`chcp 65001`）

`git log` 中文显示乱码时，追加配置：`git config --global i18n.logOutputEncoding utf-8`。

## Type 一览

| type | 用途 | 示例 |
|------|------|------|
| `feat` | 新功能 | `feat(mcp): 支持 stdio 传输与工具名去重` |
| `fix` | 缺陷修复 | `fix(route): 路由判断请求补 model 参数避免 400` |
| `refactor` | 重构（不改行为） | `refactor(tools): 按名称去除重复工具回调` |
| `perf` | 性能优化 | `perf(cli): 复用 http 客户端减少连接开销` |
| `docs` | 文档 | `docs: readme 重写为 github 风格` |
| `test` | 测试 | `test(assignments): 覆盖重名工具去重场景` |
| `chore` | 构建/工具/杂项 | `chore: gitignore 增加 cli 临时文件` |
| `revert` | 回滚 | `revert: 回滚 feat(mcp) 因握手超时` |
| `remove` | 删除文件/功能 | `remove: 清理调试临时文件` |

## Subject 规则

1. 简洁动宾短语，不加句号：`支持断点续跑` 而非 `支持了断点续跑。`
2. 一行不超过 72 字符（中文按 2 字符宽估算，约 30+ 汉字）
3. 不写「更新代码」「修改一些东西」这类无信息量的描述

## Body 规则

- 解释**动机与取舍**（为什么），改动内容看 diff 即可
- 空一行后接 body（git 工具依赖此分隔）
- 多段时用 `-` 列表

## 提交纪律（硬性）

1. **只提交明确在任内的文件**，禁止 `git add -A` / `git add .` 盲加
2. **绝不提交**：`.mvn-repo/`、`.cli-history`、`target/`、`.idea/`、`.vscode/`、
   `in.txt`、`cli-*.txt`、`server.log` 等临时/生成文件（已在 `.gitignore`）
3. 未获用户明示（「提交」/「commit」/「push」等指令）**不得执行任何 git 提交类操作**
4. 推送必须**双远程**：`origin`（GitHub）与 `gitee` 缺一不可
5. 含密钥/凭据的文件永不入库

## 示例

单行（多数场景够用）：

```
fix(mcp): 对齐 mcp sdk 至 0.17.0 解决 graph-core 依赖冲突
```

带 body：

```
fix(cli): 显式声明 token 事件修复 SSE 事件粘滞吞字

SSE 规范中 event 字段按块粘滞：progress 块之后未声明事件的 data 行
会被误归入 progress 通道，导致所有回答 token 静默丢失（CLI 显示
输出约 0 字）并产生空白 spinner 行。

服务端与客户端现在统一显式使用 token 事件，并移除无事件的兜底分支。
```
