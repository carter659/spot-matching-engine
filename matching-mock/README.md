# 现货撮合测试页面

matching-mock 首页 **http://localhost:17001/** 为测试引导页：进入 `/parameters.html` 配置参数，进入 `/trading.html` 投递交易。静态页面随 Spring Boot JAR 一起发布，无需安装前端构建工具。

交易对参考参数保存在 MySQL `mock_trading_pair` 表。默认初始化 20 个交易对，不覆盖已有参数。配置页默认只读，点击“修改”后可保存或取消示例价格修改。支持添加新的 USDT 交易对（价格精度 0–8，数量精度固定 4）；币种与精度创建后固定，重新添加已删除币种必须保持原精度。价格必须为正、满足币种精度且转换后不超过 long 范围。
删除采用软删除，不影响历史订单和撤单，也不会在重启时自动恢复。新增币种还需在 server 中启用后才能撮合。`POST /mock/parameters/pairs` 添加，`DELETE /mock/parameters/pairs/{symbol}` 删除。
交易页通过 `GET /mock/parameters/pairs` 读取数据库目录及示例价格；修改后刷新交易页生效。保存接口为 `POST /mock/parameters/pairs/{symbol}`，请求体 `{"examplePrice":"65000.00"}`。目录读取失败时禁止新下单，原有撤单及重试机制保留。

mock 与 server 均使用根 POM 管理的 Spring Boot 4 Web MVC。server 默认端口为 17000，mock 默认端口为 17001，可分别通过 `SERVER_PORT`、`MOCK_PORT` 覆盖。

Java 源码按职责分包：`controller`（HTTP 接口）、`service`（业务服务）、`dto`（请求与响应）、`config`（配置）、`messaging`（消息发布）、`repository`（可靠消息存取）、`market`（行情缓存）。启动类 `App` 位于模块根包。

## 功能

