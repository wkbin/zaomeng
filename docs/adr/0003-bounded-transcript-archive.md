# ADR-0003：会话 transcript 使用有界清单与分块归档

- 状态：已采纳
- 日期：2026-08-11

## 背景

如果 session manifest 永久保存完整 transcript，每轮生成都要读取和解析线性增长的 JSON；Room 文档、领域消息表和 API 响应也容易重复保存或传输历史，增加延迟、内存和数据库体积。

## 决策

session manifest 只保留有界的近期 transcript，并记录 `transcript_start` 与 `transcript_count`。达到 rollover 阈值后，将较旧条目成批写入按顺序命名的 archive 文档，避免每轮重写整个归档。

生成路径读取轻量 manifest；会话详情、搜索、章节和历史分页按需物化或查询完整历史。Room messages 表使用全局 seq 保持顺序，并在近期区间变更时增量同步。

## 结果

优点：

- 单轮生成的 manifest 解析成本保持有界；
- 历史仍可完整查看和分页；
- 归档按批写入，降低每轮 IO；
- 轻量 complete 响应只传本轮增量和总数。

代价：

- 编辑旧消息时可能需要物化并重新归档；
- archive 与 manifest 写入顺序需要考虑崩溃恢复；
- 所有使用 transcript 的功能必须明确需要近期还是完整历史。

## 约束

不能仅按 manifest 中近期数组长度计算总消息数。删除或分支会话时必须覆盖归档和领域消息表。
