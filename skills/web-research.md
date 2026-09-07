---
name: web-research
description: 网页调研纪律：多来源信息收集、交叉核对与出处标注的步骤
agents: researcher, general
---

## 网页调研纪律

1. 先明确要回答的问题与所需信息点，再决定抓取哪些来源，不要盲目抓取。
2. 同一 URL 只抓取一次；优先用 fetchUrl 一次抓取多角度提取，JS 渲染页再退回 browser_navigate + browser_snapshot。
3. 至少交叉核对两个独立来源，结论注明出处 URL；来源冲突时并列呈现，不要擅自取舍。
4. 材料足以支撑结论时立即停止抓取，输出带来源的资料摘要。
