# 撮合消息链路与本地模拟

## 消息分工

| 数据 | 发送方 → 消费方 | 中间件与目的地 |
| --- | --- | --- |
| 下单、撤单 | matching-mock → matching-server | RabbitMQ exchange: matching.commands；queue: matching.commands.engine |
| 撮合结果（含成交明细、剩余数量、撤单结果或拒绝原因） | matching-server → matching-mock | RabbitMQ exchange: matching.results；queue: matching.results.mock-results |
| 订单簿快照、最新一批成交 | matching-server → matching-mock | Kafka topic: matching.market；group: mock-market |

下单和撤单使用同一命令通道。当前实现单实例撮合引擎，RabbitMQ 消费并发和 prefetch 均为 1，并启用 single-active-consumer。价格优先、同价 FIFO；支持 LIMIT/GTC、LIMIT/IOC、LIMIT/FOK 和 MARKET/IOC。FOK 在执行前检查可成交数量，市价单和 IOC 不保留未成交挂单。价格与数量分别使用整数 priceTicks、quantityLots；市价单 priceTicks 为 0。orderId 在本地引擎历史中唯一。

mock 交易测试页面入口为 http://localhost:17001/，包含订单投递、撤单、订单簿和最新成交列表，使用说明见 [mock README](../matching-mock/README.md)。该页面 BTC_USDT 的 priceTicks 精度为 0.01 USDT，quantityLots 精度为 0.0001 BTC。

## RabbitMQ 确认与恢复

1. mock 先将命令提交到 MySQL mock_command_log，再发布持久化 RabbitMQ 消息，等待 publisher confirm，并检查 returned message，随后保存已确认记录。失败命令保留原 commandId；启动时或 POST /mock/retry 重试。新命令发布前先按记录顺序发送未确认命令。
2. 引擎先记录命令并刷盘，再执行撮合。重启时按日志顺序恢复订单簿、处理结果与 commandId 去重状态。相同 commandId、不同内容属于非法请求。
3. 引擎将结果发布到 RabbitMQ，收到确认且未退回后才手动 ACK 输入命令。处理或发布失败时 NACK 并重新入队。重复投递返回相同结果，不重复撮合。
4. mock 将结果提交到 MySQL mock_result_log，按 commandId 去重，再手动 ACK。写入失败则 NACK 重投。
5. 两个 RabbitMQ 输出通道均配置 required-groups，使接收端离线时也存在持久订阅队列。非法业务命令拒绝并进入 DLQ；反序列化等 Binder 处理失败也按配置进入 DLQ，需人工检查后处理。

这是一条“至少一次投递 + 幂等处理”的链路，并非 RabbitMQ 与 MySQL 或引擎本地文件之间的分布式事务。发送确认超时可能实际已投递，重试必须沿用 commandId。HTTP 202 只表示 broker 已确认命令，不表示撮合完成；实际结果从 /mock/results 查看。

本地日志以长度、JSON 数据和 CRC32 组成记录。恢复时截断不完整尾记录，完整记录校验失败则停止启动，禁止静默跳过。保留 data 目录或配置持久磁盘；不得将同一个引擎队列分配给各自独立日志的多个活跃实例。单活队列不能替代引擎状态复制。

## Kafka 推送

可靠结果完成确认后，由独立线程和容量 1024 的队列异步发送行情。队列满、Kafka 发布失败允许丢弃并记录日志，不撤销已经确认的撮合结果。使用 symbol 作为 key，消费者按快照 sequence 忽略旧消息。每次更新携带完整订单簿和该交易对最近一批成交，撤单不清空最新成交。

Kafka 数据仅供行情展示和模拟消费；交易事实以 RabbitMQ 撮合结果为准。引擎无需在启动时预绑定 Kafka 输出通道；mock 的 Kafka 消费者启动仍需要 Kafka 可用。

## 配置与启动

