(function () {
  if (window.__ZHIQUE__) return; const Z = window.__ZHIQUE__ = { events: [], seq: 0 };
  function post(type, payload) {
    const e = { seq: ++Z.seq, t: Date.now(), type, ...payload };
    Z.events.push(e); if (Z.events.length > 500) Z.events.shift();
    if (window.ZhiqueNative && ZhiqueNative.onEvent) ZhiqueNative.onEvent(JSON.stringify(e));
  }
  ['log','info','warn','error','debug'].forEach(level => {
    const orig = console[level].bind(console);
    console[level] = function (...a) {
      post('console', { level, text: a.map(x => { try { return typeof x === 'object' ? JSON.stringify(x) : String(x); } catch { return String(x); } }).join(' ') });
      orig(...a);
    };
  });
  window.addEventListener('error', e => e.error ? post('js_error', { message: e.message, line: e.lineno, col: e.colno, stack: String(e.error.stack || '').slice(0, 2000) }) : post('resource_error', { url: e.target && e.target.src || '' }));
  window.addEventListener('unhandledrejection', e => post('promise_reject', { reason: String(e.reason) }));
  // fetch：入参可为 Request 对象——String(Request) 会打出无信息量的 "[object Request]"，
  // 归一为 URL 文本（读 url 不消费 body，Request 仍可原样透传）
  function inputUrl(input) {
    try { return (input && typeof input === 'object' && input.url) ? String(input.url) : String(input); }
    catch (e) { return String(input); }
  }
  const of = window.fetch;
  window.fetch = function (input, init) {
    const url = inputUrl(input);
    return of.call(this, input, init).then(r => { if (!r.ok) post('network_fail', { url: url, status: r.status }); return r; })
      .catch(err => { post('network_fail', { url: url, error: String(err) }); throw err; });
  };
  // XHR：监听器挂 open 且按实例去重（send 可对同一对象多次调用，逐次 addEventListener 会叠加重复上报）
  const oo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) {
    if (!this._zhiqueHooked) {
      this._zhiqueHooked = true;
      this.addEventListener('load', () => { if (this.status >= 400) post('network_fail', { url: this._zu, status: this.status, method: this._zm }); });
    }
    this._zu = u; this._zm = m;
    return oo.apply(this, arguments);
  };
  window.addEventListener('load', () => setTimeout(() => {
    const empty = !document.body || document.body.children.length === 0 ||
      (document.body.innerText || '').trim().length === 0 && document.querySelectorAll('canvas,img,svg,video').length === 0;
    if (empty) post('white_screen', { url: location.href });
    post('metrics', { domNodes: document.getElementsByTagName('*').length });
  }, 1200));
  // zq.* 桥（M5）：请求-响应走 Proxy → zq_call/__zqResolve；
  // 订阅流（sensor/location）走 zq.on(sub, cb) + native 侧 __zqEvent 推送
  const ZQ_TIMEOUTS = { 'mic.record': 120000, 'screen.capture': 120000 }; // 分级超时（Minor #5，与 ZqProtocol.timeoutMs 一致）
  window.zq = Z.api = new Proxy({}, { get: (_, ns) => ns === 'on'
    ? (sub, cb) => { Z.subs = Z.subs || {}; Z.subs[sub] = cb; return sub; }
    : new Proxy({}, { get: (__, fn) => (...args) =>
    new Promise((res, rej) => {
      const id = ++Z.seq;
      const timeout = ZQ_TIMEOUTS[ns + '.' + fn] || 30000;
      Z.pending = Z.pending || {}; Z.pending[id] = { res, rej };
      post('zq_call', { id, ns, fn, args: JSON.stringify(args || []), timeout: timeout });
      // 分级超时兜底：native 侧未命中/丢失时 promise 也必须 settle，防 pending 泄漏
      setTimeout(() => { if (Z.pending && Z.pending[id]) window.__zqResolve(id, false, 'timeout: ' + ns + '.' + fn); }, timeout);
    }) }) });
  window.__zqResolve = (id, ok, value) => { const p = Z.pending && Z.pending[id]; if (p) { delete Z.pending[id]; ok ? p.res(JSON.parse(value)) : p.rej(new Error(value)); } };
  window.__zqEvent = (sub, value) => { const s = Z.subs && Z.subs[sub]; if (s) { try { s(JSON.parse(value)); } catch (e) {} } };
  // W3C Notification 桥接（审查修复 #2，实现简者）：Android WebView 不暴露 Notification，
  // 缺失时用 zq.notification.requestPermission/post 兜出 window.Notification——
  // requestPermission 即 notification 能力矩阵条目（同一张授权卡 + 系统权限门）
  if (!window.Notification) {
    const ZqNotification = function (title, options) { zq.notification.post(Object.assign({ title: title }, options || {})); };
    ZqNotification.permission = 'default';
    ZqNotification.requestPermission = function (cb) {
      return zq.notification.requestPermission().then(function (r) {
        ZqNotification.permission = (r && r.permission) || 'denied';
        if (typeof cb === 'function') cb(ZqNotification.permission);
        return ZqNotification.permission;
      });
    };
    ZqNotification.maxActions = 0;
    window.Notification = ZqNotification;
  }
})();
