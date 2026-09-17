'use strict';
const {$,request,ready,status}=Admin;
const {t}=DashboardUI;
const format=n=>Number(n).toLocaleString(DashboardUI.language);
let disconnected=false;
let latest;
function rateCell(cell,counter){
  cell.textContent=format(counter.perSecond);
  const peak=document.createElement('small');peak.className='rate-peak';
  peak.title=t('本次启动以来的最高完整秒');
  peak.textContent=t('最大 ')+format(counter.maxPerSecond)+t(' / 秒');cell.append(peak);
}
const queueNames=[['rabbitReceived','RabbitMQ · 命令接收'],['rabbitAcked','RabbitMQ · 消费 ACK 完成'],['rabbitPublished','RabbitMQ · 结果确认投递'],['kafkaPublished','Kafka · 行情确认投递'],['rabbitRetried','RabbitMQ · 重入队'],['rabbitRejected','RabbitMQ · 拒绝到死信'],['rabbitPublishFailed','RabbitMQ · 结果投递失败'],['kafkaFailed','Kafka · 投递失败'],['kafkaDropped','Kafka · 本地队列满丢弃']];
function row(values){const tr=document.createElement('tr');values.forEach(value=>{const td=document.createElement('td');td.textContent=value;tr.append(td);});return tr;}
const tradeTip=document.createElement('div');
tradeTip.id='latest-trade-tip';tradeTip.className='trade-tooltip';tradeTip.role='tooltip';tradeTip.hidden=true;
document.body.append(tradeTip);
let tipTrigger=null;
function hideTradeTip(){if(tipTrigger)tipTrigger.removeAttribute('aria-describedby');tipTrigger=null;tradeTip.hidden=true;}
function showTradeTip(trigger){
  if(tipTrigger&&tipTrigger!==trigger)tipTrigger.removeAttribute('aria-describedby');
  tipTrigger=trigger;tradeTip.textContent=trigger.dataset.tradeTip;tradeTip.hidden=false;
  trigger.setAttribute('aria-describedby',tradeTip.id);
  const rect=trigger.getBoundingClientRect();
  const left=Math.max(8,Math.min(rect.left,window.innerWidth-tradeTip.offsetWidth-8));
  const top=rect.bottom+8+tradeTip.offsetHeight<=window.innerHeight?rect.bottom+8:Math.max(8,rect.top-tradeTip.offsetHeight-8);
  tradeTip.style.left=left+'px';tradeTip.style.top=top+'px';
}
function pairs(){
  if(!latest)return;
  const filter=$('pair-filter').value.toUpperCase().replace('/','_');
  const rows=latest.pairs.filter(p=>p.symbol.includes(filter)),body=$('pair-rows');
  const existing=new Map([...body.children].map(tr=>[tr.dataset.symbol,tr]));
  const visible=new Set(rows.map(p=>p.symbol));
  for(const tr of [...body.children])if(!visible.has(tr.dataset.symbol)){if(tipTrigger&&tr.contains(tipTrigger))hideTradeTip();tr.remove();}
  rows.forEach((p,index)=>{
    let tr=existing.get(p.symbol);
    if(!tr){
      tr=row(['','','','']);tr.dataset.symbol=p.symbol;
      const trigger=document.createElement('button');trigger.type='button';trigger.className='trade-tip-trigger';
      trigger.addEventListener('mouseenter',()=>showTradeTip(trigger));
      trigger.addEventListener('mouseleave',()=>{if(document.activeElement!==trigger)hideTradeTip();});
      trigger.addEventListener('focus',()=>showTradeTip(trigger));
      trigger.addEventListener('blur',hideTradeTip);
      tr.children[0].append(trigger);
    }
    const trigger=tr.children[0].firstChild;
    trigger.textContent=p.symbol+(p.enabled?'':t(' · 已停用'));
    trigger.dataset.tradeTip=p.symbol+t('\n最新成交价（ticks）：')+(p.latestPriceTicks??'-')+t('\n最新成交量（lots）：')+(p.latestQuantityLots??'-')
      +t('\n买盘：')+format(p.bidOrderCount??0)+t(' 笔挂单 · ')+format(p.bidLevelCount??0)+t(' 个价格档位')
      +t('\n卖盘：')+format(p.askOrderCount??0)+t(' 笔挂单 · ')+format(p.askLevelCount??0)+t(' 个价格档位');
    trigger.setAttribute('aria-label',p.symbol+t('，查看最新成交和买卖盘挂单档位'));
    rateCell(tr.children[1],p.commands);tr.children[2].textContent=format(p.commands.total);tr.children[3].textContent=format(p.trades.total);
    // Reuse rows so the one-second refresh preserves hover and keyboard focus.
    if(body.children[index]!==tr)body.insertBefore(tr,body.children[index]||null);
    if(tipTrigger===trigger)showTradeTip(trigger);
  });
  if(!rows.length)body.append(row([t('暂无匹配交易对'),'-','-','-']));
}
document.addEventListener('keydown',event=>{if(event.key==='Escape')hideTradeTip();});
window.addEventListener('scroll',hideTradeTip,true);
window.addEventListener('resize',hideTradeTip);

