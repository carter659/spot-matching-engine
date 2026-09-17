function sampleQuantity(scale) { return scale === 0 ? '1' : '0.3'.padEnd(scale + 2, '0'); }
import { toUnits, fromUnits, estimate, orderPayload, statusLabel, isCurrent, paginateOrders, escapeHtml as esc } from './trading-model.mjs';

const root = document.getElementById('spot-prototype');
const $ = selector => root.querySelector(selector);
const $$ = selector => root.querySelectorAll(selector);
const pendingKey = 'matching-mock.pending-request.v1';
const state = { pair: { symbol: 'BTC_USDT', baseAsset: 'BTC', quoteAsset: 'USDT', priceScale: 2, quantityScale: 4, examplePrice: '65000.00' }, pairs: [], side: 'BUY', type: 'LIMIT', policy: 'GTC', list: 'open', data: null,
  connected: false, busy: false, pending: null, samples: [], tradeIds: new Set(), sequence: null, marketSeen: 0 };
let refreshPromise;
const orderPages = {open:1, all:1};
let orderPageSize = 20;
let pollTimer;
let preferences = {};
let preferenceWrites = Promise.resolve();
const preferenceStatus = document.createElement('span');
preferenceStatus.setAttribute('role', 'status');
preferenceStatus.style.fontSize = '12px';
$('#theme-toggle').after(preferenceStatus);
function savePreference(key, value) {
  preferenceStatus.textContent = '偏好保存中…';
  preferenceWrites = preferenceWrites.then(async () => {
    try {
      await request('/mock/preferences', { key, value });
      preferences[key] = value;
      preferenceStatus.textContent = key === 'theme' ? '' : '偏好已保存';
    } catch (error) { preferenceStatus.textContent = '偏好未保存：' + error.message + '；请重新选择重试'; }
  });
}
async function loadPreferences() {
  $('#theme-toggle').disabled = true;
  try {
    preferences = await request('/mock/preferences');
    if (['light', 'dark'].includes(preferences.theme)) document.documentElement.dataset.theme = preferences.theme;
    if (['open', 'all'].includes(preferences.orderList)) state.list = preferences.orderList;
    renderOrders();
  } catch (error) { preferenceStatus.textContent = '偏好读取失败：' + error.message; }
  finally { $('#theme-toggle').disabled = false; }
}

async function loadPairs() {
  try {
    state.pairs = await request('/mock/parameters/pairs');
    $('#symbol-select').replaceChildren(...state.pairs.map(pair => {
      const option = document.createElement('option'); option.value = pair.symbol;
      option.textContent = `${pair.baseAsset} / ${pair.quoteAsset}`; return option;
    }));
    const symbol = state.pending?.body?.symbol || state.pending?.symbol || preferences.symbol;
    selectPair(state.pairs.find(pair => pair.symbol === symbol) || state.pairs[0] || state.pair);
  } catch(error) { notice('交易对目录读取失败：' + error.message, true); }
}

$('#symbol-select').addEventListener('change', async () => {
  const pair = state.pairs.find(pair => pair.symbol === $('#symbol-select').value);
  if (!pair || state.busy || state.pending) { $('#symbol-select').value = state.pair.symbol; return; }
  selectPair(pair);
  savePreference('symbol', pair.symbol);
  notice(`已切换 ${pair.baseAsset} / ${pair.quoteAsset}；请确认 server 已启用该交易对。示例价格仅用于测试。`);
  renderChart(); renderMarket(); renderTape(); renderOrders(); renderForm();
  await refresh();
  if (!state.data) await refresh();
});

function selectPair(pair) {
  orderPages.open = 1; orderPages.all = 1;
  state.pair = pair; state.data = null; state.connected = false;
  $('#symbol-select').value = pair.symbol;
  state.samples = []; state.tradeIds.clear(); state.sequence = null;
  $('#price').value = pair.examplePrice;
  $('#quantity').value = sampleQuantity(pair.quantityScale);
  $('#pair-description').textContent = `${pair.baseAsset} · 现货测试`;
  $$('[data-base-asset]').forEach(element => element.textContent = pair.baseAsset);
  $('#example-order').textContent = `投递示例卖单：${pair.examplePrice} × ${sampleQuantity(pair.quantityScale)} ${pair.baseAsset}`;
}

