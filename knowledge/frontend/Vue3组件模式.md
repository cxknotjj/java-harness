---
title: Vue3 组件模式
---

# Vue3 组件模式

本文档属于 frontend 知识库：`knowledge/frontend/` 子目录下的文档只提供给绑定了 `frontend` 库的 agent 检索。

## 组合式 API 要点

- `ref` 包装基本类型，访问值需 `.value`；`reactive` 适合对象，解构会丢失响应性。
- `computed` 缓存依赖计算结果，副作用放 `watch` / `watchEffect`。
- 逻辑复用优先抽 composable（`useXxx` 函数），替代 Vue2 的 mixin。

## 组件通信

| 场景 | 方式 |
| --- | --- |
| 父 → 子 | props |
| 子 → 父 | emits |
| 跨层级 | provide / inject |
| 全局状态 | Pinia store |

## 常见陷阱

- `v-if` 与 `v-for` 同节点时 `v-if` 优先级更高，勿依赖此行为，用 `computed` 过滤数据源。
- 直接修改 props 会告警，需要双向绑定用 `defineModel`。
- 异步组件用 `defineAsyncComponent` 包裹，配合 `Suspense` 处理加载态。
