import test from 'node:test';
import assert from 'node:assert/strict';
import {paginateOrders, isCurrent} from '../../main/resources/static/trading-model.mjs';
test('2000 orders paginate without loss or overlap', () => {
  const orders = Array.from({length:2000}, (_, orderId) => ({orderId}));
  const rows = [];
  for (let page = 1; page <= 100; page++) {
    const result = paginateOrders(orders,page,20);
    assert.equal(result.pages,100); assert.equal(result.rows.length,20);
    rows.push(...result.rows);
  }
  assert.deepEqual(rows, orders);
});
test('terminal orders are filtered before pagination and shrinking pages clamp', () => {
  const orders = Array.from({length:41}, (_, i) => ({status:i < 21 ? 'OPEN' : 'FILLED'}));
  const current = orders.filter(isCurrent);
  assert.equal(paginateOrders(current,2,20).rows.length,1);
  assert.equal(paginateOrders(current.slice(0,20),2,20).page,1);
  assert.equal(paginateOrders(orders,3,20).rows.length,1);
  assert.deepEqual(paginateOrders([],5,20), {page:1,pages:1,total:0,rows:[]});
});
