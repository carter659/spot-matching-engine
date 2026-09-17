# 撮合引擎管理后台

启动 server 后访问 http://localhost:17000/，默认账号 root，初始密码 root。登录后进入数据看板，导航可进入参数配置与修改密码。修改密码后所有会话失效；新密码长度 8–128 个字符。密码使用随机盐 PBKDF2-HMAC-SHA256 保存。

## 存储与迁移

打包运行时，H2 数据库为 jar 同目录的 data/matching-admin.mv.db，命令日志默认为 data/engine/commands.journal。启动命令所在目录不会影响这些默认路径。IDE/开发运行时使用当前工作目录下的 data，保留开发环境的原日志位置。

可通过 matching.admin-data-directory 与 MATCHING_JOURNAL_PATH 覆盖数据库目录与命令日志。升级时将原命令日志和相邻的 .settings 文件放到目标日志位置，或明确指定原 MATCHING_JOURNAL_PATH。首次连接空数据库时自动将原 .settings 的完整配置历史迁入 H2，旧文件保留，此后的配置更新仅写 H2。历史按原命令边界重放，避免重启后改变订单处理结果。

数据库和命令日志属于同一引擎实例，应一起保留与备份；不要把空命令日志与另一实例的配置数据库混用。页面创建的重放快照包含命令和配置历史，不含登录密码。原有离线快照恢复命令继续可用；恢复生成的 .settings 会在空 H2 首次启动时迁入。

交易对可输入 BTC_USDT、BTC/USDT、BTC-USDT，自动转大写下划线格式，每行一个或用逗号分隔。支持目录外交易对，但生产方必须保持整数价格和数量单位一致。移除有挂单的交易对仍会拒绝。

## 统计口径

- 引擎 QPS：上一完整秒新处理的命令，包含下单、撤单和业务拒绝，不重复统计相同 commandId。
- 成交速率：上一完整秒撮合结果中的成交明细笔数；一条命令可能产生多笔成交。
- 看板所有统计（总数、各交易对计数、曲线、消息速率和最新成交）只保存在内存中，不写 H2 或其他持久化存储，也不从命令日志恢复；进程重启全部清零。命令日志仍用于恢复撮合业务状态和幂等性。
- 前端通过 MetricsController 的 GET /api/engine/metrics 读取内存快照。最新成交价/量取本次运行中最后一笔实际成交，单位为 ticks/lots；接口以十进制字符串返回以避免 64 位整数精度丢失，无成交返回 null；页面在交易对名称的悬停提示中展示价量，无成交显示“-”，不单独占用列表列。
- RabbitMQ 接收：命令进入处理器；消费完成：结果确认后 basicAck 调用成功；投递成功：结果收到 publisher confirm 且未被退回。
- Kafka 投递成功：行情发送收到 leader ACK（acks=1）。同步等待在独立的行情线程完成，不阻塞可靠 RabbitMQ ACK 流程。失败/超时不代表 broker 一定未收到；本地队列满的丢弃单独统计。
- server 只消费 RabbitMQ 命令、投递 RabbitMQ 结果和 Kafka 行情，因此看板不包含 mock 的消费速度或 broker 集群吞吐。

会话采用 HttpOnly / SameSite=Strict Cookie，写操作需要 CSRF Token。使用 HTTPS 部署时可设置 server.servlet.session.cookie.secure=true。

交易对 tips 同时显示当前买盘/卖盘的挂单笔数及非空价格档位数：部分成交但未完全成交的订单仍计为一笔，相同价格的多笔订单只计一个档位。空盘口显示 0。这些数值由控制器读取内存订单簿，不另行持久化；重启后反映业务恢复的实际挂单，不受本次运行累计计数清零影响。

## RabbitMQ 未消费完成数量

看板展示命令队列（下单和撤单共用）的 Ready、Unacked，以及两者之和。server 每 5 秒通过 RabbitMQ Management HTTP API 读取一次，只将快照缓存在内存；控制器返回快照，前端不接触 RabbitMQ 账号。RabbitMQ 自身有统计刷新延迟。获取失败或统计字段缺失显示不可用，不伪造为 0。

默认管理地址 http://localhost:15672，用户名密码继承 spring.rabbitmq 配置；可通过 RABBITMQ_MANAGEMENT_URL、RABBITMQ_MANAGEMENT_USERNAME、RABBITMQ_MANAGEMENT_PASSWORD 覆盖。需启用 RabbitMQ management 插件且账号有相应虚拟主机的队列查看权限。队列名称由 commands-in-0 的 destination/group 与 Rabbit consumer prefix/queue-name-group-only 推导；特殊部署可设置 matching.rabbitmq.command-queue 指定实际物理队列名。配置独立 binder 环境时应将管理连接参数指向该 binder 对应的 broker。

## RabbitMQ 吞吐配置

server 的 commands-in-0 与 mock 的 results-in-0 默认使用 Direct 消费容器，减少 RabbitMQ 客户端线程到消费线程的额外切换。可设置 MATCHING_COMMAND_CONTAINER_TYPE=simple 或 MATCHING_RESULT_CONTAINER_TYPE=simple 回退。

server 命令消费仍固定 concurrency=1、prefetch=1，保留 single-active-consumer 与手动 ACK，避免高预取下失败重投导致命令越序。mock 的结果消费仍单线程，因为 Inbox 写入有同步锁；prefetch 默认提高为 128，可用 MATCHING_RESULT_PREFETCH 调整，大消息可降低该值。结果重新投递有 commandId 去重，落库成功后才 ACK。

publisher confirm、returns、持久化消息、死信队列、队列声明参数保持原值；无需删除已有队列。没有开启消息批量封装或自动 ACK。逐条 confirm 和命令日志强制落盘仍会限制单引擎吞吐；要显著提高吞吐，需要另行设计有序确认流水线或按交易对分区，而不能直接增加命令消费者数量。

本机隔离对比（一次预热 100 条，随后三批各 400 条，等待可靠结果返回）：Simple 为 684.6 / 699.0 / 952.7 条每秒，Direct 为 828.6 / 681.4 / 969.1 条每秒；三批总耗时约 1.576 / 1.483 秒。两组各 1300 条结果顺序校验通过。此测试仅比较 server 消费容器，不代表 mock/MySQL 全链路吞吐，也不是稳定性能承诺。
