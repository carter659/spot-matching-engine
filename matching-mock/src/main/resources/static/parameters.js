const status = document.getElementById('status');
const table = document.getElementById('pairs');
let busy = false;
function message(text, error = false) { status.textContent = text; status.classList.toggle('error', error); }
async function api(path = '', body, method = body ? 'POST' : 'GET') {
  const response = await fetch('/mock/parameters/pairs' + path, {method,
    headers:{'Content-Type':'application/json'}, body:body ? JSON.stringify(body) : undefined,
    cache:'no-store', signal:AbortSignal.timeout(10000)});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '配置服务不可用');
  return data;
}
function button(text, action) { const item = document.createElement('button'); item.type = 'button'; item.textContent = text; item.onclick = action; return item; }
function precisionInput(value, label) {
  const input = document.createElement('input');
  input.type = 'number'; input.min = '0'; input.max = '8'; input.step = '1'; input.required = true;
  input.value = value; input.setAttribute('aria-label', label); input.style.width = '90px';
  return input;
}
async function mutate(action, success) {
  if (busy) return;
  busy = true;
  document.querySelectorAll('button,input').forEach(item => item.disabled = true);
  try { await action(); await load(); message(success); }
  catch (error) { message(error.message + '；如请求超时，请重新读取确认。', true); }
  finally { busy = false; document.querySelectorAll('button,input').forEach(item => item.disabled = false); }
}
function rowFor(pair) {
  const row = document.createElement('tr');
  for (const value of [`${pair.baseAsset} / ${pair.quoteAsset}`, pair.priceScale, pair.quantityScale]) {
    const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
  }
  const priceCell = document.createElement('td'), actions = document.createElement('td');
  actions.className = 'pair-actions';
  const show = () => {
    row.children[1].textContent = pair.priceScale;
    row.children[2].textContent = pair.quantityScale;
    priceCell.textContent = pair.examplePrice;
    actions.replaceChildren(button('修改', edit), button('删除', () => {
      if (confirm(`删除 ${pair.baseAsset} / USDT？历史订单保留，但该交易对将从可选列表移除。`))
        mutate(() => api('/' + encodeURIComponent(pair.symbol), undefined, 'DELETE'), '交易对已删除');
    }));
  };
  const edit = () => {
    const priceScale = precisionInput(pair.priceScale, pair.symbol + ' 价格小数位');
    const quantityScale = precisionInput(pair.quantityScale, pair.symbol + ' 数量小数位');
    row.children[1].replaceChildren(priceScale); row.children[2].replaceChildren(quantityScale);
    const price = document.createElement('input'); price.value = pair.examplePrice; price.inputMode = 'decimal';
    price.setAttribute('aria-label', pair.symbol + ' 示例价格'); priceCell.replaceChildren(price);
    actions.replaceChildren(button('保存', () => {
      if (!priceScale.reportValidity() || !quantityScale.reportValidity()) return;
      mutate(() => api('/' + encodeURIComponent(pair.symbol),
        {examplePrice:price.value.trim(), priceScale:Number(priceScale.value), quantityScale:Number(quantityScale.value)}), '修改已保存');
    }), button('取消', show));
    price.focus();
  };
  show(); row.append(priceCell, actions); return row;
}
async function load() {
  try {
    const pairs = await api(); table.replaceChildren(...pairs.map(rowFor));
    message(`已从 MySQL 读取 ${pairs.length} 个交易对`);
    return true;
  } catch (error) { message('读取失败：' + error.message, true); throw error; }
}
const add = button('添加交易对', () => { form.hidden = false; base.focus(); });
document.getElementById('reload').after(add);
const form = document.createElement('form'); form.className = 'pair-add'; form.hidden = true;
function field(label, value, type = 'text') {
  const wrapper = document.createElement('label'); wrapper.textContent = label;
  const input = document.createElement('input'); input.value = value; input.type = type; input.required = true;
  wrapper.append(input); form.append(wrapper); return input;
}
const base = field('基础币（报价币 USDT）', ''); base.maxLength = 16; base.placeholder = '例如 SUI';
const scale = field('价格小数位（0–8）', '4', 'number'); scale.min = '0'; scale.max = '8'; scale.step = '1';
const quantityScale = field('数量小数位（0–8）', '4', 'number'); quantityScale.min = '0'; quantityScale.max = '8'; quantityScale.step = '1';
const example = field('示例价格', '1.0000'); example.inputMode = 'decimal';
const hint = document.createElement('p'); hint.textContent = '价格和数量小数位支持 0–8。重新添加已删除的币种时需使用原精度。'; form.append(hint);
const create = document.createElement('button'); create.type = 'submit'; create.textContent = '保存';
form.append(create, button('取消', () => { form.hidden = true; }));
status.before(form);
form.onsubmit = event => {
  event.preventDefault();
  const asset = base.value.trim().toUpperCase();
  mutate(async () => {
    await api('', {symbol:asset + '_USDT',baseAsset:asset,quoteAsset:'USDT',priceScale:Number(scale.value),quantityScale:Number(quantityScale.value),examplePrice:example.value.trim()});
    form.hidden = true; base.value = '';
  }, '交易对已添加；请在 server 启用该交易对后测试撮合');
};
document.getElementById('reload').onclick = () => { if (!busy) load().catch(() => {}); };
load().catch(() => {});
