<div align="center">

# 织雀 Zhique

**安卓手机上的 HTML 运行器 + AI 编程助手——粘贴即跑、AI 自写自调、权限全桥接、一键打包发布。**

[![CI](https://github.com/zhique-app/zhique/actions/workflows/ci.yml/badge.svg)](https://github.com/zhique-app/zhique/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/zhique-app/zhique?include_prereleases)](https://github.com/zhique-app/zhique/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF.svg)](https://kotlinlang.org)
[![minSdk](https://img.shields.io/badge/minSdk-31-3DDC84.svg)](https://developer.android.com)

<!-- 截图占位（v0.1.0 发布时补充：首页 / 三模式运行器 / Agent 修复 / 导出向导 各一） -->
<!-- ![首页](docs/screenshots/home.png) ![运行器](docs/screenshots/runner.png) ![Agent](docs/screenshots/agent.png) ![导出](docs/screenshots/export.png) -->

</div>

---

## 为什么做这个

AI 助手写 HTML 程序已经又快又好，但安卓用户拿到代码后依然寸步难行：

- **打开难**——手机浏览器加载本地 HTML 兼容性差、没有调试手段，WebGL/WebGPU 页面基本跑不起来；
- **改不动**——页面报错只能干瞪眼，手机上没有 console，更没有能看报错改代码的工具；
- **用不了**——网页调不了相机、文件、传感器这些系统能力，AI 写的程序永远只是「能看」；
- **留不下**——好的 HTML 程序没法变成手机桌面上一个真正的 App。

织雀把这四件事一次做完：**从任何 AI 复制代码 → 打开织雀 2 次点击运行 → 报错交给内置 Agent 自动修复 → 满意后一键打包成独立 APK → 甚至直接发布到 GitHub。**

## 核心特性

### 运行与编辑
- **F1 智能粘贴**：一次粘贴全搞定——完整 HTML、说明文字混代码块、HTML/CSS/JS 分开的多个代码块、裸片段，自动识别、清洗、命名、入库；规则拿不准时内置 AI 兜底解析。
- **F2 三模式运行器**：系统 WebView 全量渲染（CSS/JS/WebGL/WebGPU/Canvas），底部抽屉 / 上下分屏 / 悬浮球浮层随时切换；调试抽屉含报错/Console/网络，可选 [eruda](https://github.com/liriliri/eruda) 高级面板。
- **F3 轻编辑器**：语法高亮 + 选中即问 AI，大改动交给 Agent。

### AI 能力
- **F4 多协议 Provider**：OpenAI 兼容 / Anthropic / Gemini 三协议原生适配，自带 Key 即用；防截断自动续写、思考过程独立折叠展示。
- **F5 Agent 自主调试**：自己跑页面、读报错、改代码、重跑验证、截图自查，循环到修好为止；轮数/token 预算可控，每步快照可回滚，全程审计。
- **多模型协作**：不同模型干不同的活（主力规划、便宜模型跑循环、视觉模型看截图）。

### 系统能力
- **F6 权限桥**：网页可用相机/麦克风/文件/定位/传感器/蓝牙/通知/剪贴板/分享/截屏——标准 W3C API 零改动兼容，织雀 `zq.*` API 覆盖更多；**App 集中持权，网页按需申请，用户逐项授权随时吊销**。
- **提示词桥**：把 `zq.*` API 规范一键生成提示词发给外部 AI，让它一次写对兼容代码。

### 变成真正的 App
- **F7 打包导出**：端上组装 + 官方 apksig 签名，HTML 项目一键变独立 APK；签名密钥一次生成永久复用、可备份到电脑，机制上保证每次更新可覆盖安装；launcher 图标按项目色自动匹配；每项目独立包名可共存。
- **F8/F11 GitHub 发布**：手动模式只负责点确认（日常 2 步），或让 Agent 出计划代办；发布是持久化状态机，中断可续跑；项目与仓库绑定后一键推送更新。
- **F9 [Apilot](https://github.com/yuelangmanle/Apilot) 桥接**：与 Apilot（API 管理工具）官方 V2 协议互操作——API 配置双向授权流转，密钥最小暴露（读走 `PICK_API_CONFIG` scopes 最小集、写走 `IMPORT_API_CONFIGS` 的 `apiProfiles` 语义化负载、URI 即焚、Key 不落 URL/日志/剪贴板）。
- **自更新**：GitHub Release 双通道（stable/beta），下载 APK 按 Release 资产的 `sha256` digest 校验，同签名约束兜底覆盖安装。

## 体验

- **Aurora Glass** 设计语言：晨光/深空双域、低饱和极光光晕、系统级实时毛玻璃（RenderEffect）、全线非线性弹簧动效。
- **触点纪律**：粘贴到运行 ≤2 触、报错到 Agent 1 触、出包 3 触。

## 构建指南

要求：JDK 17、Android SDK（compileSdk 36）、网络（首次构建拉取 eruda 资产）。

```bash
git clone https://github.com/zhique-app/zhique.git
cd zhique

# 全量测试（含真实模板壳夹具的导出端到端）
./gradlew test

# 构建织雀本体（debug / release）
./gradlew :app:assembleDebug :app:assembleRelease
```

模块结构（11 个 Gradle 模块，核心逻辑全部纯 JVM 可测）：

| 模块 | 职责 |
|---|---|
| `:app` | UI、导航、依赖组装 |
| `:core:project` | 项目仓库、project.json、版本快照 |
| `:core:paste` | 智能粘贴管道 + 提示词桥 + zq 文档资产 |
| `:core:web` | WebView 运行时、织雀桥 JS、调试采集器 |
| `:core:ai` | Provider 协议适配、角色路由、SSE |
| `:core:agent` | Agent 工具循环、预算、三层记忆 |
| `:core:permission` | 权限注册表、zq.* 桥、授权 UI 协议 |
| `:core:export` | 模板 APK 组装、apksig 签名、密钥库 |
| `:core:publish` | JGit、GitHub API、发布状态机 |
| `:core:apilot` | Apilot V2 Intent 桥接 |
| `:core:common` | 工具、加密存储 |

`template-min` / `template-full` 是导出模板壳（同一运行时，差异化权限），release 产物经 `:core:export` 收割后随织雀 APK 分发。

## zq.* 协议文档

网页侧可用的织雀 API 文档随仓库与 App 内维护（源：[`core/paste/src/main/resources/zq-docs/`](core/paste/src/main/resources/zq-docs/)）：

[`camera`](core/paste/src/main/resources/zq-docs/camera.md) · [`mic`](core/paste/src/main/resources/zq-docs/mic.md) · [`file`](core/paste/src/main/resources/zq-docs/file.md) · [`location`](core/paste/src/main/resources/zq-docs/location.md) · [`sensor`](core/paste/src/main/resources/zq-docs/sensor.md) · [`bluetooth`](core/paste/src/main/resources/zq-docs/bluetooth.md) · [`notification`](core/paste/src/main/resources/zq-docs/notification.md) · [`clipboard`](core/paste/src/main/resources/zq-docs/clipboard.md) · [`share`](core/paste/src/main/resources/zq-docs/share.md) · [`screen`](core/paste/src/main/resources/zq-docs/screen.md)

织雀桥 JS（`ZhiqueNative` 注入与 `zq_call` 协议）见 [`core/web/src/main/assets/zhique-bridge.js`](core/web/src/main/assets/zhique-bridge.js)；App 内「设置 → 开发者 → zq 协议文档」可随用随查。给外部 AI 写代码用？运行器/粘贴页内置**提示词桥**，一键生成带 API 规范的完整提示词。

## 安全与隐私

API Key AES 加密、主密钥在系统安全区；无账号无云服务，数据全在本机；Agent 外发动作默认逐项确认；keystore 口令零字面量（运行时随机生成、密文存安全区）；CI 发布件签名凭据只从环境变量/密钥服务读取。

## 路线图与文档

- [设计规格书](docs/superpowers/specs/2026-09-30-zhique-design.md)——完整需求与 29 条决策记录
- [实施计划](docs/superpowers/plans/2026-09-30-zhique-implementation-plan.md)——M0–M10 任务分解
- [开发进度书](docs/开发进度书.md)——实时进度

**明确不做（首版）**：iOS 跨平台 · 多项目同时运行 · 应用商店上架 · 云端打包 · 账号体系 · npm 构建链。

## 许可

[Apache-2.0](LICENSE)。欢迎 issue 与 PR。
