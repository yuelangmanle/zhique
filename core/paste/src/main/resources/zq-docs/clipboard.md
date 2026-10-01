读取或写入系统剪贴板。

- `zq.clipboard.get({})` → `{text}`（当前剪贴板文本；可能为空）。
- `zq.clipboard.set({text})` → 写入剪贴板。

授权语义：读剪贴板需要「剪贴板」授权卡确认；拒绝时 Promise 走 reject。
写剪贴板成功后请给用户一个「已复制」的反馈；get 结果为空也要正常处理。