function storageGet(key) { try { return sessionStorage.getItem(key); } catch { return null; } }
function remember(packet) {
  if (packet && !packet.symbol) packet.symbol = state.pair.symbol;
  state.pending = packet;
  try { if (packet) sessionStorage.setItem(pendingKey, JSON.stringify(packet)); else sessionStorage.removeItem(pendingKey); }
  catch { /* The server outbox remains authoritative when browser storage is unavailable. */ }
}
try {
  const saved = JSON.parse(storageGet(pendingKey));
  if (saved && /^\/mock\/trading\/orders(?:\/\d+\/cancel)?$/.test(saved.path) && saved.body?.commandId) state.pending = saved;
} catch { /* Ignore invalid local drafts. */ }

function formatDecimal(value, scale = state.pair.priceScale) {
  if (value == null) return '—';
  const [whole, fraction = ''] = String(value).split('.');
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (scale ? '.' + fraction.padEnd(scale, '0').slice(0, scale) : '');
}
function form() { return { symbol: state.pair.symbol, priceScale: state.pair.priceScale, quantityScale: state.pair.quantityScale, side: state.side, type: state.type, policy: state.policy,
  price: $('#price').value, quantity: $('#quantity').value }; }
function notice(message, error = false) { $('#notice').textContent = message; $('#notice').classList.toggle('error', error); }
function putHtml(element, html) { if (element.innerHTML !== html) element.innerHTML = html; }

async function request(path, body) {
  const response = await fetch(path, { method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? { Accept: 'application/json' } : { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store',
    signal: AbortSignal.timeout(body === undefined ? 7000 : 30000) });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(response.status === 404 ? '交易接口未启用，请使用 messaging 配置启动 mock 服务'
      : data.error || data.detail || `服务返回 HTTP ${response.status}`);
    error.permanent = [400, 404, 405, 422].includes(response.status);
    throw error;
  }
  return data;
}

