'use strict';
const {$,request,ready,status}=Admin;
let busy=false;
function lock(value){busy=value;document.querySelectorAll('#settings button,#settings input,#settings textarea,#snapshot').forEach(el=>el.disabled=value);}
async function load(){lock(true);try{await ready;const config=await request('/api/engine/configuration');$('symbols').value=config.parameters.symbols.sort().join('\n');$('min-lots').value=config.parameters.minOrderLots;$('max-lots').value=config.parameters.maxOrderLots;$('summary').textContent='当前已启用 '+config.parameters.symbols.length+' 个交易对';status('已读取当前配置');}catch(error){status(error.message,true);}finally{lock(false);}}
$('reload').onclick=load;
$('settings').onsubmit=async event=>{
  event.preventDefault();if(busy)return;
  const symbols=[...new Set($('symbols').value.toUpperCase().replace(/\s*\/\s*/g,'_').replace(/-/g,'_').split(/[,，\s;；]+/).filter(Boolean))];
  const min=$('min-lots').value.trim(),max=$('max-lots').value.trim();
  if(!symbols.length||symbols.length>500||symbols.some(s=>s.length>32||!/^[A-Z0-9]+_[A-Z0-9]+$/.test(s))){status('请输入有效的交易对，例如 BTC_USDT，每行一个，最多 500 个',true);return;}
  if(!/^[1-9]\d*$/.test(min)||!/^[1-9]\d*$/.test(max)||BigInt(min)>BigInt(max)||BigInt(max)>9223372036854775807n){status('请输入有效的正整数数量范围',true);return;}
  lock(true);try{
    await request('/api/engine/configuration','{"symbols":'+JSON.stringify(symbols)+',"minOrderLots":'+min+',"maxOrderLots":'+max+'}');
    $('symbols').value=symbols.join('\n');$('summary').textContent='当前已启用 '+symbols.length+' 个交易对';status('配置已保存并生效');
  }catch(error){status(error.message+'；如超时，请重新读取确认实际配置。',true);}finally{lock(false);}
};
$('snapshot').onclick=async()=>{if(busy)return;lock(true);status('正在创建快照…');try{const result=await request('/api/engine/snapshot',{});status('快照已保存：'+result.path+'（'+result.commandCount+' 条命令）');}catch(error){status(error.message+'；超时后请检查服务端快照文件。',true);}finally{lock(false);}};
load();
