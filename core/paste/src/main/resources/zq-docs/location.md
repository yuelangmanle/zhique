读取手机位置（单次或周期流）。

- `zq.location.get({})` → 单次定位，返回经纬度等信息。
- `zq.location.watch({intervalMs?})` → 周期位置流；通过事件回调持续推送。
- `zq.location.unwatch({})` → 停止位置流（页面隐藏/销毁时系统也会自动停）。

授权语义：首次调用弹「定位」授权卡，用户拒绝时 Promise 走 reject。
请务必处理拒绝分支（如切换到手动选点），并在不用时 unwatch。
