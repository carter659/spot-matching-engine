# 对敲策略：跟随成交

首页 → 对敲策略（/trade-strategies.html）。第一版采用主动单回放：订阅币安 aggTrade，buyerMaker=false 表示主动买入，true 表示主动卖出。只访问 wss://data-stream.binance.vision/ws/<symbol>@aggTrade，不需要 API Key，不向币安投递订单。

每个交易对一个策略，新增默认暂停。支持数量倍率（最多8位小数）、每秒最大新单数1–100、单笔基础币数量上限、行情过期时间1–60秒。默认倍率1、每秒5单、单笔上限1、过期5秒。超频、过期、未来异常时间和精度不足跳过，数量乘倍率后截取上限并向下取整，买价向下、卖价向上取整。生成 LIMIT/IOC，通过现有 RabbitMQ/outbox 链路投递，未成交部分由引擎自动取消。

数据表 mock_trade_strategy 保存配置、收到/跳过计数和成交 ID 高水位；mock_trade_replay 保存源成交、稳定 commandId、本地订单 ID、回执状态和实际成交数量。JPA 建表，MyBatis Plus 读写。先持久化回放记录，再投递；崩溃或失败重试复用 commandId 和雪花 ID。删除策略保留最小配置行及高水位，列表隐藏；再次添加同交易对复用去重进度。部署仅支持单个 mock 执行器使用同一数据库。

暂停/删除关闭订阅并丢弃未受理的缓存行情；已持久化受理的命令继续重试及接收回执。重启恢复运行意图。断线自动重连，5–60秒退避，23小时轮换连接，回复服务端 ping。每条订阅缓冲最多1000条，溢出丢弃最旧并计入跳过；发布异常后本批未受理行情跳过并计数。只回放实时行情，不补放断线期间历史。

统计区分已投递和实际成交：有成交笔数指本地回放订单已成交数量大于零的订单数，不等同于撮合成交事件数。失败统计为引擎拒绝或终态失败，发布重试在说明中显示。IOC 零成交显示已结束。记录每页50条，保留参考价/量、本地下单价/量、订单 ID、真实成交数量。已确认 CANCELLED 的策略关联记录自动清理，原始命令/回执/成交数据保留；EXPIRED 的 IOC 回放记录保留用于查看无成交或部分成交结果。

适合配合盘口策略测试；真实本地盘口决定成交价格和数量。该模式不生成成对订单、不改变撮合规则、不保证两笔指定订单互相成交。

验证：TradeStrategyTests 使用独立 H2、真实撮合引擎覆盖买卖方向、IOC、去重、重试、限流、过期、暂停和参数校验。BinanceTradeStreamLiveTests 通过 -Dbinance.stream.live=true 启用公开行情联通测试，不投递订单。

行情字段依据：https://github.com/binance/binance-spot-api-docs/blob/master/web-socket-streams.md#aggregate-trade-streams
