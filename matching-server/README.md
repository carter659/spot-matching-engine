# 撮合引擎参数配置

启动 server 后访问 **http://localhost:17000/**。

- 新引擎默认启用 BTC/USDT，网页可选择一个、多个或全部 20 个交易对。
- 配置最小/最大下单数量（lots）。当前目录 1 lot = 0.0001 个基础币；价格精度随币种不同。
- 保存立即生效。未启用交易对或数量超限返回可靠 `REJECTED` 结果，仍通过 RabbitMQ 交给 mock。
- 移除仍有挂单的交易对会被拒绝，先完成或撤销该币种挂单。数量范围调整不影响已有订单的撤单。
- 参数日志位于命令日志旁，默认 `data/engine/commands.journal.settings`；必须和 `commands.journal` 一起保留。参数记录对应的命令位置，重启按原时序恢复，不会按新配置重做旧订单。
- 旧命令日志首次升级时，会保留其中已出现的交易对并启用 BTC；之后以配置日志为准。

```powershell
# 正常启动：消息通道与配置网页同时启用
java -jar .\matching-server\target\matching-server-0.0.1-SNAPSHOT.jar

# 仅初始化参数：无需启动 RabbitMQ/Kafka，不消费交易命令
java -jar .\matching-server\target\matching-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=configuration
```

仅配置模式保存后，停止该进程，再以默认 messaging 模式启动同一日志目录。Windows JDK 临时套接字设置见 [mock 使用说明](../matching-mock/README.md)。

## 多实例

每个 server 实例使用独立 `SERVER_PORT` 和 `MATCHING_JOURNAL_PATH`。不同交易对配置的实例不能竞争同一个输入队列。

为每组 server/mock 配置相同的一组三个环境变量，不同组使用不同值：

| 环境变量 | 默认值 | 示例：BTC 独立实例 |
| --- | --- | --- |
| MATCHING_COMMAND_DESTINATION | matching.commands | matching.btc.commands |
| MATCHING_RESULT_DESTINATION | matching.results | matching.btc.results |
| MATCHING_MARKET_DESTINATION | matching.market | matching.btc.market |

mock 实例还需独立端口及 MySQL 数据库。当前一个 mock 连接一组引擎消息通道，币种下拉框不负责把订单自动路由到不同实例。

## HTTP 接口

- `GET /api/engine/pairs`：共享的 20 个交易对目录及精度。
- `GET /api/engine/configuration`：当前生效参数。
- `POST /api/engine/configuration`：保存 `symbols`、`minOrderLots`、`maxOrderLots`。
- `POST /api/engine/snapshot`：创建包含命令和配置历史的校验备份；页面提供按钮，离线恢复说明见 [根文档](../README.md#快照与恢复)。

```json
{"symbols":["BTC_USDT","ETH_USDT"],"minOrderLots":1,"maxOrderLots":100000000}
```
