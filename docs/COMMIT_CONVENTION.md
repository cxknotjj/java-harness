# Commit 提交规范

> 本项目遵循 [Conventional Commits](https://www.conventionalcommits.org/) 格式。
> 与 [.trae/rules/git-workflow.md](../.trae/rules/git-workflow.md) 配套使用。

## 格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

- **type**（必填）：见下表
- **scope**（可选）：影响范围，如 `mcp`、`agent`、`cli`、`sandbox`、`route`
- **subject**（必填）：一句话说明，**用英文**（Windows 下中文 commit 易乱码）
- **body**（可选）：说明**为什么**改，而非罗列做了什么
- **footer**（可选）：`Closes #123`、`BREAKING CHANGE: ...`

## Type 一览

| type | 用途 | 示例 |
|------|------|------|
| `feat` | 新功能 | `feat(mcp): add stdio transport support` |
| `fix` | 缺陷修复 | `fix(route): include model param to avoid 400 on judge call` |
| `refactor` | 重构（不改行为） | `refactor(tools): dedupe tool callbacks by name` |
| `perf` | 性能优化 | `perf(cli): reuse http client across requests` |
| `docs` | 文档 | `docs: update data-flow with mcp section` |
| `test` | 测试 | `test(assignments): cover duplicate tool name dedup` |
| `chore` | 构建/工具/杂项 | `chore: ignore cli debug temp files` |
| `revert` | 回滚 | `revert: feat(mcp) due to handshake timeout` |
| `remove` | 删除文件/功能 | `remove: drop debug temp files` |

## Subject 规则

1. 祈使句、不加句号：`add` 而非 `added` / `adds xxx.`
2. 首字母小写
3. 一行不超过 72 字符
4. 不写「更新代码」「修改一些东西」这类无信息量的描述

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
fix(mcp): align mcp sdk to 0.17.0 to fix graph-core conflict
```

带 body：

```
fix(cli): declare token event explicitly to prevent sticky SSE event

The SSE `event` field is sticky per spec: after a `progress` block,
subsequent `data:` lines without an event header were misrouted to
onProgress, silently dropping all answer tokens (displayed as 0 chars)
and producing blank spinner lines.

Both server and client now use an explicit `token` event; the
no-event fallback branch is removed.
```