- 左上角交易对下拉框支持 20 个 USDT 交易对：BTC、ETH、BNB、SOL、XRP、DOGE、ADA、TRX、AVAX、LINK、DOT、LTC、BCH、UNI、ATOM、NEAR、APT、ARB、OP、SHIB。
- 切换后下单单位、价格精度、订单簿、成交与委托记录随币种变化，图表不会混入其他币种数据。示例价格仅是测试输入，不是实时行情。
- 先在 [server 配置页面](http://localhost:17000/) 启用要测试的币种；未启用的交易对会被引擎拒绝。配置说明见 [server README](../matching-server/README.md)。

- 限价单：GTC、IOC、FOK；市价单：按基础币数量下单，即时成交，未成交部分取消。
- 买入、卖出、撤销剩余挂单、查看当前委托与委托记录。
- 投递失败时保留原 commandId 重试；“已投递确认”不等于“已成交”。
- 显示 Kafka 订单簿与最新成交列表；列表保留 mock 本次运行期间最近 100 笔成交，按成交编号去重，最新在上、内部滚动。
- 最新成交以时间、价格、数量三列与订单簿并排展示，手机端纵向排列；悬停查看成交编号。价格颜色表示相邻成交的涨跌，平价沿用前一次颜色，不代表买卖方向。
- 时间为 **mock 接收时间**，不是撮合发生时间；行情缓存重启后重新积累。
- 根据页面收到的成交生成价格走势；没有行情时显示等待状态，不生成虚假报价或成交。
- 适配桌面和手机；支持深浅主题。

页面通过 HTTP 调用 mock，mock 再通过 Spring Cloud Stream 将命令投递到 RabbitMQ。撮合结果通过 RabbitMQ 回到 mock；订单簿和最新成交由 Kafka 消费者更新。浏览器每 1.5 秒读取 mock 的状态，未直接连接消息中间件。

## 启动

1. 准备 JDK 25、RabbitMQ 和 Kafka，连接参数见 [消息接入说明](../matching-server/MESSAGING.md)。
2. 从项目根目录打包：

```powershell
.\mvnw.cmd package
```

3. 在两个终端中分别启动服务：

```powershell
java -jar .\matching-server\target\matching-server-0.0.1-SNAPSHOT.jar
```

```powershell
java -jar .\matching-mock\target\matching-mock-0.0.1-SNAPSHOT.jar
```

4. 打开 http://localhost:17001/。默认使用 dev 环境，自动启用 messaging,database 功能配置；test profile 只用于自动测试，不启用真实订单接口。

## 环境配置

- `application.properties`：应用名称、默认环境及 profile 分组。
- `application-dev.properties`：开发环境端口、MySQL、RabbitMQ、Kafka 连接参数。
- `application-database.properties`：JPA 建表、连接池及 MyBatis Plus 所用的数据源基础设置。
- `application-messaging.properties`：消息绑定、路由与 ACK 策略。
- `application-test.properties`：自动测试隔离配置。

未指定环境时默认 dev。显式切换使用 Spring Boot 标准参数：

```powershell
java -jar matching-mock/target/matching-mock-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
# 或在启动前设置环境变量：
$env:SPRING_PROFILES_ACTIVE = 'dev'
```

STS 的 Program arguments 同样填写 `--spring.profiles.active=dev`。新增环境时增加 `application-环境名.properties`，并在基础文件中声明 `spring.profiles.group.环境名=messaging,database`；不要只启用功能 profile 代替包含连接参数的环境配置。

## MySQL 数据库

页面主题、所选交易对、委托列表筛选保存在 `mock_preference` 表，打开页面自动恢复；修改立即生效，并显示数据库保存结果。数据库不可用时显示未保存提示。
当前没有登录用户，偏好由连接同一数据库的 mock 页面共享。首次无主题配置时跟随系统主题。接口为 `GET /mock/preferences` 和 `POST /mock/preferences`（`key`、`value`）；仅允许 `theme`、`symbol`、`orderList`。
待重试订单优先显示该订单的交易对，避免与偏好币种不一致；待重试命令草稿仍保留原有会话恢复机制。

dev 默认连接 `localhost:3306/matching_mock`（下划线），用户名 `root`、空密码；以 `application-dev.properties` 或 `MOCK_DB_URL` 的实际值为准。Spring Boot 使用 HikariCP 管理连接。

首次部署在 MySQL 中执行 [建库脚本](database/create-database.sql)。JPA/Hibernate 根据 `entity` 包的实体创建和更新表（`ddl-auto=update`），禁用 SQL 脚本建表和 JPA Repository。业务读写全部由 MyBatis Plus 的 `mapper` 执行，包括命令日志、可靠结果、偏好和交易对参数；仓储在 JPA 完成初始化后才读取数据库。

建库脚本使用 `matching_mock`。本机 MySQL 5.6 使用 Hibernate `MySQLLegacyDialect`，可通过 `MOCK_DB_DIALECT` 覆盖；H2 测试使用独立的 H2 方言。

| 环境变量 | 默认值 |
| --- | --- |
| MOCK_DB_URL | jdbc:mysql://localhost:3306/matching_mock?connectionTimeZone=UTC&characterEncoding=UTF-8 |
| MOCK_DB_USERNAME | root |
| MOCK_DB_PASSWORD | 空 |

- `mock_command_log`：保存下单/撤单、方向、订单类型、价格、数量及 RabbitMQ 投递确认记录。同一命令通常有待投递与已确认两条日志。
- `mock_result_log`：保存引擎可靠结果、状态、剩余数量、撤销数量；`payload` 包含完整成交明细。
- 命令先提交数据库再发 RabbitMQ；结果先提交数据库再 ACK。重启从数据库按日志顺序恢复状态和未确认命令。
- Kafka 订单簿和最新成交仍为运行时缓存；可靠成交明细保存在结果表中。
- 数据库连接失败会报错，不自动切换文件保存。`messaging` profile 自动包含 `database`，避免遗漏数据库配置后偏好接口返回 404；旧文件日志不会自动导入数据库。
- 当前 mock 按单实例使用，同一数据库不要同时运行多个 mock；日志会持续增长。

显式指定环境时使用 `--spring.profiles.active=dev`。开发连接配置在 `src/main/resources/application-dev.properties`。

STS 修改依赖后，执行 Maven → Update Project，再终止旧的 mock 进程并重新运行 `App`。仅刷新浏览器或编译类文件不能更新旧 JVM 的依赖列表。也可用重新打包后的 JAR 启动。

本机 Windows JDK 25 若出现 `Unable to establish loopback connection`，可将 Unix domain socket 临时目录指定为项目内路径。以下在项目根目录执行，不修改全局 JDK 设置：

```powershell
New-Item -ItemType Directory -Force .runtime/sockets | Out-Null
$matchingSocketDirectory = (Resolve-Path .runtime/sockets).Path
java "-Djdk.net.unixdomain.tmpdir=$matchingSocketDirectory" -jar .\matching-server\target\matching-server-0.0.1-SNAPSHOT.jar
# 在另一个终端设置相同的 matchingSocketDirectory 后启动 mock：
java "-Djdk.net.unixdomain.tmpdir=$matchingSocketDirectory" -jar .\matching-mock\target\matching-mock-0.0.1-SNAPSHOT.jar
```

## 测试流程

在空订单簿下，可以先点击“投递示例卖单”，创建价格 65,000 USDT、数量 0.3000 BTC 的限价 GTC 卖单，然后分别测试：

| 委托 | 预期行为 |
| --- | --- |
| 65,000 USDT 买入 0.3500 BTC，LIMIT/GTC | 成交 0.3000，剩余 0.0500 挂单 |
| 同样的数量和价格，LIMIT/IOC | 成交 0.3000，剩余 0.0500 取消 |
| 同样的数量和价格，LIMIT/FOK | 可成交数量不足，整笔取消，卖盘不变 |
| MARKET 买入 0.3500 BTC | 吃掉可用卖盘，未成交余量取消，不挂单 |

每个场景会实际改变测试订单簿，需要分别准备对应盘口。页面的盘口预估可能滞后，最终结果以 RabbitMQ 回执为准。订单簿初始为空时，市价单会取消全部未成交数量。

## 精度与接口

页面支持共享目录中的 20 个交易对；价格精度依币种为 2、4、6 或 8 位，数量统一为 4 位。BTC_USDT 的 1 tick = 0.01 USDT，1 lot = 0.0001 BTC。页面发送十进制字符串，Java 使用 BigDecimal 精确转为 long；订单号以字符串返回，避免 JavaScript 整数精度损失。底层撮合仍使用整数 tick/lot。

| 接口 | 用途 |
| --- | --- |
| GET /mock/trading | 委托状态、订单簿、最近成交及待确认命令数 |
| POST /mock/trading/orders | 下单；字段为 commandId、symbol、side、orderType、timeInForce、price、quantity |
| POST /mock/trading/orders/{orderId}/cancel | 撤单；请求体包含新的 commandId |
| POST /mock/retry | 按持久记录顺序重试未确认命令 |

同一请求重试必须保留 commandId 和原始内容。市价单 price 为 null 或 0，timeInForce 为 IOC。订单旧协议未传 orderType/timeInForce 时仍解释为 LIMIT/GTC，已有命令日志可回放。

## 验证

从项目根目录执行：

```powershell
.\mvnw.cmd test -B -ntp
node --test matching-mock/src/test/frontend/trading-model.test.mjs
```

本机临时套接字问题也会影响 HTTP 自动测试，可追加 `"-DargLine=-Djdk.net.unixdomain.tmpdir=$matchingSocketDirectory"`。

Java 测试覆盖实际撮合引擎、持久化、页面接口、静态资源服务和 ACK 行为；前端测试覆盖精度、策略预估、消息状态区分及输出转义。自动测试中的发布器/通道为测试替身，不能替代实际 RabbitMQ/Kafka 联调。
