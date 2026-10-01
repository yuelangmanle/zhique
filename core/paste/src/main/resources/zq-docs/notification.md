以系统通知的形式展示网页发来的消息。

- `zq.notification.post({title, body?})` → 发一条系统通知。
- `zq.notification.requestPermission({})` → 主动发起通知授权（可提前在关键操作前请求）。

授权语义：Android 13+ 首次发通知前需用户授权；拒绝时 Promise 走 reject。
通知被拒时请降级为页面内横幅/toast 提示，不要崩溃。
