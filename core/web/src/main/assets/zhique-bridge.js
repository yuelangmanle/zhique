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
  const of = window.fetch;
  window.fetch = function (input, init) {
    return of.call(this, input, init).then(r => { if (!r.ok) post('network_fail', { url: String(input), status: r.status }); return r; })
      .catch(err => { post('network_fail', { url: String(input), error: String(err) }); throw err; });
  };
  const oo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) { this._zu = u; this._zm = m; return oo.apply(this, arguments); };
  const os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    this.addEventListener('load', () => { if (this.status >= 400) post('network_fail', { url: this._zu, status: this.status, method: this._zm }); });
    return os.apply(this, arguments);
  };
  window.addEventListener('load', () => setTimeout(() => {
    const empty = !document.body || document.body.children.length === 0 ||
      (document.body.innerText || '').trim().length === 0 && document.querySelectorAll('canvas,img,svg,video').length === 0;
    if (empty) post('white_screen', { url: location.href });
    post('metrics', { domNodes: document.getElementsByTagName('*').length });
  }, 1200));
  // zq.* 骨架（M5 充实实现；此处注册机制先行）
  window.zq = Z.api = new Proxy({}, { get: (_, ns) => new Proxy({}, { get: (__, fn) => (...args) =>
    new Promise((res, rej) => {
      const id = ++Z.seq;
      Z.pending = Z.pending || {}; Z.pending[id] = { res, rej };
      post('zq_call', { id, ns, fn, args: JSON.stringify(args || []) });
      // 30s 超时兜底：native 侧未命中/丢失时 promise 也必须 settle，防 pending 泄漏
      setTimeout(() => { if (Z.pending && Z.pending[id]) window.__zqResolve(id, false, 'timeout: ' + ns + '.' + fn); }, 30000);
    }) }) });
  window.__zqResolve = (id, ok, value) => { const p = Z.pending && Z.pending[id]; if (p) { delete Z.pending[id]; ok ? p.res(JSON.parse(value)) : p.rej(new Error(value)); } };
})();