function renderForm() {
  $$('[data-side]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.side === state.side)));
  $$('[data-type]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.type === state.type)));
  $$('[data-policy]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.policy === state.policy)));
  const market = state.type === 'MARKET';
  $('#limit-price').hidden = market;
  $('#price').disabled = market;
  $('#market-price').hidden = !market;
  $('#tif').hidden = market;
  $('#policy-help').textContent = market ? '立即与现有对手盘成交，未成交部分取消；不指定成交价格。' : {
    GTC: '持续有效，未成交部分保留在订单簿，直到成交或撤销。',
    IOC: '立即成交可成交部分，未成交部分立即取消。',
    FOK: '必须立即全部成交；数量不足时整笔取消，不产生部分成交。'
  }[state.policy];
  $('#rest-label').textContent = !market && state.policy === 'GTC' ? '预计剩余挂单' : '预计未成交取消';
  let valid = true;
  try {
    const preview = estimate(form(), state.data?.market);
    $('#estimated-fill').textContent = preview ? fromUnits(preview.filled, state.pair.quantityScale) + ' ' + state.pair.baseAsset : '等待盘口';
    $('#estimated-rest').textContent = preview ? fromUnits(preview.remaining, state.pair.quantityScale) + ' ' + state.pair.baseAsset : '—';
    // Quote precision is the sum of price and quantity precision.
    $('#estimated-cost').textContent = preview ? formatDecimal(fromUnits(preview.cost, state.pair.priceScale + state.pair.quantityScale), state.pair.priceScale + state.pair.quantityScale) + ' USDT' : '—';
    $('#validation').hidden = true;
  } catch (error) {
    valid = false;
    $('#validation').textContent = error.message;
    $('#validation').hidden = false;
    for (const id of ['estimated-fill', 'estimated-rest', 'estimated-cost']) $('#' + id).textContent = '—';
  }
  $('#submit-order').classList.toggle('sell', state.side === 'SELL');
  $('#submit-order').textContent = state.busy ? '正在等待投递确认…' : '投递' + (state.side === 'BUY' ? '买单' : '卖单') + ' · ' + state.pair.baseAsset;
  $('#submit-order').disabled = !valid || !state.connected || !state.pairs.length || state.busy || !!state.pending;
  $('#symbol-select').disabled = state.busy || !!state.pending;
  $('#example-order').disabled = !state.connected || !state.pairs.length || state.busy || !!state.pending;
  $('#retry-box').hidden = !state.pending;
  $('#retry-request').disabled = state.busy || !state.connected;
  $('#retry-help').textContent = state.pending ? '保留原命令 ' + state.pending.body.commandId + '，重试不会创建新订单。' : '';
  const pendingCount = state.data?.pendingCommandCount || 0;
  $('#outbox-box').hidden = !pendingCount;
  $('#outbox-count').textContent = `服务端有 ${pendingCount} 条命令待确认`;
  $('#retry-outbox').disabled = state.busy || !state.connected;
}

function renderBook(levels, container, isAsk) {
  if (!levels?.length) { putHtml(container, '<div class="sp-empty">暂无' + (isAsk ? '卖盘' : '买盘') + '</div>'); return; }
  let sum = 0n;
  const capacity = Math.max(0, Math.floor(container.clientHeight / 30));
  const rows = levels.slice(0, capacity).map(level => { sum += toUnits(level.quantity, state.pair.quantityScale); return { ...level, sum }; });
  const total = sum;
  if (isAsk) rows.reverse();
  putHtml(container, rows.map(row => `<button type="button" class="sp-book-row ${isAsk ? 'ask' : 'bid'}"
    data-price="${esc(row.price)}" style="--depth:${Number(row.sum * 100n / total)}%" aria-label="限价 ${esc(row.price)} USDT">
    <span class="${isAsk ? 'sp-down' : 'sp-up'}">${esc(formatDecimal(row.price))}</span><span>${esc(row.quantity)}</span><span>${fromUnits(row.sum, state.pair.quantityScale)}</span></button>`).join(''));
}

function renderChart() {
  const values = state.samples.map(sample => Number(sample.price));
  $('#chart-count').textContent = `${values.length} 笔成交`;
  $('#chart-empty').hidden = values.length > 1;
  $('#chart-empty').textContent = values.length ? '已收到 1 笔成交，等待下一笔' : '等待真实成交数据';
  if (!values.length) { $('#price-line').setAttribute('points', ''); $('#chart-high').textContent = '—'; $('#chart-low').textContent = '—'; return; }
  const width = 400, height = 260;
  const low = Math.min(...values), high = Math.max(...values), padding = Math.max((high - low) * .1, 10 ** -state.pair.priceScale);
  $('#price-chart').setAttribute('viewBox', `0 0 ${width} ${height}`);
  $('#chart-high').textContent = formatDecimal(high.toFixed(state.pair.priceScale));
  $('#chart-low').textContent = formatDecimal(low.toFixed(state.pair.priceScale));
  $('#price-line').setAttribute('points', values.map((value, index) =>
    `${10 + index / Math.max(1, values.length - 1) * (width - 20)},${height - 10 - (value - low + padding) / (high - low + padding * 2) * (height - 20)}`).join(' '));
}

function renderMarket() {
  const market = state.data?.market;
  if (!market) {
    state.sequence = null;
    for (const id of ['best-bid', 'best-ask', 'last-price', 'book-price', 'market-sequence']) $('#' + id).textContent = '—';
    putHtml($('#asks'), '<div class="sp-empty">等待卖盘</div>');
    putHtml($('#bids'), '<div class="sp-empty">等待买盘</div>');
    $('#book-help').textContent = '尚未收到 Kafka 行情；预估价格暂不可用。';
    return;
  }
  if (state.sequence !== market.sequence) {
    state.sequence = market.sequence;
    state.marketSeen = Date.now();
    for (const trade of [...(state.data.recentTrades || market.trades)].reverse()) {
      if (!state.tradeIds.has(trade.tradeId)) {
        state.tradeIds.add(trade.tradeId);
        state.samples.push(trade);
      }
    }
    state.samples = state.samples.slice(-120);
    if (state.tradeIds.size > 500) state.tradeIds = new Set([...state.tradeIds].slice(-500));
  }
  renderBook(market.asks, $('#asks'), true);
  renderBook(market.bids, $('#bids'), false);
  $('#best-bid').textContent = formatDecimal(market.bids[0]?.price);
  $('#best-ask').textContent = formatDecimal(market.asks[0]?.price);
  $('#market-sequence').textContent = market.sequence;
  const last = market.trades.at(-1);
  $('#last-price').textContent = formatDecimal(last?.price);
  $('#book-price').textContent = formatDecimal(last?.price);
  $('#book-help').textContent = Date.now() - state.marketSeen > 15000
    ? '行情序号暂未变化；盘口预估仅供参考，以撮合结果为准。' : '点击价格填入限价单；订单簿来自 Kafka 推送。';
  renderChart();
}

function renderTape() {
  const trades = state.data?.recentTrades || [];
  $('#trade-total').textContent = `${trades.length} 笔`;
  $('#empty-trades').hidden = trades.length > 0;
  let direction = '';
  let previousPrice = null;
  const rows = [...trades].reverse().map(trade => {
    const price = toUnits(trade.price, state.data.priceScale);
    if (previousPrice !== null && price !== previousPrice) direction = price > previousPrice ? 'sp-up' : 'sp-down';
    previousPrice = price;
    const time = trade.receivedAt ? new Date(trade.receivedAt).toLocaleTimeString('zh-CN', { hour12: false }) : '—';
    return `<tr title="成交编号：${esc(trade.tradeId)}">
      <td title="接收时间：${esc(trade.receivedAt || '')}">${esc(time)}</td>
      <td class="${direction}">${esc(formatDecimal(trade.price))}</td>
      <td>${esc(trade.quantity)}</td></tr>`;
  }).reverse();
  putHtml($('#trade-tape'), rows.join(''));
}

function renderOrders() {
  const orders = state.data?.orders || [];
  const current = orders.filter(isCurrent);
  const cancellable = current.filter(order => order.canCancel && !order.cancelPending && order.symbol === state.pair.symbol);
  $('#cancel-all').disabled = state.busy || !!state.pending || !state.connected || cancellable.length === 0;
  $('#cancel-all').textContent = `批量撤单${cancellable.length ? ' (' + cancellable.length + ')' : ''}`;
  $('#open-count').textContent = current.length;
  $$('[data-list]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.list === state.list)));
  const selected = state.list === 'open' ? current : orders;
  const pagination = paginateOrders(selected, orderPages[state.list], orderPageSize);
  orderPages[state.list] = pagination.page;
  $('#order-page-info').textContent = `共 ${pagination.total} 条 · 第 ${pagination.page} / ${pagination.pages} 页`;
  $('#order-prev').disabled = pagination.page <= 1;
  $('#order-next').disabled = pagination.page >= pagination.pages;
  $('#empty-orders').hidden = selected.length > 0;
  $('#empty-orders').textContent = state.list === 'open' ? '暂无当前委托' : '暂无委托记录';
  putHtml($('#orders'), pagination.rows.map(order => `<tr>
    <td><span class="${order.side === 'BUY' ? 'sp-up' : 'sp-down'}">${order.side === 'BUY' ? '买入' : '卖出'} ${esc(order.symbol.replace('_', '/'))}</span><small class="sp-order-id" title="${esc(order.orderId)}">${esc(order.orderId)}</small></td>
    <td>${order.orderType === 'MARKET' ? '市价单' : '限价单 / ' + esc(order.timeInForce)}</td>
    <td>${order.price == null ? '市价' : esc(formatDecimal(order.price))}</td>
    <td>${esc(order.quantity)} / ${esc(order.filledQuantity)}</td>
    <td>${esc(order.remainingQuantity)} / ${esc(order.cancelledQuantity)}</td>
    <td>${esc(statusLabel(order))}${order.reason ? '<small class="sp-order-reason">' + esc(order.reason) + '</small>' : ''}</td>
    <td>${order.canCancel ? `<button type="button" data-cancel="${esc(order.orderId)}" ${state.busy || state.pending || !state.connected ? 'disabled' : ''}>撤单</button>` : '—'}</td>
  </tr>`).join(''));
}

async function refresh() {
  if (refreshPromise) return refreshPromise;
  const selectedSymbol = state.pair.symbol;
  refreshPromise = (async () => {
    try {
      const data = await request('/mock/trading?symbol=' + encodeURIComponent(selectedSymbol));
      if (state.pair.symbol !== selectedSymbol) return;
      if (!Array.isArray(data.orders)) throw new Error('交易状态响应格式不正确');
      state.data = data;
      state.connected = true;
      $('#connection').textContent = '● mock 服务已连接';
      $('#connection').classList.remove('failed');
      $('#synced').textContent = '同步于 ' + new Date(data.observedAt).toLocaleTimeString('zh-CN');
      renderMarket();
      renderTape();
    } catch (error) {
      if (state.pair.symbol !== selectedSymbol) return;
      state.connected = false;
      $('#connection').textContent = '● 连接失败：' + error.message;
      $('#connection').classList.add('failed');
      $('#synced').textContent = state.data ? '显示上次成功同步的数据' : '尚未同步';
    } finally { renderForm(); renderOrders(); refreshPromise = null; }
  })();
  return refreshPromise;
}

async function deliver(packet) {
  if (state.busy) return;
  remember(packet);
  state.busy = true;
  renderForm(); renderOrders();
  notice('正在投递' + packet.label + '，等待 RabbitMQ 确认…');
  try {
    const receipt = await request(packet.path, packet.body);
    remember(null);
    notice(`${packet.label}已投递确认，订单 ${receipt.orderId}；请在委托列表查看撮合结果。`);
  } catch (error) {
    if (error.permanent) remember(null);
    notice(error.message + (state.pending ? ' 原请求已保留，请点击“重试原委托”。' : ''), true);
  } finally {
    state.busy = false;
    await refresh();
    renderForm(); renderOrders();
  }
}

$('#cancel-all').addEventListener('click', async () => {
  if (state.busy || state.pending || !state.connected) return;
  const symbol = state.pair.symbol;
  const orders = (state.data?.orders || []).filter(order => order.symbol === symbol && isCurrent(order) && order.canCancel && !order.cancelPending);
  if (!orders.length) return;
  state.busy = true; renderForm(); renderOrders();
  let sent = 0;
  try {
    for (const order of orders) {
      // Refreshes may have observed a fill while earlier cancellations were sent.
      const latest = state.data?.orders.find(item => item.orderId === order.orderId);
      if (!latest?.canCancel || latest.cancelPending || !isCurrent(latest)) continue;
      const packet = {path:`/mock/trading/orders/${encodeURIComponent(order.orderId)}/cancel`,
        body:{commandId:crypto.randomUUID()}, symbol, label:'批量撤单命令'};
      remember(packet);
      notice(`正在批量撤单：已投递 ${sent} / ${orders.length} 笔…`);
      await request(packet.path, packet.body);
      remember(null); sent++;
    }
    notice(`批量撤单已投递 ${sent} 笔，等待撮合引擎回执。`);
  } catch (error) {
    if (error.permanent) remember(null);
    notice(`批量撤单已暂停，已投递 ${sent} 笔：${error.message}` +
      (state.pending ? ' 请点击“重试原委托”确认当前请求，再继续批量撤单。' : ' 其余订单可重新批量撤单。'), true);
  } finally {
    state.busy = false; await refresh(); renderForm(); renderOrders();
  }
});

$('#order-prev').onclick = () => { orderPages[state.list]--; renderOrders(); };
$('#order-next').onclick = () => { orderPages[state.list]++; renderOrders(); };
$('#order-page-size').onchange = event => {
  orderPageSize = Number(event.target.value); orderPages.open = 1; orderPages.all = 1; renderOrders();
};
$('#order-form').addEventListener('submit', event => {
  event.preventDefault();
  if (!state.connected || state.busy || state.pending) return;
  try { deliver({ path: '/mock/trading/orders', body: orderPayload(form(), crypto.randomUUID()), label: '测试订单' }); }
  catch (error) { notice(error.message, true); }
});
root.addEventListener('click', event => {
  const button = event.target.closest('button');
  if (!button || button.disabled) return;
  if (button.dataset.side) { state.side = button.dataset.side; renderForm(); }
  if (button.dataset.type) { state.type = button.dataset.type; renderForm(); }
  if (button.dataset.policy) { state.policy = button.dataset.policy; renderForm(); }
  if (button.dataset.list) { state.list = button.dataset.list; renderOrders(); savePreference('orderList', state.list); }
  if (button.dataset.price) { state.type = 'LIMIT'; $('#price').value = button.dataset.price; renderForm(); }
  if (button.dataset.cancel && !state.pending && !state.busy && state.connected) {
    deliver({ path: `/mock/trading/orders/${encodeURIComponent(button.dataset.cancel)}/cancel`,
      body: { commandId: crypto.randomUUID() }, label: '撤单命令' });
  }
});
for (const id of ['price', 'quantity']) $('#' + id).addEventListener('input', renderForm);
$('#example-order').addEventListener('click', () => {
  if (state.busy || state.pending || !state.connected) return;
  deliver({ path: '/mock/trading/orders', body: orderPayload({ side: 'SELL', type: 'LIMIT', policy: 'GTC',
    symbol: state.pair.symbol, priceScale: state.pair.priceScale, quantityScale: state.pair.quantityScale, price: state.pair.examplePrice, quantity: sampleQuantity(state.pair.quantityScale) }, crypto.randomUUID()), label: '示例卖单' });
});
$('#retry-request').addEventListener('click', () => { if (state.pending) deliver(state.pending); });
$('#retry-outbox').addEventListener('click', async () => {
  if (state.busy) return;
  state.busy = true; renderForm(); renderOrders();
  try { await request('/mock/retry', {}); notice('服务端待确认命令已重试，请查看委托结果。'); }
  catch (error) { notice(error.message, true); }
  finally { state.busy = false; await refresh(); }
});
$('#refresh').addEventListener('click', refresh);
const fullscreenButton = document.createElement('button');
fullscreenButton.type = 'button'; fullscreenButton.className = 'sp-quiet';
fullscreenButton.textContent = '全屏'; fullscreenButton.setAttribute('aria-pressed', 'false');
$('#theme-toggle').before(fullscreenButton);
fullscreenButton.hidden = !document.fullscreenEnabled;
fullscreenButton.onclick = async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.documentElement.requestFullscreen();
  } catch { notice('无法进入全屏，请使用浏览器全屏快捷键 F11。', true); }
};
document.addEventListener('fullscreenchange', () => {
  fullscreenButton.textContent = document.fullscreenElement ? '退出全屏' : '全屏';
  fullscreenButton.setAttribute('aria-pressed', String(!!document.fullscreenElement));
});
$('#theme-toggle').addEventListener('click', () => {
  const dark = document.documentElement.dataset.theme
    ? document.documentElement.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.dataset.theme = dark ? 'light' : 'dark';
  savePreference('theme', document.documentElement.dataset.theme);
});
async function poll() { if (!document.hidden) await refresh(); pollTimer = setTimeout(poll, 1500); }
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
window.addEventListener('pagehide', () => clearTimeout(pollTimer));
const bookResizeObserver = new ResizeObserver(() => {
  const market = state.data?.market;
  if (market) {
    renderBook(market.asks, $('#asks'), true);
    renderBook(market.bids, $('#bids'), false);
  }
});
bookResizeObserver.observe($('#asks'));
bookResizeObserver.observe($('#bids'));
renderForm(); renderOrders(); loadPreferences().then(loadPairs).finally(poll);
