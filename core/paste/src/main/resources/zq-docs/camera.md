用手机相机拍照，照片自动存入项目目录。

- `zq.camera.capture({dataUrl?})` → 拍照；默认返回文件信息，传 `dataUrl: true` 同时返回图片 data URL。
- `zq.camera.startPreview({})` → 打开取景浮层（仅预览，不拍摄；`zq.camera.capture` 随时可拍）。
- `zq.camera.stopPreview({})` → 关闭取景浮层。

授权语义：首次调用会弹「相机」授权卡，用户同意才执行；拒绝时 Promise 走 reject，
请捕获后提示用户改用相册/手动输入，不要崩溃。
