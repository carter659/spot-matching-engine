# 撮合引擎设计与机制

本文描述当前仓库实际实现。快速启动、消息 JSON 和接入示例见 [README](../README.md)。

## 1. 系统边界

`matching-core` 接收 `OrderCommand`，返回 `MatchingOutcome`，其中包括可靠结果 `OrderResult` 和行情 `MarketUpdate`。它不负责生成订单 ID、账户余额校验、资产冻结、结算或手续费。接入业务应在下单前完成必要的账户及风险校验，并在可靠结果消费端执行幂等记账。

`matching-server` 负责持久化与中间件交互，`matching-mock` 是模拟接入方。mock 保存交易对精度并把十进制输入换算成整数；核心只识别整数 ticks/lots，不从交易对代码推断精度。因此独立接入方必须采用一致的单位契约。

## 2. 订单簿结构

| 结构 | 当前实现 | 目的 |
| --- | --- | --- |
| `books` | symbol → OrderBook | 隔离各交易对盘口 |
| `bids` | 降序 NavigableMap | 最优买价在首位 |
| `asks` | 升序 NavigableMap | 最优卖价在首位 |
| `PriceLevel` | priceTicks、totalRemainingLots、head、tail | 汇总一个价位的剩余量 |
| `OrderNode` | orderId、side、remainingLots、previous、next、level | 价位内 FIFO 链表 |
| `ordersById` | 订单 ID → 节点 | 撤单时直接定位 |

新挂单加入价位链表尾部，撮合从对手方最优价的链表头取单。节点成交完或撤销时从链表和 ID 索引移除；价位为空时删除价位。档位维护的是剩余数量，不是原始委托数量。

价格树查找/插入/删除的复杂度约为 O(log P)，P 为价格档位数。订单 ID 哈希查找期望 O(1)，摘除链表节点 O(1)，删除空档位仍需树操作。扫单成本与实际触及的订单数、档位数有关。FOK 会预先扫描符合价格的档位。完整请求还包括日志刷盘、全盘口快照和消息发布，不能仅根据核心数据结构宣称端到端固定吞吐。

## 3. 成交与订单状态

### 价格与时间优先

对手方最优价优先；同价按节点入队顺序成交。并发请求以引擎实际接收和处理顺序为准。不同客户端的墙上时钟和雪花 ID 不能代替队列顺序。

例：卖盘依次为 A：100 × 2、B：100 × 3、C：101 × 1。买入限价 101、数量 4 时，成交 A 的 2 和 B 的 2，成交价均为 100，B 剩余 1，C 不变。

### 成交数量与价格

每次 `filled = min(takerRemaining, makerRemaining)`。同步减少 taker 剩余、maker 剩余和档位汇总量，成交价取 maker 的 `priceTicks`。成交 ID 为 `commandId + ':' + 本命令成交序号`，回放时保持确定性。

### GTC / IOC / FOK

- GTC：只对限价单挂剩余量；完全未成交为 OPEN，成交部分且仍挂单为 PARTIALLY_FILLED。
- IOC：有剩余量时 EXPIRED，`cancelledLots` 为未成交量；其 trades 仍可能非空。
- FOK：先在同一撮合锁内检查可用总量，不足返回 EXPIRED，全部委托量计入 cancelledLots，trades 为空，不修改对手方。
- 完全成交为 FILLED。
- 市价单必须为 MARKET/IOC，priceTicks=0，数量仍是基础币 lots。

成交与失效混合时，对有效下单命令应满足：原始数量 = 本命令成交数量之和 + remainingLots + cancelledLots。REJECTED 命令未进入这一业务分配过程，应单独处理。

### 撤单

使用原 symbol 和 orderId 查找当前挂单，仅取消节点剩余量。订单已经完全成交、撤完或不属于该交易对时返回 REJECTED。重投相同撤单 commandId 返回原结果；新 commandId 再次撤销已终结订单则是新的拒绝结果。

### maker 状态维护

`OrderResult` 对应输入命令，通常是 taker 命令。maker 后续被其他订单成交时，不会收到以原下单 commandId 重写的新结果。消费端根据 `Trade.makerOrderId` 和 `Trade.takerOrderId` 分别累计成交量，再结合撤单事件重建订单状态。仅保存每个 orderId 最后一个下单回执会导致 maker 显示错误。

## 4. 精度与标识

例如价格精度 2、数量精度 4：

```text
priceTicks   = 65000.00 × 10^2 = 6500000
quantityLots = 0.3000 × 10^4  = 3000
金额         = priceTicks × quantityLots / 10^(2+4)
```

外部金额计算应使用 BigDecimal/大整数，不能假设两个 long 的乘积仍不溢出。mock 的输入转换要求能够精确表示为 long，不静默四舍五入订单输入。

`commandId` 标识一次业务命令，`orderId` 标识一笔委托。同 commandId 同内容幂等返回旧结果；同 commandId 不同内容是非法命令。引擎还保留历史 usedOrderIds，避免已经终结的订单 ID 被重新用于下单。

mock 使用雪花 ID：41 位毫秒差、10 位 worker、12 位毫秒内序号，epoch 为 2026-01-01 UTC。时钟回拨或毫秒内序号耗尽时拒绝发号，不默默生成重复 ID。多实例配置不同 worker；跨实例是趋势有序，不代表统一撮合顺序。

