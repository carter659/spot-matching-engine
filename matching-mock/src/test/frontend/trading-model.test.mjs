import test from 'node:test';
import assert from 'node:assert/strict';
import { toUnits, fromUnits, estimate, orderPayload, statusLabel, isCurrent, escapeHtml } from '../../main/resources/static/trading-model.mjs';

const market = { asks: [{ price: '65010.00', quantity: '0.1200' }, { price: '65020.00', quantity: '0.1800' }, { price: '65030.00', quantity: '0.2000' }], bids: [] };
const form = { side: 'BUY', type: 'LIMIT', policy: 'GTC', price: '65020.00', quantity: '0.3500' };
test('selected pair and eight-decimal prices are preserved in payload and preview', () => {
  const shib = {...form, symbol:'SHIB_USDT', priceScale:8, quantityScale:4, price:'0.00002001', quantity:'10.0000'};
  const payload = orderPayload(shib, 'shib-one');
  assert.equal(payload.symbol, 'SHIB_USDT');
  assert.equal(payload.price, '0.00002001');
  const preview = estimate(shib, {asks:[{price:'0.00002000',quantity:'10.0000'}], bids:[]});
  assert.equal(fromUnits(preview.cost, 12), '0.000200000000');
  assert.throws(() => orderPayload({...shib, price:'0.000020001'}, 'invalid'));
});
test('decimal conversion is exact above JavaScript integer precision', () => {
  assert.equal(toUnits('922337203685477.5807', 4), 9223372036854775807n);
  assert.equal(fromUnits(9223372036854775807n, 4), '922337203685477.5807');
  assert.throws(() => toUnits('922337203685477.5808', 4));
  assert.throws(() => toUnits('0.00001', 4));
  assert.throws(() => toUnits('1e3', 4));
  assert.throws(() => toUnits('-1', 4));
  assert.throws(() => toUnits('0', 4));
});
test('GTC and IOC preview preserve price and partial quantity', () => {
  const result = estimate(form, market);
  assert.equal(result.filled, 3000n);
  assert.equal(result.remaining, 500n);
  assert.equal(fromUnits(result.cost, 6), '19504.800000');
  assert.deepEqual(estimate({ ...form, policy: 'IOC' }, market), result);
});
test('FOK insufficient depth displays zero execution', () => {
  assert.deepEqual(estimate({ ...form, policy: 'FOK' }, market), { filled: 0n, remaining: 3500n, cost: 0n });
});
test('market ignores price, hides unsupported policy in request, and uses decimal strings', () => {
  const input = { ...form, type: 'MARKET', policy: 'FOK', price: '' };
  const result = estimate(input, market);
  assert.equal(result.filled, 3500n);
  assert.equal(result.remaining, 0n);
  const body = orderPayload(input, 'same-command');
  assert.equal(body.price, null);
  assert.equal(body.quantity, '0.3500');
  assert.equal(body.timeInForce, 'IOC');
  assert.equal(body.commandId, 'same-command');
});
test('no market snapshot means unknown estimate rather than simulated fill', () => {
  assert.equal(estimate(form, null), null);
});
test('broker confirmation is not a fill, and expired partial fills remain distinct', () => {
  assert.equal(statusLabel({ status: 'BROKER_CONFIRMED', filledQuantity: '0.0000' }), '已投递 · 等待撮合');
  assert.equal(statusLabel({ status: 'EXPIRED', filledQuantity: '0.1000' }), '部分成交 · 余量取消');
  assert.equal(isCurrent({ status: 'BROKER_CONFIRMED' }), true);
  assert.equal(isCurrent({ status: 'EXPIRED' }), false);
});
test('server text is escaped before rendering', () => {
  assert.equal(escapeHtml('<script>"&'), '&lt;script&gt;&quot;&amp;');
});
test('current orders exclude all terminal states and retain pending cancellations until receipt', () => {
  for (const status of ['FILLED', 'CANCELLED', 'EXPIRED', 'REJECTED'])
    assert.equal(isCurrent({status}), false);
  assert.equal(isCurrent({status:'OPEN', cancelPending:true}), true);
  assert.equal(isCurrent({status:'PARTIALLY_FILLED'}), true);
});
test('configured zero and eight digit precision are preserved in orders and fills', () => {
  const input = {...form, priceScale:0, quantityScale:8, price:'123', quantity:'0.00000001'};
  const body = orderPayload(input, 'precision');
  assert.equal(body.price, '123');
  assert.equal(body.quantity, '0.00000001');
  assert.equal(statusLabel({status:'EXPIRED', filledQuantity:body.quantity}), '部分成交 · 余量取消');
  assert.equal(orderPayload({...input, quantityScale:0, quantity:'1'}, 'integer').quantity, '1');
});
