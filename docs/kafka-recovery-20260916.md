# Kafka Rebootstrapping 日志排查与修复

## 已确认原因

2026-09-16，本机 Kafka broker 反复启动后退出，9092 无法提供连接。客户端 AdminClient 因无法获取集群元数据持续重新连接。

Kafka server.log 明确报告测试分区 `matching.qa.admin.20260916.market-0` 的 topic ID `uzNrEqDHS3S4UFJJ00ZR2g` 已不存在于 metadata image，因此被判定为 stray（孤立分区）。重命名为 `-stray` 时 Windows 返回 `AccessDeniedException`，随后 broker 报 `Shutdown broker because all log dirs ... have failed` 并退出。具体拒绝重命名的底层文件句柄/权限来源未进一步确认。

## 已执行恢复

停止 KafkaUI 和 Kafka 服务，将上述单个孤立分区完整移动至：

`D:\Program Files\kafka\kafka_2.13-4.3.1\recovery-backup\20260916-184446\matching.qa.admin.20260916.market-0`

随后启动 Kafka 与 KafkaUI。未删除数据目录，未格式化 KRaft 元数据，未修改集群 ID 或端口。

## 验证

- Kafka / KafkaUI 服务恢复 Running。
- Kafka UI `/api/clusters` 返回 ONLINE、brokerCount=1。
- mock 消费者自动订阅 matching.market，获取集群 ID 并发现 localhost:9092 协调器。
- 原 Rebootstrapping 高频刷屏停止。

`scripts/repair-kafka-orphan.ps1` 是本次特定孤立目录的恢复脚本，需要管理员权限；不是通用数据清理工具。后续同类问题先查 broker 日志确认原因，不要直接删除 data、__consumer_offsets 或 KRaft 元数据，也不要仅关闭客户端日志掩盖连接故障。