function chart(){
 if(!latest)return;const canvas=$('chart'),dpr=window.devicePixelRatio||1,w=canvas.clientWidth,h=canvas.clientHeight;canvas.width=w*dpr;canvas.height=h*dpr;const ctx=canvas.getContext('2d');ctx.scale(dpr,dpr);
 const values=latest.history,max=Math.max(1,...values.flatMap(p=>[p.commands,p.trades])),left=48,right=16,top=18,bottom=30;
 ctx.font='11px Segoe UI';const style=getComputedStyle(document.documentElement);ctx.fillStyle=style.getPropertyValue('--muted').trim();ctx.lineWidth=1;
 for(let i=0;i<=4;i++){const y=top+(h-top-bottom)*i/4;ctx.strokeStyle=style.getPropertyValue('--border').trim();ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(w-right,y);ctx.stroke();ctx.fillText((max*(1-i/4)).toFixed(max<4?1:0),4,y+4);}
 for(const [key,color] of [['commands',style.getPropertyValue('--accent').trim()],['trades',style.getPropertyValue('--chart-trades').trim()]]){ctx.strokeStyle=color;ctx.lineWidth=2;ctx.beginPath();values.forEach((p,i)=>{const x=left+i/(values.length-1)*(w-left-right),y=top+(1-p[key]/max)*(h-top-bottom);i?ctx.lineTo(x,y):ctx.moveTo(x,y);});ctx.stroke();}
 ctx.fillText(t('60 秒前'),left,h-6);ctx.fillText(t('上一秒'),w-58,h-6);
 $('chart-summary').textContent=t('近 60 秒峰值：命令 ')+format(Math.max(...values.map(p=>p.commands)))+t(' / 秒 · 成交 ')+format(Math.max(...values.map(p=>p.trades)))+t(' 笔 / 秒');
}
function queueBacklog(queue){
  const available=queue?.available===true;
  $('rabbit-total').textContent=available?format(queue.total):'-';
  $('rabbit-ready').textContent=available?format(queue.ready):'-';
  $('rabbit-unacked').textContent=available?format(queue.unacked):'-';
  $('rabbit-queue-name').textContent=t('命令队列：')+(queue?.queueName??'-')+t(' · 包含下单和撤单');
  $('rabbit-queue-status').textContent=available?t('每 5 秒采样 · 最近读取 ')+new Date(queue.updatedAt).toLocaleTimeString(DashboardUI.language)+t(' · 未消费完成 = Ready + Unacked'):t('不可用：')+(DashboardUI.language==='en'?t('未获取到队列状态'):(queue?.message??t('未获取到队列状态')));
  $('rabbit-queue-status').classList.toggle('queue-unavailable',!available);
}
function render(data){queueBacklog(data.rabbitCommandQueue);latest=data;const c=data.counters;$('qps').textContent=format(c.commands.perSecond);$('tps').textContent=format(c.trades.perSecond);$('qps-max').textContent=format(c.commands.maxPerSecond);$('tps-max').textContent=format(c.trades.maxPerSecond);$('commands').textContent=format(c.commands.total);$('trades').textContent=format(c.trades.total);$('queue-rows').replaceChildren(...queueNames.map(([key,name])=>{const tr=row([t(name),'',format(c[key].total)]);rateCell(tr.children[1],c[key]);return tr;}));$('backlog').textContent=format(data.marketBacklog);$('uptime').textContent=Math.floor(data.uptimeSeconds/3600)+'h '+Math.floor(data.uptimeSeconds%3600/60)+'m '+data.uptimeSeconds%60+'s';pairs();chart();$('connection').textContent=t('● 实时 · ')+new Date((data.sampleSecond+1)*1000).toLocaleTimeString(DashboardUI.language);$('connection').classList.remove('error');}
async function poll(){try{await ready;const data=await request('/api/engine/metrics');disconnected=false;render(data);status(t('速率取上一完整秒，最大值为本次启动以来的最高完整秒。看板所有统计与最新成交仅保存在内存中，从本次启动开始，重启后清零。'));}catch(error){disconnected=true;queueBacklog({available:false,queueName:latest?.rabbitCommandQueue?.queueName,message:t('连接中断，队列数据已过期')});$('connection').textContent=t('连接中断 · 数据已过期');$('connection').classList.add('error');status(DashboardUI.language==='en'?'Unable to refresh metrics. Check the connection and try again.':error.message,true);}finally{setTimeout(poll,1000);}}
$('pair-filter').oninput=pairs;window.addEventListener('resize',chart);poll();

window.addEventListener('dashboard-preferences',()=>{if(latest)render(latest);if(disconnected){queueBacklog({available:false,queueName:latest?.rabbitCommandQueue?.queueName});$('connection').textContent=t('连接中断 · 数据已过期');$('connection').classList.add('error');status(t('连接中断 · 数据已过期'),true);}else if(latest)status(t('速率取上一完整秒，最大值为本次启动以来的最高完整秒。看板所有统计与最新成交仅保存在内存中，从本次启动开始，重启后清零。'));});
new ResizeObserver(()=>chart()).observe($('chart'));
