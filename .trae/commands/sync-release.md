# 发布同步：dev → master，test → dev，test 双远程推送

> 触发方式：用户说「同步发布分支」「执行发布同步」或引用本命令。
> 目标：把 dev 合入 master、test 合入 dev，最后三个分支全部推送，结束停回 test。

## 硬性安全规则

1. **任何一步失败立即停止**，原样报告错误输出，等待用户指示；严禁继续执行后续步骤。
2. **严禁** `push --force` / `--force-with-lease` / `reset --hard` / `rebase`；遇到冲突只报告，不擅自解决。
3. **严禁**自动提交或修改任何文件；本命令只做合并与推送，不产生新提交。
4. 每步执行后必须校验结果（当前分支、合并输出、推送输出）再进入下一步。

## 执行步骤

### 0. 前置检查

```
git status
git branch --show-current
```

- 若工作树不干净（有未提交/未暂存改动）：**停止**，告知用户先处理改动。
- 记录当前分支（完成后需切回 test；若当前不是 test 也照常执行，最后统一切回）。
- 确认远程存在：`git remote -v` 必须同时有 `origin`（GitHub）与 `gitee`。

### 1. dev → master，推送 master

```
git checkout master
git merge dev
git push origin master
git push gitee master
```

- merge 出现冲突或 push 被拒：**停止并报告**（说明哪个远程/分支失败、错误原文）。

### 2. test → dev，推送 dev

```
git checkout dev
git merge test
git push origin dev
git push gitee dev
```

- 失败处理同上。

### 3. 切回 test，推送 test

```
git checkout test
git push origin test
git push gitee test
```

### 4. 收尾校验（必须执行）

```
git branch --show-current
git log --oneline -n 1 master dev test
```

- 确认当前分支是 **test**；若不是，执行 `git checkout test`。
- 向用户汇报：master / dev / test 各自的最终 commit，以及两个远程的同步结果。
