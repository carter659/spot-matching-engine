# mock 雪花订单 ID

新订单使用正数 long：41 位毫秒时间差、10 位节点编号、12 位毫秒内序号。
固定起点为 `2026-01-01T00:00:00Z`，不要修改起点或位布局。

开发配置 `mock.snowflake.worker-id=${MOCK_SNOWFLAKE_WORKER_ID:0}`，范围 0–1023。
其他 profile 可设置同名属性。并行运行的 mock 必须使用不同编号；同一节点编号不可同时启动两个实例。
配置不同编号只解决发号冲突，不代表当前 outbox 支持多个实例共享同一命令历史。

生成时间转换示例（仅适用于新雪花 ID）：

```java
var time = SnowflakeIdGenerator.timestamp(orderId)
        .atZone(java.time.ZoneId.of("Asia/Shanghai"));
var text = time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
```

同一节点正常时钟下递增，多节点按时间趋势有序，不等于撮合处理顺序。
时钟回拨、时间超出 41 位范围或同毫秒 4096 个序号用尽时拒绝生成，恢复后可重试。
生成本身不访问数据库；命令仍然先持久化再发送。重试复用原 ID。
持久化记录新增 snowflake 标记；旧记录缺省为 false，历史 ID 不修改，也不能按雪花时间解释。
重启从已持久化的新记录恢复当前节点的时间和序号，并跳过历史 ID 冲突。
请保留持久化命令记录；清空记录会丢失恢复和去重依据。
