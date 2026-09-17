export const LONG_MAX = 9223372036854775807n;
export function paginateOrders(orders, page, size) {
  const pages = Math.max(1, Math.ceil(orders.length / size));
  const current = Math.min(pages, Math.max(1, page));
  return {page:current, pages, total:orders.length, rows:orders.slice((current - 1) * size, current * size)};
}

export function toUnits(value, scale, positive = true) {
  const text = String(value).trim();
  if (!/^\d+(?:\.\d+)?$/.test(text)) throw new Error('请输入有效的正数');
  const [whole, fraction = ''] = text.split('.');
  if (fraction.length > scale) throw new Error(`最多支持 ${scale} 位小数`);
  const units = BigInt(whole) * 10n ** BigInt(scale) + BigInt(fraction.padEnd(scale, '0') || '0');
  if ((positive && units <= 0n) || units > LONG_MAX) throw new Error('数值必须大于 0 且不能超出允许范围');
  return units;
}

export function fromUnits(value, scale) {
  const units = BigInt(value), factor = 10n ** BigInt(scale);
  return `${units / factor}${scale ? '.' + String(units % factor).padStart(scale, '0') : ''}`;
}

export function estimate({ side, type, policy, price, quantity, priceScale = 2, quantityScale = 4 }, market) {
  const amount = toUnits(quantity, quantityScale);
  const limit = type === 'LIMIT' ? toUnits(price, priceScale) : 0n;
  if (!market) return null;
  let left = amount, cost = 0n;
  for (const level of side === 'BUY' ? market.asks : market.bids) {
    const p = toUnits(level.price, priceScale), q = toUnits(level.quantity, quantityScale);
    if (type === 'LIMIT' && (side === 'BUY' ? p > limit : p < limit)) break;
    const take = left < q ? left : q;
    left -= take;
    cost += take * p;
    if (left === 0n) break;
  }
  if (type === 'LIMIT' && policy === 'FOK' && left > 0n) return { filled: 0n, remaining: amount, cost: 0n };
  return { filled: amount - left, remaining: left, cost };
}

export function orderPayload(form, commandId) {
  const quantity = fromUnits(toUnits(form.quantity, form.quantityScale ?? 4), form.quantityScale ?? 4);
  const price = form.type === 'LIMIT' ? fromUnits(toUnits(form.price, form.priceScale ?? 2), form.priceScale ?? 2) : null;
  return { commandId, symbol: form.symbol ?? 'BTC_USDT', side: form.side, orderType: form.type,
    timeInForce: form.type === 'MARKET' ? 'IOC' : form.policy, price, quantity };
}

export function statusLabel(order) {
  if (order.cancelPending) return '撤单处理中';
  const filled = Number(order.filledQuantity) > 0;
  return ({ PENDING_PUBLISH: '待投递确认', BROKER_CONFIRMED: '已投递 · 等待撮合',
    OPEN: '挂单中', PARTIALLY_FILLED: '部分成交 · 挂单中', FILLED: '全部成交',
    CANCELLED: filled ? '部分成交 · 已撤单' : '已撤单',
    EXPIRED: filled ? '部分成交 · 余量取消' : '未成交 · 已取消', REJECTED: '委托被拒绝' })[order.status] || order.status;
}

export function isCurrent(order) {
  return ['PENDING_PUBLISH', 'BROKER_CONFIRMED', 'OPEN', 'PARTIALLY_FILLED'].includes(order.status);
}

export function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}
