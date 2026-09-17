import {createBatch, sendBatch} from './batch-model.mjs';
const $ = id => document.getElementById(id);
const key = 'matching-mock.batch.v1';
let batch = null, pairs = [], running = false, stop = false, storageReady = true;
function message(text) { $('status').textContent = text; }
function render() {
  const unfinished = batch && batch.next < batch.count;
  $('fields').disabled = running || !!unfinished || !pairs.length || !storageReady;
  $('pause').disabled = !running || stop;
  $('resume').disabled = running || !unfinished || !storageReady;
  $('new').disabled = running || !batch || !!unfinished;
  $('progress').max = batch?.count || 1000; $('progress').value = batch?.next || 0;
  $('summary').textContent = batch ? `${batch.body.symbol.replace('_',' / ')} · ${batch.body.side === 'BUY' ? '买入' : '卖出'} · 单价 ${batch.body.price} · 每单 ${batch.body.quantity} · ${batch.body.timeInForce} · 已确认 ${batch.next} / ${batch.count} 笔${batch.lastOrderId ? ' · 最近订单 ' + batch.lastOrderId : ''}` : '尚未创建批次';
}
function save(value) { sessionStorage.setItem(key, JSON.stringify(value)); render(); }
async function api(url, body) {
  const response = await fetch(url, {method:body ? 'POST':'GET', headers:{'Content-Type':'application/json'},
    body:body ? JSON.stringify(body):undefined, signal:AbortSignal.timeout(15000), cache:'no-store'});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '请求失败');
  return data;
}
async function run() {
  if (running || !batch) return;
  running = true; stop = false; render(); message('正在逐笔投递，等待 RabbitMQ 确认…');
  try {
    await sendBatch(batch, body => api('/mock/trading/orders', body), save, () => stop);
    message(batch.next === batch.count ? '全部投递已确认；成交结果请查看委托记录。' : '已暂停，可继续原批次。');
  } catch(error) { message('投递已暂停：' + error.message + '。修复后继续原批次，避免重复创建订单。'); }
  finally { running = false; render(); }
}
function selectPair() {
  const pair = pairs.find(p => p.symbol === $('symbol').value);
  if (!pair) return;
  $('price').value = pair.examplePrice;
  $('quantity').value = pair.quantityScale === 0 ? '1' : '0.1';
  $('precision').textContent = `价格最多 ${pair.priceScale} 位小数，数量最多 ${pair.quantityScale} 位小数；数量单位 ${pair.baseAsset}。`;
}
$('symbol').onchange = selectPair;
$('batch-form').onsubmit = event => {
  event.preventDefault(); if (running || (batch && batch.next < batch.count)) return;
  try {
    const pair = pairs.find(p => p.symbol === $('symbol').value);
    batch = createBatch({...pair, side:$('side').value, policy:$('policy').value, price:$('price').value.trim(), quantity:$('quantity').value.trim()}, Number($('count').value), crypto.randomUUID());
    save(batch); run();
  } catch(error) { message(error.message); }
};
$('pause').onclick = () => { stop = true; render(); message('正在等待当前请求结束，随后暂停。'); };
$('resume').onclick = run;
$('new').onclick = () => { if (!running && batch?.next === batch?.count) { sessionStorage.removeItem(key); batch = null; render(); message('可创建新批次。'); } };
try {
  batch = JSON.parse(sessionStorage.getItem(key) || 'null');
  if (batch && (!batch.id || !batch.body || !Number.isInteger(batch.count) || batch.count < 1 || batch.count > 1000 || !Number.isInteger(batch.next) || batch.next < 0 || batch.next > batch.count)) throw new Error('批次记录无效');
  sessionStorage.setItem(key, JSON.stringify(batch));
} catch(error) { storageReady = false; message('无法读取或保存批次进度：' + error.message); }
try {
  pairs = await api('/mock/parameters/pairs');
  $('symbol').replaceChildren(...pairs.map(pair => { const option = document.createElement('option'); option.value = pair.symbol; option.textContent = `${pair.baseAsset} / ${pair.quoteAsset}`; return option; }));
  selectPair();
  if (storageReady) message(batch ? '已恢复批次进度，点击继续可重试未确认订单。' : pairs.length ? '参数已就绪。' : '请先在参数配置中添加交易对。');
} catch(error) { message('交易对读取失败：' + error.message); }
render();
