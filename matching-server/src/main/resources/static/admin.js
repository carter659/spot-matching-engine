'use strict';
window.Admin = (() => {
  let csrf='';
  const $=id=>document.getElementById(id);
  function status(message,error=false){const el=$('status');if(el){el.textContent=message;el.classList.toggle('error',error);}}
  async function request(path,body){
    const response=await fetch(path,{method:body===undefined?'GET':'POST',credentials:'same-origin',cache:'no-store',
      headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:body===undefined?undefined:typeof body==='string'?body:JSON.stringify(body),signal:AbortSignal.timeout(12000)});
    const raw=await response.text();let data;
    try{data=JSON.parse(raw.replace(/("(?:minOrderLots|maxOrderLots)"\s*:\s*)(\d+)/g,'$1"$2"'));}catch{throw Error('服务响应异常，请稍后重试');}
    if(response.status===401 && path!=='/api/auth/login'){location.replace('/login.html');throw Error('登录已过期');}
    if(!response.ok)throw Error(data.error||data.detail||'请求失败，请检查服务日志');
    if(data.csrfToken)csrf=data.csrfToken;
    return data;
  }
  const ready=request('/api/auth/session').then(session=>{
    const page=document.body.dataset.page;
    if(page!=='login'&&!session.authenticated){location.replace('/login.html');throw Error('请先登录');}
    if(page==='login'&&session.authenticated)location.replace('/');
    return session;
  });
  ready.catch(error=>status(error.message,true));
  if($('logout'))$('logout').onclick=async()=>{try{await ready;await request('/api/auth/logout',{});location.replace('/login.html');}catch(e){status(e.message,true);}};
  if($('login-form'))$('login-form').onsubmit=async event=>{
    event.preventDefault();$('login-submit').disabled=true;
    try{await ready;await request('/api/auth/login',{username:$('username').value.trim(),password:$('password').value});location.replace('/');}
    catch(error){status(error.message,true);}finally{$('login-submit').disabled=false;}
  };
  if($('password-form'))$('password-form').onsubmit=async event=>{
    event.preventDefault();if($('new-password').value!==$('confirm-password').value){status('两次输入的新密码不一致',true);return;}
    $('password-submit').disabled=true;
    try{await ready;await request('/api/auth/password',{oldPassword:$('old-password').value,newPassword:$('new-password').value});location.replace('/login.html?changed=1');}
    catch(error){status(error.message,true);}finally{$('password-submit').disabled=false;}
  };
  if(document.body.dataset.page==='login'&&new URLSearchParams(location.search).has('changed'))status('密码已修改，请使用新密码登录');
  return {$,request,ready,status};
})();
