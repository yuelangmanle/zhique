读写本项目沙盒目录内的文件；SAF 可选/存手机任意位置。

- `zq.file.read({path})` → `{content}`（限项目沙盒内；过大返回 rejected "too large"）。
- `zq.file.write({path, content?})` → 写入沙盒文件（默认空内容）。
- `zq.file.list({path?})` → 沙盒目录文件清单。
- `zq.file.pick({mime?})` → 系统选择器挑一个文件读入网页。
- `zq.file.save({name, mime?, content})` → 系统保存对话框，把内容存到手机存储。

路径都是项目内相对路径（越界会被拒绝）；沙盒外只有 pick/save 两条路。
用户在 SAF 对话框取消时 Promise 走 reject，请按「用户放弃」处理，不要报错轰炸。
