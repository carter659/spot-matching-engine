import {orderPayload} from './trading-model.mjs';

export function createBatch(form, count, id) {
  if (!Number.isInteger(count) || count < 1 || count > 1000) throw new Error('下单个数必须为 1–1000 的整数');
  const body = orderPayload({...form, type:'LIMIT'}, id + '-0');
  return {id, body, count, next:0, lastOrderId:null};
}

// Persist before sending and after confirmation. A retry always keeps the same command ID.
export async function sendBatch(batch, send, save, shouldStop = () => false) {
  while (batch.next < batch.count && !shouldStop()) {
    save(batch);
    const receipt = await send({...batch.body, commandId:batch.id + '-' + batch.next});
    if (receipt.status !== 'BROKER_CONFIRMED' || !receipt.orderId) throw new Error('未收到有效投递确认，请重试原批次');
    batch.next++;
    batch.lastOrderId = receipt.orderId;
    save(batch);
  }
}