## 5. 串行执行模型

`MatchingEngine.process` 和 `ReliableMatchingService.process` 使用同步锁，单个实例内所有交易对串行修改。Rabbit 命令入口采用单消费者、prefetch=1、single-active-consumer。FOK 预检与实际执行之间不会插入另一条命令。

这不是分布式共享订单簿。增加实例并让它们竞争同一命令队列，不能得到同一个盘口的并行加速。若按 symbol 拆分引擎，需要外部稳定路由、独立消息 destination 和独立持久化，当前 mock 的交易对下拉框不实现跨引擎路由。

## 6. 持久化与故障窗口

1. mock 持久化逻辑命令及生成的 ID，然后投递 RabbitMQ。
2. server 校验命令，首次处理先追加并刷盘日志，再执行撮合。
3. server 发布可靠结果并等待 broker confirm。
4. 成功后 ACK 原命令，再异步提交行情发送任务。
5. mock 的结果消费者先幂等保存，再 ACK 结果。

| 故障窗口 | 预期处理 |
| --- | --- |
| 上游发送超时，无法确认 broker 是否接收 | 原 commandId / orderId / 内容重试 |
| server 刷盘后、执行或 ACK 前退出 | 重启回放日志；重投命令返回同一结果 |
| 结果发送失败 | 原命令 NACK requeue，结果可能再次发送 |
| 结果落库后 ACK 失败 | 结果重投；消费端幂等消除重复副作用 |
| Kafka 不可用或发送队列满 | 可丢行情，不撤销可靠撮合；恢复后周期盘口刷新 |
| 非法协议或同 commandId 不同内容 | reject 且不 requeue，依赖 Binder 死信配置处理 |
| 未启用交易对、数量超限等业务拒绝 | 返回可靠 REJECTED，正常发布与 ACK |

命令日志是带 CRC32 的追加文件，单记录最大 16 MiB，追加后 force 刷盘。只容忍尾部不完整记录的截断；校验不通过的完整记录需要人工排查，不能随意丢掉继续运行。

至少一次投递与幂等能防止重复业务处理，但不保证消息绝不重复，也没有跨存储的原子提交。生产系统需另外设计重试监控、死信处置、业务对账和灾难恢复方案。

## 7. 配置历史与快照

server 将引擎参数变化与 `effectiveAfterCommands` 一起持久化，回放时在对应命令边界应用，避免用当前配置重新解释历史命令。默认 Web 服务将配置历史、管理账号和偏好存入本地 H2，命令仍存文件日志。

禁用有挂单的交易对会被拒绝，必须先撤销或完成挂单。最小/最大数量配置针对下单，不应阻止已有挂单撤销。

快照是命令和配置历史归档，带完整性校验，不是内存对象镜像。离线恢复先写临时目录、验证回放，再发布到不存在的目标目录，不覆盖在线文件。恢复步骤见 README。管理账号、mock 数据库和外部消费者的业务账本需单独备份。

## 8. 行情与显示

每次新命令处理后生成全盘口快照，序号在引擎内递增。不同 symbol 共享序号来源，单个 symbol 的序号可以跳跃。行情缓存按 symbol 忽略旧序号，latestTrades 按 tradeId 去重。

server 另有默认 5 秒一次的盘口刷新。Kafka producer 的 key 是 symbol，当前配置 acks=1、关闭 producer 幂等；异步发送队列容量 1024。该通道不承诺完整成交归档，应从 RabbitMQ 结果获取记账所需成交。

交易页面约每 1.5 秒轮询 mock，不是 broker 到浏览器的逐条实时推送。页面最新成交时间是 mock 接收时间，协议 Trade 本身没有撮合时间字段。仪表盘每秒速率和最大值来自本次运行内存统计，重启清零；恢复盘口挂单数量与历史指标清零是两件不同的事。

## 9. 验证建议

从空盘口执行确定性场景，检查价格优先、FIFO、FOK 不足时不修改 maker、部分成交剩余量、撤单及幂等。再验证重复投递、消费失败、日志截断恢复、配置变更回放。

压力测试应同时报告机器、JDK、订单分布、档位数、maker 数、消息确认方式、日志持久化和端到端延迟，不能把管理截图瞬时 QPS 当作性能承诺。核心吞吐和包含 broker/磁盘/数据库的系统吞吐应分别测量。

## 10. 代码导航

- [MatchingEngine](../matching-core/src/main/java/com/exchange/matching/core/orderbook/MatchingEngine.java)：价格时间优先、FOK、成交和撤单。
- [OrderBook](../matching-core/src/main/java/com/exchange/matching/core/orderbook/OrderBook.java)：盘口与订单索引。
- [ReliableMatchingService](../matching-server/src/main/java/com/exchange/matching/server/service/ReliableMatchingService.java)：先日志后处理、配置回放。
- [CommandHandler](../matching-server/src/main/java/com/exchange/matching/server/messaging/CommandHandler.java)：ACK/NACK 顺序。
- [DurableJournal](../matching-persistence/src/main/java/com/exchange/matching/persistence/journal/DurableJournal.java)：校验、刷盘与尾部恢复。
- [MockMessagingConfiguration](../matching-mock/src/main/java/com/exchange/matching/mock/config/MockMessagingConfiguration.java)：结果消费与行情缓存。
