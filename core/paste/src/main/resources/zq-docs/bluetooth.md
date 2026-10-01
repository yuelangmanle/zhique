扫描附近的 BLE（低功耗蓝牙）设备。

- `zq.bluetooth.scan({durationMs?})` → 开始扫描，发现的设备经事件回调推送。
- `zq.bluetooth.stopScan({})` → 停止扫描。

授权语义：首次调用弹「蓝牙」授权卡（扫描/连接），拒绝时 Promise 走 reject。
请捕获拒绝并提示到系统设置开启；扫描是耗时操作，界面要有进行中状态与停止按钮。
