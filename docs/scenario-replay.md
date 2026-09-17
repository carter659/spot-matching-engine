# 确定性场景回放

入口：`/scenarios.html`，首页“确定性场景回放”卡片。

先在 mock 参数页配置专用交易对，并在 server 启用相同交易对。停止该交易对的其他下单来源，确认盘口为空。页面的确认框是人工隔离确认，系统不能阻止其他客户端同时下单；检测到本地挂单、待确认命令或行情盘口非空时拒绝启动。

场景：价格优先、同价时间优先、部分成交、撤单、IOC、FOK、重复提交。价格为 99/100 ticks，数量为 1–3 lots，按交易对精度换算。每一步等待最多 30 秒可靠回执，逐笔校验 maker 顺序、价格、数量、状态、剩余及取消数量。重复提交检查 mock 的 commandId 复用及最终成交量，不等同于直接向 broker 注入重复消息的测试。

运行批次与逐步报告保存到 MySQL `mock_scenario_run`（JPA 建表，MyBatis Plus 读写）。页面展示最近 50 次运行。原始命令、可靠回执沿用已有持久化链路。

失败停止后续步骤，结束时仅撤销本批次未完成订单。清理未确认标记 CLEANUP_REQUIRED。服务重启将未完成批次标记 INTERRUPTED，不自动重新下单；需在交易页根据报告内 commandId/订单 ID 核查挂单。Kafka 行情只能用于运行前辅助检查，测试断言以 RabbitMQ 可靠回执为准。

验证：ScenarioTests 在隔离 H2 数据库中使用真实 JPA、MyBatis Plus、MockOutbox/MockInbox 和撮合核心执行全部七个场景，并确认批次结束没有可撤挂单。该测试替代 publisher 的网络传输，不是在线 RabbitMQ 端到端压力测试。
