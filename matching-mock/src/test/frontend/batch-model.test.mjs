import test from 'node:test';
import assert from 'node:assert/strict';
import {createBatch, sendBatch} from '../../main/resources/static/batch-model.mjs';
const form = {symbol:'BTC_USDT',priceScale:2,quantityScale:4,side:'BUY',policy:'GTC',price:'100.00',quantity:'0.0010'};
test('1000 identical limit orders have distinct stable command IDs', async () => {
  const batch = createBatch(form, 1000, 'batch');
  const commands = [];
  await sendBatch(batch, async body => {
    commands.push(body);
    return {status:'BROKER_CONFIRMED',orderId:String(commands.length)};
  }, () => {});
  assert.equal(batch.next, 1000);
  assert.equal(new Set(commands.map(c => c.commandId)).size, 1000);
  assert.ok(commands.every(c => c.price === '100.00' && c.quantity === '0.0010' && c.symbol === 'BTC_USDT' && c.orderType === 'LIMIT'));
});
test('uncertain response resumes with original command ID after reload', async () => {
  let snapshot;
  const batch = createBatch(form, 3, 'retry');
  const seen = [];
  await assert.rejects(sendBatch(batch, async body => {
    seen.push(body.commandId);
    if (seen.length === 2) throw new Error('timeout');
    return {status:'BROKER_CONFIRMED',orderId:'1'};
  }, value => {snapshot = JSON.stringify(value);}));
  assert.equal(batch.next, 1);
  const restored = JSON.parse(snapshot);
  await sendBatch(restored, async body => {seen.push(body.commandId); return {status:'BROKER_CONFIRMED',orderId:'2'};}, () => {});
  assert.deepEqual(seen, ['retry-0','retry-1','retry-1','retry-2']);
});
test('pause and validation prevent unintended sends', async () => {
  for (const count of [0,1001,1.5,NaN]) assert.throws(() => createBatch(form,count,'invalid'));
  assert.throws(() => createBatch({...form,quantity:'0.00001'},1,'precision'));
  const batch = createBatch(form,2,'pause');
  let sent = 0;
  await sendBatch(batch, async () => {sent++; return {status:'BROKER_CONFIRMED',orderId:'1'};}, () => {}, () => sent === 1);
  assert.equal(sent,1); assert.equal(batch.next,1);
  await assert.rejects(sendBatch(batch, async () => {sent++;}, () => {throw new Error('storage full');}));
  assert.equal(sent,1);
});
