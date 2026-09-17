'use strict';
window.DashboardUI=(()=>{
 const translations={
  "数据看板 · 撮合引擎": "Dashboard · Matching engine",
  "管理导航": "Administration",
  "数据看板": "Dashboard",
  "参数配置": "Configuration",
  "修改密码": "Password",
  "退出登录": "Sign out",
  "真实撮合与消息吞吐，每秒自动刷新。": "Live matching and messaging throughput · refreshed every second.",
  "正在连接": "Connecting",
  "引擎 QPS · 命令 / 秒": "Engine QPS · commands / sec",
  "实际成交 · 笔 / 秒": "Trades / sec",
  "去重后的下单、撤单，含业务拒绝": "Unique orders and cancels, including rejected commands",
  "撮合产生的成交明细笔数": "Individual executions produced by matching",
  "本次启动以来的最高完整秒": "Highest completed second since startup",
  "最大": "Max",
  "命令 / 秒": "commands / sec",
  "笔 / 秒": "trades / sec",
  "累计处理命令": "Total commands",
  "本次运行累计，重启后清零": "Since startup · resets on restart",
  "累计成交笔数": "Total trades",
  "重复投递不重复累计": "Redeliveries are not counted twice",
  "近 60 秒处理趋势": "Throughput · last 60 seconds",
  "━ 命令 QPS": "━ Commands / sec",
  "━ 成交笔 / 秒": "━ Trades / sec",
  "最近60秒命令和成交速率趋势": "Command and trade rates over the last 60 seconds",
  "等待采样…": "Waiting for samples…",
  "消息投递与消费": "Message delivery & consumption",
  "server 侧统计": "Server metrics",
  "RabbitMQ 命令队列未消费消息": "Outstanding RabbitMQ commands",
  "未消费完成（条）": "Outstanding messages",
  "等待消费 Ready": "Ready",
  "处理中 Unacked": "Unacked",
  "下单、撤单共用命令队列": "Shared queue for orders and cancels",
  "正在读取队列积压…": "Reading queue backlog…",
  "链路": "Channel",
  "条 / 秒": "messages / sec",
  "本次运行累计": "Total since startup",
  "接收：进入命令处理器；消费完成：可靠结果确认后发出 ACK。投递：RabbitMQ confirm / Kafka leader ACK 已返回。失败含超时，投递结果可能不确定。": "Received: entered the command handler. Consumed: ACK sent after result confirmation. Delivered: RabbitMQ confirm / Kafka leader ACK received. Failures include timeouts with an uncertain outcome.",
  "行情待发送": "Pending market updates",
  "运行时间": "Uptime",
  "交易对处理明细": "Trading pair activity",
  "筛选交易对": "Filter trading pairs",
  "输入交易对筛选": "Filter pairs",
  "交易对": "Pair",
  "累计命令": "Commands",
  "累计成交": "Trades",
  "悬停交易对名称可查看最新成交及买卖盘挂单笔数、价格档位数；无成交显示“-”，空盘口显示 0。": "Hover over a pair for its last trade, resting orders and price levels. No trade: “-”; empty book: 0.",
  "速率取上一完整秒。看板所有统计与最新成交仅保存在内存中，从本次启动开始，重启后清零。": "Rates use the last completed second. Metrics and latest trades are kept in memory and reset on restart.",
  "速率取上一完整秒，最大值为本次启动以来的最高完整秒。看板所有统计与最新成交仅保存在内存中，从本次启动开始，重启后清零。": "Rates use the last completed second; maxima cover this run. Metrics and latest trades stay in memory and reset on restart.",
  "最大 ": "Max ",
  " / 秒": " / sec",
  "RabbitMQ · 命令接收": "RabbitMQ · commands received",
  "RabbitMQ · 消费 ACK 完成": "RabbitMQ · consumption ACKs",
  "RabbitMQ · 结果确认投递": "RabbitMQ · results confirmed",
  "Kafka · 行情确认投递": "Kafka · market updates confirmed",
  "RabbitMQ · 重入队": "RabbitMQ · requeued",
  "RabbitMQ · 拒绝到死信": "RabbitMQ · dead-lettered",
  "RabbitMQ · 结果投递失败": "RabbitMQ · result delivery failures",
  "Kafka · 投递失败": "Kafka · delivery failures",
  "Kafka · 本地队列满丢弃": "Kafka · dropped (local queue full)",
  " · 已停用": " · disabled",
  "\n最新成交价（ticks）：": "\nLast price (ticks): ",
  "\n最新成交量（lots）：": "\nLast quantity (lots): ",
  "\n买盘：": "\nBids: ",
  " 笔挂单 · ": " orders · ",
  " 个价格档位": " price levels",
  "\n卖盘：": "\nAsks: ",
  "，查看最新成交和买卖盘挂单档位": ", view latest trade and book depth",
  "暂无匹配交易对": "No matching pairs",
  "60 秒前": "60s ago",
  "上一秒": "Last sec",
  "近 60 秒峰值：命令 ": "60s peaks: commands ",
  " / 秒 · 成交 ": " / sec · ",
  " 笔 / 秒": " trades / sec",
  "命令队列：": "Command queue: ",
  " · 包含下单和撤单": " · orders and cancels",
  "每 5 秒采样 · 最近读取 ": "Sampled every 5s · updated ",
  " · 未消费完成 = Ready + Unacked": " · outstanding = Ready + Unacked",
  "不可用：": "Unavailable: ",
  "未获取到队列状态": "Queue status unavailable",
  "● 实时 · ": "● Live · ",
  "连接中断，队列数据已过期": "Connection lost; queue data is stale",
  "连接中断 · 数据已过期": "Disconnected · stale data",
  "明亮主题": "Light theme",
  "深色主题": "Dark theme",
  "全屏": "Full screen",
  "退出全屏": "Exit full screen",
  "语言": "Language",
  "无法切换全屏，请使用浏览器全屏功能": "Full screen unavailable; use your browser full-screen control"
};
 let language='zh-CN',theme='dark';
 document.documentElement.dataset.theme=theme;document.documentElement.lang=language;
 const t=text=>language==='en'?(translations[text]??text):text;
 const nodes=[],attributes=[];
 function controls(){document.getElementById('theme-toggle').textContent=t(theme==='dark'?'明亮主题':'深色主题');document.getElementById('fullscreen-toggle').textContent=t(document.fullscreenElement?'退出全屏':'全屏');}
 function apply(){document.documentElement.dataset.theme=theme;document.getElementById("language-select").value=language;document.documentElement.lang=language;document.title=t('数据看板 · 撮合引擎');nodes.forEach(([node,text])=>node.textContent=text.replace(text.trim(),t(text.trim())));attributes.forEach(([node,key,text])=>node.setAttribute(key,t(text)));controls();window.dispatchEvent(new Event('dashboard-preferences'));}
 document.addEventListener('DOMContentLoaded',()=>{
  const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);let node;
  while(node=walker.nextNode())if(translations[node.textContent.trim()])nodes.push([node,node.textContent]);
  document.querySelectorAll('[title],[aria-label],[placeholder]').forEach(node=>['title','aria-label','placeholder'].forEach(key=>{const value=node.getAttribute(key);if(translations[value])attributes.push([node,key,value]);}));
  const select=document.getElementById('language-select'),themeButton=document.getElementById('theme-toggle');
  const errorBox=document.createElement('p');errorBox.className='preference-error';errorBox.role='alert';errorBox.hidden=true;
  document.querySelector('.display-controls').append(errorBox);
  const busy=value=>{select.disabled=value;themeButton.disabled=value;};
  function error(message){errorBox.textContent=message;errorBox.hidden=!message;}
  async function save(next){
    busy(true);error('');
    try{const saved=await Admin.request('/api/admin/dashboard-preferences',next);theme=saved.theme;language=saved.language;apply();}
    catch{select.value=language;error(language==='en'?'Preferences could not be saved. Please try switching again.':'偏好保存失败，请重新切换。');}
    finally{busy(false);}
  }
  select.onchange=()=>save({theme,language:select.value});
  themeButton.onclick=()=>save({theme:theme==='dark'?'light':'dark',language});
  async function load(){
    busy(true);
    try{await Admin.ready;const saved=await Admin.request('/api/admin/dashboard-preferences');theme=saved.theme;language=saved.language;apply();error('');busy(false);}
    catch{error(language==='en'?'Preferences could not be loaded. Retrying…':'偏好读取失败，正在自动重试…');setTimeout(load,5000);}
  }
  load();
  document.getElementById('fullscreen-toggle').onclick=async()=>{try{if(document.fullscreenElement)await document.exitFullscreen();else await document.documentElement.requestFullscreen();}catch{Admin.status(t('无法切换全屏，请使用浏览器全屏功能'),true);}};
  document.addEventListener('fullscreenchange',controls);apply();
 });
 return {t,get language(){return language;}};
})();