server 默认 profile 为 messaging；mock 默认环境为 dev，自动启用 messaging,database，将命令和可靠结果保存至 MySQL matching_mock。mock 数据库配置见 [mock README](../matching-mock/README.md)。test profile 不启动外部消息通道。连接参数：

| 环境变量 | 默认值 |
| --- | --- |
| RABBITMQ_HOST / RABBITMQ_PORT | localhost / 5672 |
| RABBITMQ_USERNAME / RABBITMQ_PASSWORD | guest / guest（仅本地） |
| RABBITMQ_VIRTUAL_HOST | / |
| KAFKA_BOOTSTRAP_SERVERS | localhost:9092 |
| MATCHING_JOURNAL_PATH | data/engine/commands.journal |
| MOCK_DATA_DIRECTORY | data/mock |
| MOCK_PORT | 17001 |
| SERVER_PORT | 17000 |
| MATCHING_COMMAND_DESTINATION | matching.commands |
| MATCHING_RESULT_DESTINATION | matching.results |
| MATCHING_MARKET_DESTINATION | matching.market |

在项目根目录打包，并在两个终端分别启动：

~~~powershell
.\matching-server\mvnw.cmd -f pom.xml package
java -jar .\matching-server\target\matching-server-0.0.1-SNAPSHOT.jar
java -jar .\matching-mock\target\matching-mock-0.0.1-SNAPSHOT.jar
~~~

先准备 RabbitMQ 和 Kafka；生产环境按集群配置 TLS/SASL、权限、队列复制及 DLQ 监控。当前默认 durable classic queues 并不提供多节点副本保证。当前日志/去重集合未做压缩，恢复为全量回放，适合验证链路；大规模运行需补充快照、归档、容量限制及状态复制。

## 模拟操作

生成卖单 10 手、买单成交 4 手，再撤销卖单剩余 6 手（空订单簿下的预期）：

~~~powershell
Invoke-RestMethod -Method Post http://localhost:17001/mock/scenarios/basic
Invoke-RestMethod http://localhost:17001/mock/results
Invoke-RestMethod http://localhost:17001/mock/market
~~~

自定义下单：

~~~powershell
$command = @{
    commandId = "demo-place-1001"
    action = "PLACE"
    orderId = 1001
    symbol = "BTC_USDT"
    side = "BUY"
    priceTicks = 10000
    quantityLots = 5
} | ConvertTo-Json
Invoke-RestMethod -Method Post http://localhost:17001/mock/commands -ContentType application/json -Body $command
~~~

撤单需要新的 commandId，orderId 指向原订单：

~~~powershell
$cancel = @{
    commandId = "demo-cancel-1001"
    action = "CANCEL"
    orderId = 1001
    symbol = "BTC_USDT"
} | ConvertTo-Json
Invoke-RestMethod -Method Post http://localhost:17001/mock/commands -ContentType application/json -Body $cancel
~~~

查看和重试未获 broker 确认的命令：

~~~powershell
Invoke-RestMethod http://localhost:17001/mock/pending
Invoke-RestMethod -Method Post http://localhost:17001/mock/retry
~~~

mock HTTP 接口仅用于受控测试环境，未提供账户认证、余额检查和撤单权限校验。撮合逻辑尚未实现费用和自成交防护。

## 验证

~~~powershell
.\matching-server\mvnw.cmd -f pom.xml test -B -ntp
~~~

自动测试覆盖撮合优先级、撤单、溢出拒绝、重投幂等、日志恢复/损坏检测、确认顺序、发布退回/NACK、mock 消费持久化与 Kafka 失败隔离。测试使用本地文件和模拟通道，不等同于实际 broker 联调。

参考：[Spring Cloud Stream publisher confirms](https://docs.spring.io/spring-cloud-stream/reference/rabbit/rabbit_overview/publisher-confirms.html)、[RabbitMQ 消费配置](https://docs.spring.io/spring-cloud-stream/reference/rabbit/rabbit_overview/rabbitmq-consumer-properties.html)。

