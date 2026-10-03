# 更新日志

本项目每次迭代交付都递增版本号（`app/build.gradle.kts` 的 versionCode +1 / versionName 语义化）
并在本文件记录变更。versionCode 单调递增是 APK 覆盖安装的前提（决策 29）。

## 0.2.1（versionCode 3）· 2026-10-02

**真机第三轮修复**

- 修复：引导页「从 Apilot 接入」仍闪退（Can only use lower 16 bits）——引导页漏改
  的旧式 launcher 补齐稳定 key 注册；引导页与设置页的 launch 全部 runCatching
- 新增：**Apilot 一键网关接入**（v2.5.0+ GRANT_GATEWAY 协议）——引导页与 AI 服务商页
  各加「⚡ 一键网关接入」，授权后网关地址/模型直接落 Provider，零手抄
- 修复：权限检测中心传感器参数（acceleration → accel）与定位首测无 lastKnown 的
  降级路径（改 watch 等 8 秒实时定位）
- 修复：检测失败详情原样透传（不再吞成"被拒绝"），排查有据可依
- 新增检测项：振动（W3C vibrate）、本地存储读写、WebGL 上下文——共 13 项

## 0.2.0（versionCode 2）· 2026-10-02

**真机反馈修复（第二轮 + PM 级审计）**

- 修复：导出 APK 在部分 OEM 安装器"解析包失败"——签名升级 v1+v2+v3 三重（小米/HyperOS
  文件管理器路径对纯 v2/v3 包校验更严）；**根因修复：冲突消解包名含连字符**
  （Android 包名禁 '-'，INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME）+ 历史非法包名弃用
- 修复：编辑器改码后重跑仍是旧页——本地资源响应补 `Cache-Control: no-store`
- 修复：**项目"退出再进数据消失"**——损坏的 project.json 自动重建（从 `<title>` 取回
  项目名并修复写回），不再是静默跳过；含回归测试
- 新增：**示例库**——预设示例扩充到 6 个（权限检测中心 / 粒子星云 / 极简番茄钟 /
  流光画板 / 星空 / 相机测试），空态网格 + 有项目时横向条，同名自动加序号
- 新增：**全权限检测中心示例**——10 项能力逐个真实调用检测，结果 localStorage 持久化
- 新增：编辑器小白工具条——「粘贴替换全文」（剪贴板 → 自动清洗 → 全文替换）、
  「清空」、「复制全文」
- 修复：权限中心「允许」现在**同步弹系统权限确认框**（此前只改矩阵状态，App 从未
  真正向系统申请）；系统被拒时矩阵回写 DENIED（反映真实可用性）
- 修复：Apilot 接入/同步的 launch 全包 runCatching（包名解析失败不再闪退）
- 修复：全局 insets——所有二级界面与状态栏重叠（根节点统一 systemBarsPadding）
- 修复：WebGPU 能力检测假阴性（about:blank 非安全上下文，navigator.gpu 恒 undefined）
  → 项目页加载后重测覆盖初判
- 修复：模型列表拉取 URL 去重（baseUrl 带 /v1 时不再拼出 /v1/v1/models）
- 修复：三级回退链（运行器→编辑器/Agent/对话→返回逐级还原）；返回键=网页回退
  （跳过 about:blank 初始页）
- 修复：网页标准行为补齐——`<input type=file>` 文件选择、外链转系统浏览器、
  `window.open` 同 WebView 承载、网页下载转系统
- 修复：App 主题补 `Theme.Zhique`（NoActionBar），消灭系统 ActionBar 黑带
- 修复：Apilot 包可见性（manifest `<queries>`，"明明装了却显示未安装"）
- 修复：Apilot 授权 ActivityResult requestCode 越界闪退（稳定 key 显式注册）
- 修复：FAB 新建项目自动打开；项目卡长按菜单加「编辑代码」；项目对话注入项目
  上下文（index.html 摘要）
- 设计：首页视觉升级（品牌字标 / 粘贴代码主按钮 / 渐变缩略图卡片 / 相对时间 /
  发光剪贴板横幅 / 精致空态）；设置行动样式统一

## 0.1.0（versionCode 1）· 2026-10-01

首个完整版本：M0–M10 全部里程碑交付。

- 智能粘贴管道（分类/清洗/组装/AI 兜底）、三模式运行器、轻编辑器
- AI Provider 三协议（OpenAI 兼容 / Anthropic / Gemini）+ 能力声明 + 输出上限 /
  防截断自动续写 / 思考流折叠
- Agent 自主调试（工具循环 + 三层记忆 + 上下文压缩 + 三道安全带）
- 权限桥全能力（zq.* + W3C 双路、能力×项目矩阵、OS 运行时申请）
- 端上打包导出（模板 APK 组装 + apksig 签名 + 统一密钥库 + 覆盖安装保证，决策 29）
- GitHub 发布（双模式 + 持久化状态机断点续跑）与自更新
- Apilot V2 桥接与提示词桥
- Aurora Glass 设计语言 + 非线性 spring 动效全落地
