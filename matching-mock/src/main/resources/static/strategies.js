const $ = id => document.getElementById(id);
let busy = false, loading = false, editingId = null;
function status(text, error = false) { $('status').textContent = text; $('status').classList.toggle('error', error); }
async function request(path = '', method = 'GET', body) {
  const response = await fetch('/mock/strategies' + path, {method, headers:{'Content-Type':'application/json'},
    body:body ? JSON.stringify(body):undefined, cache:'no-store', signal:AbortSignal.timeout(30000)});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '策略服务不可用');
  return data;
}
function button(text, handler, disabled = false) {
  const item = document.createElement('button'); item.type = 'button'; item.textContent = text;
  item.disabled = disabled || busy; item.onclick = handler; return item;
}
const labels = {PAUSED:'已暂停',STARTING:'运行中 · 载入',SEEDING:'运行中 · 挂单',WATCHING:'运行中 · 监测',REPLACING:'运行中 · 换单',CANCELLING:'撤单中',DELETING:'删除中'};
function row(strategy) {
  const tr = document.createElement('tr');
  for (const value of [strategy.symbol.replace('_',' / ') + ' · 每侧 ' + strategy.depth + ' 档 · 倍率 ' + strategy.quantityMultiplier,labels[strategy.phase] || strategy.phase,
    strategy.sourceUpdateId || '—',strategy.checkedAt ? new Date(strategy.checkedAt).toLocaleString('zh-CN') : '—',strategy.message]) {
    const td = document.createElement('td'); td.textContent = value; tr.append(td);
  }
  tr.children[1].className = 'strategy-state'; tr.children[4].className = 'strategy-message';
  const actions = document.createElement('td'); actions.className = 'strategy-actions';
  const path = '/' + encodeURIComponent(strategy.id);
  actions.append(strategy.phase === 'PAUSED' ? button('启动', () => mutate(path + '/start','POST','启动已提交')) :
    button('暂停', () => mutate(path + '/pause','POST','暂停已提交，等待撤单回执'), ['CANCELLING','DELETING'].includes(strategy.phase)),
    button('删除', () => mutate(path,'DELETE','删除已提交，挂单处理完毕后移除'), strategy.phase === 'DELETING'));
  actions.append(button('修改', () => edit(strategy), strategy.phase === 'DELETING'), button('记录', () => history(strategy.id)));
  tr.append(actions); return tr;
}
async function load() {
  if (loading) return;
  loading = true;
  try {
    const strategies = await request(); $('strategies').replaceChildren(...strategies.map(row));
    $('empty').hidden = strategies.length > 0;
  } catch(error) { status('读取失败：' + error.message, true); }
  finally { loading = false; }
}
async function mutate(path, method, message, body) {
  if (busy) return;
  busy = true; document.querySelectorAll('main button').forEach(b => b.disabled = true);
  try { await request(path, method, body); status(message); if (body) $('strategy-form').hidden = true; }
  catch(error) { status(error.message + '；超时后请重新读取确认状态。',true); }
  finally { busy = false; document.querySelectorAll('main button').forEach(b => b.disabled = false); await load(); }
}
function edit(strategy) { editingId = strategy?.id || null; $('symbol').disabled = !!strategy; if(strategy) $('symbol').value=strategy.symbol; $('depth').value=strategy?.depth || 5; $('multiplier').value=strategy?.quantityMultiplier || 1; $('submit-strategy').textContent=strategy?'保存':'添加'; $('strategy-form').hidden=false; }
$('add').onclick = () => edit(null);
$('cancel-add').onclick = () => { $('strategy-form').hidden = true; };
$('reload').onclick = load;
$('strategy-form').onsubmit = event => {
  event.preventDefault(); mutate(editingId ? '/' + encodeURIComponent(editingId) : '', editingId?'PUT':'POST', editingId?'参数已保存，运行中的策略自动应用':'策略已添加，点击启动开始运行', {symbol:$('symbol').value,depth:Number($('depth').value),quantityMultiplier:$('multiplier').value});
};
try {
  const response = await fetch('/mock/parameters/pairs', {cache:'no-store',signal:AbortSignal.timeout(10000)});
  if (!response.ok) throw new Error('无法读取交易对配置');
  const pairs = await response.json();
  $('symbol').replaceChildren(...pairs.map(pair => { const option = document.createElement('option'); option.value = pair.symbol; option.textContent = pair.baseAsset + ' / ' + pair.quoteAsset; return option; }));
  $('add').disabled = !pairs.length;
  status('策略配置保存在 MySQL；新增后点击启动。');
} catch(error) { status(error.message,true); }
await load();
const timer = setInterval(() => { if (!document.hidden && !busy) load(); }, 1000);
window.addEventListener('pagehide', () => clearInterval(timer));

async function history(id) { try { const items=await request('/'+encodeURIComponent(id)+'/snapshots'); $('history').hidden=false; $('snapshots').replaceChildren(...items.map(s=>{const o=document.createElement('option');o.value=s.id;o.textContent=new Date(s.createdAt).toLocaleString()+' · '+s.sourceUpdateId+' · '+s.depth+' 档';return o;})); $('snapshots').onchange=loadOrders; await loadOrders(); } catch(e){status(e.message,true);} }
let historyRequest = 0;
function historyMessage(message) {
  const row=document.createElement('tr'), cell=document.createElement('td');
  cell.colSpan=5; cell.className='empty'; cell.textContent=message; row.append(cell);
  $('history-orders').replaceChildren(row); $('history-count').textContent='';
}
async function loadOrders() {
  const version=++historyRequest, snapshotId=$('snapshots').value;
  if(!snapshotId){historyMessage('暂无历史盘口');return;}
  historyMessage('正在读取关联委托…');
  try {
    const orders=await request('/snapshots/'+encodeURIComponent(snapshotId)+'/orders');
    if(version!==historyRequest)return;
    if(!orders.length){historyMessage('该盘口暂无关联委托');return;}
    const states={OPEN:'挂单中',PARTIALLY_FILLED:'部分成交',FILLED:'全部成交',CANCELLED:'已撤单',REJECTED:'已拒绝',EXPIRED:'已失效',PLANNED:'待投递',NOT_SUBMITTED:'未投递',PENDING_PUBLISH:'投递中',BROKER_CONFIRMED:'已投递，待撮合确认'};
    $('history-orders').replaceChildren(...orders.map(order=>{
      const row=document.createElement('tr');
      const values=[order.orderId||'尚未投递',order.side==='BUY'?'买入':order.side==='SELL'?'卖出':order.side,order.price,order.quantity,states[order.status]||order.status];
      values.forEach((value,index)=>{const cell=document.createElement('td');cell.textContent=value;if(index===2||index===3)cell.className='numeric';if(index===1)cell.className=order.side==='BUY'?'buy':'sell';row.append(cell);});
      return row;
    }));
    $('history-count').textContent='共 '+orders.length+' 笔委托';
  } catch(error) {if(version===historyRequest){historyMessage('读取失败：'+error.message);}}
}
