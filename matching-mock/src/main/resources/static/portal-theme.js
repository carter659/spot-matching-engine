(() => {
  const root = document.documentElement;
  const controls = document.createElement('div');
  controls.className = 'theme-controls';
  const toggle = document.createElement('button');
  toggle.type = 'button'; toggle.disabled = true;
  let retryTimer;
  let readTimer;
  let changed = false;
  const status = document.createElement('span');
  status.className = 'theme-status'; status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  controls.append(toggle, status);
  document.querySelector('header').append(controls);

  function apply(theme) {
    root.dataset.theme = theme;
    toggle.textContent = theme === 'dark' ? '切换浅色主题' : '切换深色主题';
    toggle.setAttribute('aria-label', toggle.textContent);
  }
  function message(text, error = false) {
    status.textContent = text; status.classList.toggle('error', error);
  }
  async function preferences(body) {
    const response = await fetch('/mock/preferences', {
      method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined, cache: 'no-store', signal: AbortSignal.timeout(10000)
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(response.status === 404
      ? '偏好接口未启用，请用 dev 环境重启新版 mock'
      : data.error || '偏好服务不可用');
    return data;
  }
  async function save() {
    clearTimeout(retryTimer);
    toggle.disabled = true; message('正在保存…');
    try {
      await preferences({ key: 'theme', value: root.dataset.theme });
      message('');
    } catch (error) {
      message('尚未保存，将自动重试。' + error.message, true);
      retryTimer = setTimeout(save, 15000);
    } finally { toggle.disabled = false; }
  }
  toggle.onclick = () => {
    changed = true; clearTimeout(readTimer);
    apply(root.dataset.theme === 'dark' ? 'light' : 'dark'); save();
  };
  window.addEventListener('pagehide', () => { clearTimeout(retryTimer); clearTimeout(readTimer); });
  apply(matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  async function restore() {
    try {
      const saved = await preferences();
      if (!changed) {
        if (['light', 'dark'].includes(saved.theme)) apply(saved.theme);
        message('');
      }
    } catch (error) {
      if (!changed) {
        message('暂未读取到主题，将自动重试。' + error.message, true);
        readTimer = setTimeout(restore, 15000);
      }
    } finally { if (!changed) toggle.disabled = false; }
  }
  restore();
})();
