---
title: Spring Boot 常见问题
---

# Spring Boot 常见问题

本文档属于 java 知识库：`knowledge/java/` 子目录下的文档只提供给绑定了 `java` 库的 agent 检索。

## 事务失效的典型场景

1. 方法非 public：Spring AOP 代理无法拦截，`@Transactional` 不生效。
2. 自调用：同类内部 `this.method()` 绕过代理，事务注解失效。
3. 异常被捕获吞掉：默认只回滚 `RuntimeException` 与 `Error`，受检异常需 `rollbackFor` 显式声明。

## Flyway 迁移规范

- 版本文件命名 `V<版本>__<描述>.sql`，描述用双下划线分隔，一经合入不可修改。
- 已应用的迁移文件校验和变化会导致启动失败，需 `flyway repair` 或新增版本修复。
- 与代码表结构同步：实体字段变更必须同步新增迁移文件。

## Reactor 常见坑

- `.map()` 不允许返回 null，需要跳过空帧时改用 `.handle()`。
- 流式响应中 `streamUsage(true)` 会多发一个只含 usage 的空帧，需跳过避免流处理失败。
