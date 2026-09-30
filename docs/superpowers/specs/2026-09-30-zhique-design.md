# 织雀（Zhique）设计文档

- **日期**：2026-09-30
- **状态**：待用户审阅
- **一句话定位**：安卓手机上的 HTML 运行器 + AI 编程助手——粘贴即跑、AI 自写自调、权限全桥接、一键打包发布。

---

## 1. 概述

### 1.1 解决的问题

安卓用户从 AI 助手（ChatGPT、DeepSeek 等）拿到 HTML/CSS/JS 程序后，在手机上没有好的运行方式：浏览器打开兼容性差、没有调试手段、无法调用系统权限、无法变成独立应用。织雀把这整条链路收进一个 App。

### 1.2 产品定位与分发

| 项 | 决定 |
|---|---|
| 目标用户 | 作者本人（重度使用），开源到 GitHub 供他人自建 |
| 分发 | GitHub Releases（stable / beta 双通道），App 内检查更新、下载 APK、安装 |
| 合规约束 | 无商店审核要求，权限可开到最大；不做合规收敛 |
| 开源协议 | Apache-2.0 |
| 最低兼容 | Android 12（API 31）——RenderEffect 系统 blur 的下限 |
| 开发设备基线 | Android 17 新机，系统 WebView = 最新 Chromium |
| 界面语言 | 首版中文 |
| 全局原则 | **不设开发周期约束，体验/能力/稳定最优** |

### 1.3 成功标准

1. 从外部 AI 复制 → 织雀内运行，≤2 次触点；
2. 报错 → 交给 Agent → 自动修复并验证，全程无需用户写代码；
3. 任意项目可导出为独立 APK，覆盖升级与多项目共存可靠；
4. 网页经权限桥可调用相机/文件/传感器等系统能力，且每次使用均经用户授权、可吊销。

---

## 2. 需求清单（全部已确认）

### 2.1 核心功能

- **F1 智能粘贴管道**：一次粘贴，自动识别形态（完整 HTML / 说明文字混代码块 / HTML+CSS+JS 多代码块 / 片段），清洗（markdown 围栏、行号污染）、组装、自动命名、入库。规则置信度低时 AI 兜底解析（只解析不改逻辑）。清洗项透明可撤销。
- **F2 运行器**：系统 WebView 渲染，支持 CSS / JS / WebGL / WebGPU / Canvas 全量标准；三种界面模式随时切换（底部抽屉 / 上下分屏 / 悬浮球浮层），每项目记忆偏好；调试抽屉含报错 / Console / 网络三页签；可选注入 eruda 高级面板。
- **F3 轻编辑器**：sora-editor，语法高亮、行号、小改；「选中即上下文」（问 AI 这段 / 修这段）；大改动交给 AI。
- **F4 AI Provider 层**：多协议原生适配（`openai_compatible` / `anthropic_messages` / `google_genai`），BYOK，Provider 可插拔；每个模型带能力声明 `modality: text|vision`（内置知识库 / 自动探测 / 手动覆盖三层填充，徽章全程可见）；**输出上限体系**（全局默认 16384 tokens，知识库按模型上限自动修正，可手动覆盖）；**防截断自动续写**（检测到因长度截断时自动携带前缀续写拼接，默认最多 3 段，UI 标识）；**思考流独立解析**（reasoning/thinking 与正文分流，见 X7）。
- **F5 Agent 自主调试**：function calling 工具箱（run/stop/reload、read_console、read_dom_snapshot、screenshot_page（仅 vision）、edit_file、list_files、read_file/grep）；自主循环跑→采→改→验；三道安全带（预算上限默认 5 轮可调、每轮版本快照可回滚、审计日志）。
- **F6 权限桥**：W3C 标准路（getUserMedia / geolocation / Notification 等经 WebChromeClient 回调路由）+ 织雀增强路 `zq.*`；能力 × 项目二维权限矩阵，首次调用弹原生授权卡，运行时可吊销；第一批全覆盖：文件读写、相机/麦克风/截屏、定位/传感器/蓝牙、通知/剪贴板/分享。
- **F7 打包导出（一步到位）**：端上模板 APK 组装 + apksig 签名 + zipalign；统一持久密钥库（可导出备份）；每项目唯一包名可共存；版本号自增覆盖升级；同时支持桌面快捷方式（图标 + 全屏运行）；导出壳内置同一套运行时与权限桥，行为与织雀内一致。
- **F8 GitHub 发布**：JGit + GitHub REST API + fine-grained PAT（仅 repo 权限，加密存储）；手动模式（日常 2 步、首次 4 步，参数预填只点确认）与 Agent 模式（出计划、批准一次、跑全程）；**持久化 Job 状态机**，断点续跑、跨模式接管、幂等。
- **F9 Apilot 桥接**：官方 V2 Intent 协议双向——`PICK_API_CONFIG`（从 Apilot 授权读取，四档 scope：connection / models.default / models.all / secret.api_key，Key 不勾不回传）与 `IMPORT_API_CONFIGS`（推送到 Apilot）；provider.id × protocol.id 与织雀 Provider 层同构。
- **F10 提示词桥**：App 内生成「织雀提示词」= 用户第一句 + 按项目勾选的 API 段落 + 固定规范（环境说明、zq.* 权限语义、单文件输出要求）；复制或系统分享直达外部 AI；解析端反向兜底（发现疑似权限调用但不认识时提示）。
- **F11 内置 Git 发布到 GitHub**（即 F8 的能力底座）：项目与仓库绑定，一键推送更新，AI 生成 commit message 可改。

### 2.2 体验与系统需求

- **X1 Aurora Glass 设计语言**：晨光（管理域）/ 深空（运行域）双底、低饱和极光光晕、毛玻璃（RenderEffect 实时渲染）、镜面高光、发光交互、语义色恒定（靛蓝 #46509F = 交互 / 绿 = 健康 / 琥珀 = 等待 / 红 = 错误）；去 AI 感（单色系强调、渐变只留主按钮、图标单色容器 + 极简符号）。
- **X2 灵动动效**：全线非线性 spring（damping 0.75–0.85）：抽屉弹簧过冲、按压回弹、FAB 形变、进度环弹性、时间线滑入；纯 GPU 合成层，120Hz；低端机自动减光晕层数。
- **X3 设置全集**（详见 §7）：AI 服务商（含 Apilot 双向流转、织雀提示词、连接诊断）、权限中心、导出与签名、发布与同步、通用、隐私与安全、关于织雀（版本号 / 检查更新 / 更新日志 / 开源仓库）、开发者（预留空壳）。
- **X4 首启引导**：90 秒完成——粘贴识别 API 配置、Apilot 接入、无 Key 可玩内置示例。
- **X5 通知**：导出完成、Agent 完成、有新版本。
- **X6 自更新**：拉 GitHub Releases 比对版本，应用内下载 APK 安装。
- **X7 输出与思考展示（类 ZCode 形态）**：思考过程与正文**严格分区渲染**，思考块默认折叠——摘要行显示「已思考 N 秒 · M tokens」，点击平滑展开逐字回放，再点收起；正文区永不被思考内容穿插；Agent 时间线中每步的思考同样折叠。续写段以「已续写 N 段」徽标标识；续写后仍触顶时显示「已达输出上限」警告 + 一键「继续输出」。

### 2.3 已按默认方案收口的 13 项

多 Provider 角色路由（见 §4.4）；大项目上下文→三层记忆体系（见 §4.5）；Agent 运行时编辑器只读（单会话锁）；断网/限流指数退避 3 次后暂停可续跑；WebView 启动检测 + WebGPU 不可用自动降级 WebGL 并标注 + 过旧引导更新；注册系统分享接收方；剪贴板前台检测（回前台读一次 + 显式按钮，不做后台监听）；首次用 Agent/云 API 前隐私告知；Token 用量统计（每 Provider / 每项目）；一次运行一个项目（多开二期）；中文 UI；Apache-2.0 / `com.zhique.runner` / 导出 `com.zhique.export.<slug>`；Apilot 包名 `com.example.api_manager` 做成可配置常量。

---

## 3. 总体架构

### 3.1 技术选型

**原生 Kotlin + Jetpack Compose + 系统 WebView。** 决定性理由：本产品的命脉是对 WebView 的深度控制（console 捕获、JS 注入、权限回调、文件选择）与端上 APK 签名，原生是一等公民；Flutter/Capacitor 均需穿透封装层。

### 3.2 四层结构

```
┌─ UI 层（Jetpack Compose）────────────────────────────┐
│ 项目列表 / 全屏运行器（三模式）/ 轻编辑器 / AI 对话    │
│ Agent 会话 / 导出中心 / 导出向导 / 设置               │
├─ 核心服务层 ─────────────────────────────────────────┤
│ 智能粘贴管道 / WebView 运行时+织雀桥JS / 调试代理     │
│ AI Provider 层（三协议） / Agent 编排+记忆 / 权限桥   │
│ 打包导出管线 / 发布状态机（Git/GitHub） / 更新检查    │
├─ 系统能力层（Android 12–17）─────────────────────────┤
│ 系统 WebView（Chromium：CSS/JS/WebGL/WebGPU/Canvas） │
│ Android API（文件/相机/麦克风/定位/传感器/蓝牙/通知…）│
│ PackageInstaller / KeyStore / BiometricPrompt        │
├─ 数据层 ─────────────────────────────────────────────┤
│ 项目仓库（文件式） / Keystore 密钥库 / Provider 配置  │
│ （AES 加密，主密钥在系统安全区）                     │
└──────────────────────────────────────────────────────┘
```

### 3.3 进程模型

单 App；**WebView 跑独立进程**（`:web`）——WebGL/WebGPU 重载页面崩溃不拖垮主进程，崩溃后自动恢复重载。导出的 APK 使用同一运行时（模板壳）。

### 3.4 模块划分（Gradle）

| 模块 | 职责 |
|---|---|
| `:app` | UI、导航、依赖组装 |
| `:core:project` | 项目仓库、project.json、版本快照、history/ |
| `:core:paste` | 智能粘贴管道（纯 JVM 可测） |
| `:core:web` | WebView 运行时、织雀桥 JS、调试采集器 |
| `:core:ai` | Provider 协议适配、能力声明、角色路由、SSE |
| `:core:agent` | 工具循环、预算、审计、三层记忆、上下文组装 |
| `:core:permission` | 权限注册表、zq.* 桥、授权 UI 协议 |
| `:core:export` | 模板 APK 组装、apksig 签名、密钥库、快捷方式 |
| `:core:publish` | JGit、GitHub API、发布状态机 |
| `:core:apilot` | V2 Intent 桥接 |
| `:core:common` | 工具、加密存储、更新检查 |

### 3.5 数据模型

```
<应用数据区>/
├─ projects/
│   └─ <projectId>/
│       ├── index.html（+ 任意子目录/资源）
│       ├── project.json      # 名称、图标色、布局偏好、权限矩阵、
│       │                     # 导出记录（包名/版本号）、仓库绑定、Provider 覆盖
│       ├── zhique.md          # 项目记忆（Agent 自动读写）
│       └── history/           # 版本快照 + Agent 审计日志 + release-job.json
├─ keystore/zhique-release.jks # 统一签名密钥库（可导出）
└─ config/providers.json       # 加密的 Provider 配置
```

关键结构：
- `PermissionState[projectId][capability] ∈ {未申请, 询问中, 已授予, 已拒绝}`；
- `ReleaseJob：{id, state: PLANNED→CHECKED→COMMITTED→PUSHED→RELEASED*, evidence{sha, remote, tag}, updatedAt}`，每步落盘；
- `ProviderConfig：{id, protocolId, baseUrl, apiKeyEnc, model, modality, roles}`。

---

## 4. 核心模块设计

### 4.1 智能粘贴管道（`:core:paste`）

```
剪贴板/分享 → 形态分类器 → 路径处理 → 组装引擎 → 清洗报告 → 入库
```

1. **分类器**：完整文档（含 `<!DOCTYPE`/`<html>`）｜混排（文字+代码块）｜多代码块（html/css/js 标注）｜纯 JS/CSS 片段｜（扩展）Apilot 配置片段。输出置信度。
2. **处理**：完整文档→清洗；混排→提取代码块按语言归类；多块→按标准骨架组装；片段→包一层可运行模板。
3. **AI 兜底**：置信度 < 阈值时交内置 AI 解析重组，仅结构化不改逻辑。
4. **清洗报告**：每项（如「剥离围栏 ×2」）可展开、可整体撤销。
5. **入库**：`<title>` 或 AI 提取命名；设置项控制「粘贴后自动运行 or 停在预览」。
6. 入口三合一：首页剪贴板置顶卡、系统分享目标、手动粘贴按钮。

### 4.2 WebView 运行时（`:core:web`）

- 独立进程 `:web`；崩溃监测 + 自动重载 + 崩溃报告入调试抽屉。
- 启动时能力检测（WebGPU / WebGL2 / Storage 等），不可用自动降级并在抽屉标注。
- **织雀桥 JS**（页面加载前注入）：
  - 采集器：console 全级别、未捕获异常、Promise 拒绝、网络失败、资源 404、白屏检测（加载后 DOM 空），带行号堆栈，形成时间线（喂 AI 的轻量结构，与 eruda 面板互不依赖）；
  - `zq.*` API 注入（见 §4.6）；
  - W3C 标准权限 API 的 Permission 语义对齐（拒绝返回 `denied`，不抛异常）。
- 可选注入 eruda（设置开关），提供 Elements/Sources/Network 完整面板。
- 截图：PixelCopy 抓 WebView（App 自有内容，无需运行时授权）。

### 4.3 轻编辑器（`:app` + sora-editor）

高亮（HTML/CSS/JS）、行号、IME 优化；选中代码浮出「问 AI 这段 / 修这段 / 复制」；Agent 会话运行中编辑器只读。偏好：字号、代码字体、自动缩进。

### 4.4 AI Provider 层与角色路由（`:core:ai`）

- **协议适配器**（可插拔接口 `Provider`）：
  - `openai_compatible`：`/v1/chat/completions`，SSE 流式，覆盖 OpenAI/DeepSeek/GLM/Kimi/Qwen/Ollama 等；
  - `anthropic_messages`：`x-api-key` + `/messages`，SSE；
  - `google_genai`：Gemini 原生端点。
- **能力声明**：`modality: text|vision`。三层填充：内置知识库（gpt-4o/claude/gemini 主力 = vision；deepseek-chat = text）→ 自动探测（发一次带图最小请求）→ 手动覆盖。徽章显示于服务商列表、Agent 顶栏、提示词桥。
- **角色路由**（不同模型干不同的活，可组合）：

| 角色 | 用途 | 默认 |
|---|---|---|
| 对话伙伴 | 问答/讲解/生成 | 全局默认 Provider |
| Agent 主力 | 规划/决策/关键改码 | 强模型 |
| 快循环 | 重试验证/摘要/压缩 | 便宜快模型 |
| 视觉检查 | 截图回看 | vision 模型（误选纯文本**硬拦**） |
| 杂务 | commit message/标题 | 任意 |

  预设组合一键切换：**省钱 / 均衡 / 质量**；每项目可覆盖。
- 密钥 AES 加密存储，主密钥在 Android Keystore（系统安全区）。
- 网络错误指数退避重试 3 次 → 会话暂停可续跑。

#### 4.4.1 输出上限 · 防截断 · 思考流

**输出上限（max_tokens）默认值表**：

| 场景 | 默认 | 说明 |
|---|---|---|
| 全局（对话/生成） | **16384** | 模型实际上限更低时自动取模型上限（如 deepseek-chat 8192） |
| Agent 主力（关键改码） | 16384 | 同上修正 |
| 快循环（摘要/压缩/杂务） | 4096 | 摘要任务够用，省钱防拖沓 |
| 自动续写每段 | 8192 | 见下 |

- 上限三层填充（与 modality 同机制）：内置模型知识库（各模型 max output 出厂即标）→ 请求时探测（错误码/响应头修正）→ Provider 设置「输出上限」手动覆盖（0 = 用模型上限）。
- **防截断自动续写**：
  1. 请求时 `max_tokens` 按上限填满，不人为设小；
  2. 流式监听截断信号——OpenAI `finish_reason=="length"` / Anthropic `stop_reason=="max_tokens"` / Gemini `finishReason=="MAX_TOKENS"`；
  3. 触发即自动续写：携带已输出内容为前缀发续写请求，**无缝拼接**（代码场景优先在语法边界拼接，去重衔接行）；默认最多续 **3 段**（设置可调 0–10，0 = 关闭改为提示）；UI 显示「已续写 N 段」徽标；
  4. 续写后仍触顶 → 明确警告「已达输出上限」+ 一键「继续输出」按钮（用户点击再续一段），绝不静默截断；
  5. Agent 的 `edit_file` 工具产出若检测到未闭合代码结构（HTML 未闭合标签 / 括号不平衡），强制触发续写而非应用残缺补丁。
- **思考流**：
  - 协议映射：DeepSeek R1 `reasoning_content` / OpenAI o 系列 `reasoning` / Anthropic `thinking` content block / Gemini `thought` → 统一内部 `ThinkingDelta` 事件，与 `ContentDelta` 分流；
  - 思考内容**永不混入**发给模型的对话历史正文（按协议要求正确回传或丢弃）；
  - UI 按 X7 渲染（默认折叠 + 摘要行 + 展开逐字回放）。

### 4.5 Agent 编排与记忆（`:core:agent`）

- **工具箱**（function calling，按模型能力装配）：
  `run` `stop` `reload`｜`read_console`｜`read_dom_snapshot`｜`screenshot_page`（仅 vision）｜`edit_file`（带 diff 与快照）｜`list_files` `read_file` `grep`｜`create_repo` `push` `read_releases`（git 类外发动作默认逐项确认，全自动模式才放手）。
- **防幻觉硬隔离**：纯文本模型工具箱不装配 `screenshot_page`（幻觉源头不存在）；截图照样拍给用户看；系统提示词同步声明「你是纯文本模型，不要描述画面」双保险。
- **三层记忆**（参考 Claude Code / Aider / mem0 理念，Kotlin 自研轻量实现）：
  1. 全局记忆：跨项目用户偏好与约定；
  2. 项目记忆 `zhique.md`：项目约定/风格/坑，每次会话自动携带，会话结束自动把新约定写回；
  3. 会话上下文：预算组装（系统规范 → 项目记忆摘要 → 任务目标 → **文件地图**（repo map 简化版：结构 + 符号索引，不塞全文）→ 报错时间线 → 近期轮次）；大项目靠 `read_file/grep` 按需取用；逼近预算用快循环模型压缩早期轮次（任务目标与关键结论永不丢）。
- **循环与安全带**：目标 → 跑 → 采集 → 改 → 重跑 → （vision）截图自查 → 直到干净或叫停；预算（轮数/token/时间，默认 5 轮）可见可调；每轮快照可回滚任意步；审计日志完整可回放；编辑器只读锁。
- 对话模式与 Agent 模式共存随时切换。

### 4.6 权限桥（`:core:permission`）

- **两条接入路，同一个注册表**：
  - 标准路：网页直接用 W3C API（camera/mic/geolocation/Notification…），WebChromeClient 回调 → 转发授权卡 → 结果按 Permission API 语义返回；
  - 增强路：`zq.*`——`zq.camera.*`、`zq.file.*`（完整文件系统读写）、`zq.sensor.*`、`zq.bluetooth.*`、`zq.clipboard.*`、`zq.share.*`、`zq.notification.*`、`zq.screen.capture()` 等。
- **授权模型**：`能力 × 项目` 矩阵；首次调用弹原生授权卡（哪个项目、要什么、干什么）；拒绝对网页是优雅信号非崩溃；运行中随时吊销。
- **系统权限集中申报**：全部 dangerous 权限在 App manifest 一次性声明，运行时按需向用户申请（App 集中持权，网页按需申请）。
- **权限中心**：矩阵总览、全局状态、运行中提醒；导出时按**运行期真实使用记录**生成建议清单。

### 4.7 打包导出（`:core:export`）

- **模板 APK 组装**：内置模板壳（同一织雀运行时 + 权限桥，无编辑器 UI）→ 项目文件进 assets → 按项目勾选权限生成 manifest 合并 → 应用名/图标/主题色可定制 → 包名 `com.zhique.export.<slug>`。
- **签名**：apksig（v2/v3，支持 v4）端上签名 + zipalign；**统一密钥库**首次生成，AES 保护存储于私有目录，**导出备份 `.jks` 是一等公民功能**（丢失 = 无法覆盖更新旧包，导出页常置顶提示备份状态）。
- **版本语义**：同项目每次导出版本号自增（1,2,3…），同包名同签名覆盖升级、数据保留；不同项目不同包名共存。
- **交付**：PackageInstaller 安装（「未知来源」授权一次）/ 导出到下载目录；桌面快捷方式（图标 + 全屏直达运行）作为轻量选项并存。
- 导出记录入 project.json，导出中心（底部 Tab）管理全部导出物。

### 4.8 GitHub 发布与状态机（`:core:publish`）

- 底座：JGit（commit/push）+ GitHub REST API（建仓/Release，OkHttp）+ fine-grained PAT（仅 repo 权限，加密存储，首次引导创建）。
- **手动模式**（用户只负责点击）：日常 2 步——1 触发起（自动变更检查/分支核对/远端同步检查 + AI 生成 commit message）+ 1 次确认；首次 4 步——包信息 → 选/建仓库（预填名称、公私、README/LICENSE）→ 确认 → 推送。
- **Agent 模式**：说目标 → Agent 出计划（commit→push→tag→Release）→ 批准一次 → 跑全程 → 汇报。
- **持久化状态机**：`PLANNED → CHECKED → COMMITTED → PUSHED → RELEASED*`（*可选）；每步完成即落盘证据（sha/remote/tag/时间戳）；**断点续跑**（重进提示「进行到 X，继续？」）；**跨模式接管**（模式只是编排器，状态机唯一）；**幂等**（每步执行前核对远端实际状态）。
- 项目绑定仓库（project.json），后续一键「推送更新」。

### 4.9 Apilot 桥接（`:core:apilot`）

按官方 V2 协议（文档 `docs/android-third-party-import.md`，对应 Apilot v1.24.0）：

- **读**：`PICK_API_CONFIG` + scopes（`connection`/`models.default`/`models.all`/`secret.api_key`），Activity Result 接收 extra 或 content URI（立即读取，不持久化 URI）；
- **写**：`IMPORT_API_CONFIGS`，payload 用 `apiProfiles`（V2 语义化），小负载 JSON extra / 大负载 content URI + `FLAG_GRANT_READ_URI_PERMISSION`；
- **对齐**：`provider.id × protocol.id` 与织雀 Provider 层同构落库（deepseek 不降级为 custom）；
- **协议纪律**：`RESULT_CANCELED` ≠ 失败不重试；URI 10 分钟即焚；Key 不写 URL/日志/剪贴板；应用锁 PIN 停留不算无响应；桥接审计记录可清；
- UI：AI 服务商页「双向流转」两张动作卡 + 连接状态 + 上次同步时间。

### 4.10 提示词桥（`:core:paste` + `:app`）

- 生成器：用户第一句（想法）+ 按项目勾选的 API 段落（只带用得上的 zq.* 文档）+ 固定规范（Chromium 环境说明、权限语义、单文件输出要求）；
- 输出：复制全文 / 系统分享直达外部 AI App；
- 反向兜底：粘贴解析发现「疑似想调系统权限但 API 不认识」时，提示用提示词桥重写或内置 AI 翻译为 zq.* 等价实现。

---

## 5. UI / UX 设计

### 5.1 Aurora Glass 设计语言

| 要素 | 规范 |
|---|---|
| 双域 | 晨光浅底（管理域：首页/设置/导出）、深空暗底（运行域：运行器/编辑器/Agent） |
| 光晕 | 每屏 ≤2 枚大半径径向光斑，低饱和环境光，缓慢漂移（无限过渡动画） |
| 毛玻璃 | blur(20px)+saturate(150%)，Compose `RenderEffect`（API 31+ 系统 GPU 实时渲染，非贴图） |
| 镜面高光 | 卡片顶部 1px 白线内阴影 |
| 发光交互 | 主按钮/主进程同色系外发光（靛蓝系） |
| 语义色 | 靛蓝 #46509F = 可交互；绿 = 健康；琥珀 = 等待/注意；红 = 错误；全 App 不换义 |
| 去 AI 感 | 强调色单色系；渐变仅主按钮一处；图标单色圆角容器 + 极简符号；emoji 克制使用 |

### 5.2 动效规范（全线非线性）

- Compose spring，damping 0.75–0.85，全 App **禁用线性 easing**；
- 规定动作：抽屉弹簧过冲回弹（跟手）、卡片按压回弹（scale 0.94→1.035→1）、FAB 加号→叉号形变、进度环弹性填充、列表项滑入微过冲；
- 纯 GPU 合成层动画，目标 120Hz；低端机自动减光晕层数。

### 5.3 导航与屏幕清单

- **底部三 Tab**：项目（首页）/ 导出中心 / 设置。
- 屏幕清单（14+）：
  1. 首启引导（2 步：粘贴识别 API / Apilot 接入；可跳过玩示例）
  2. 首页（剪贴板置顶卡 + 最近运行 + 文件夹分组 + FAB + 项目卡带 ▶ 与报错角标）
  3. 智能粘贴预览（解析结果 + 清洗报告 + 命名 + 运行/存草稿）
  4. 运行器（三模式：底部抽屉 / 上下分屏 / 悬浮球浮层；调试抽屉三页签 + 模式切换器）
  5. Agent 会话（目标 + 步骤时间线 + diff + 预算环 + 暂停/回滚/续预算 + 能力徽章）
  6. 轻编辑器（sora-editor + 选中即问 + AI 输入条）
  7. 导出向导（3 步：包信息与图标 → 权限与签名 → 打包安装/分发）
  8. 导出中心（全部导出物、密钥库状态）
  9. AI 服务商（列表 + 双向流转 + 织雀提示词 + 诊断）
  10. Apilot 授权页（系统页，四档 scope）
  11. 权限中心（矩阵 + 全局）
  12. 发布向导（手动）/ Agent 发布计划
  13. 关于织雀（版本 / 检查更新 / 更新日志 / 仓库 / 许可证）
  14. 开发者（预留空壳：调试开关 / 日志导出 / zq.* 协议文档 / 意图测试器）

### 5.4 关键动线（触点数）

- 粘贴到运行 ≤2 触；报错到 Agent 1 触；导出 3 触出包；日常推送 1 触发起 + 1 确认。

---

## 6. 错误处理与安全

| 场景 | 策略 |
|---|---|
| WebView 渲染进程崩溃 | 独立进程隔离 + 自动重载 + 崩溃报告进抽屉 |
| 粘贴解析失败 | 规则→AI 兜底→保留原文入库待处理，永不丢数据 |
| Provider 网络错误/限流 | 指数退避 3 次 → 会话暂停（状态保留可续跑） |
| Agent 失控 | 预算硬顶 + 每轮快照回滚 + 随时暂停/终止 |
| 发布中断 | 状态机落盘断点续跑 + 幂等重试 |
| 密钥库丢失风险 | 导出页常置顶备份状态提示；恢复流程支持导入 .jks |
| 覆盖安装失败 | 导出前校验包名/签名/版本号三元组一致性 |
| 纯文本模型幻觉 | 工具层硬隔离（不装配截图）+ 系统提示词声明 |
| 外发动作 | git 推送/建仓默认逐项确认；全自动模式需显式开启 |
| 隐私 | 首次使用 Agent/云 API 前告知「代码将发送至你配置的服务商」；Key 永不出现在 URL/日志/剪贴板 |
| 应用锁 | PIN + 生物识别（可选，对齐 Apilot 行为） |
| 审计 | Agent 全步骤 + 桥接记录可查看可清理 |

---

## 7. 设置信息架构（全集）

```
设置
├─ AI 服务商
│   ├─ 已接入列表（协议徽章 · modality 徽章 · 状态 · 默认模型）+「添加」
│   ├─ 角色路由（五槽 + 省钱/均衡/质量预设）
│   ├─ Apilot 双向流转（从 Apilot 接入 / 同步到 Apilot / 上次同步 / 记录可清）
│   ├─ 织雀提示词（全局版 + 按项目定制）
│   ├─ 输出与思考（输出上限默认 16384 · 自动续写段数默认 3 · 思考默认折叠开关）
│   └─ 连接诊断（逐个测试连通 / 刷新模型目录）+ Token 用量统计
├─ 权限中心（项目×能力矩阵 · 全局状态 · 运行中提醒）
├─ 导出与签名（密钥库状态/备份/恢复 · 导出历史）
├─ 发布与同步（GitHub 账号 PAT / 推送偏好：commit 语言·默认分支·新仓默认公私 / 自更新通道 stable·beta）
├─ 通用
│   ├─ 外观（主题跟随系统）
│   ├─ 运行器（默认布局三选一 · 悬浮球位置记忆 · 沉浸模式）
│   ├─ 智能粘贴（检测开关 · 清洗严格度 · 粘贴后自动运行/停在预览）
│   ├─ 编辑器（字号 · 代码字体 · 自动缩进）
│   ├─ Web（WebGPU 状态检测 · 桌面模式 UA · 下载行为 · eruda 面板开关）
│   └─ 通知（导出完成 / Agent 完成 / 新版本）
├─ 隐私与安全（应用锁 PIN/生物 · 审计日志 · 隐私告知记录）
├─ 关于织雀（版本号 build · 检查更新（GitHub Releases）· 更新日志 · 开源仓库 · 许可证 Apache-2.0）
└─ 开发者（预留：调试开关 / 日志导出 / zq.* 协议文档 / 意图测试器）
```

---

## 8. 测试策略

| 层 | 方法 |
|---|---|
| 纯 JVM 单测 | 粘贴分类器/组装器（各形态语料）、发布状态机（断点/幂等/跨模式）、上下文组装预算、角色路由、能力声明、权限矩阵状态机、版本自增逻辑 |
| 协议测试 | 三协议适配器对打真实端点的录制回放（mockwebserver）+ SSE 分帧边界 + **截断信号→续写状态机**（length/max_tokens/MAX_TOKENS 三协议注入用例）+ **思考流分流**（reasoning 与 content 交错到达） |
| Agent 测试 | mock Provider 脚本化工具循环（成功/预算耗尽/工具报错/纯文本无截图路径/**续写拼接去重/未闭合代码强制续写**） |
| UI 测试 | Compose 测试：三模式切换、抽屉跟手、导出向导 |
| 真机验收 | Android 17 主力机全流程：粘贴→运行→Agent 修复→导出→覆盖升级→共存→快捷方式；Apilot 双向真机对打 |
| 打包验证 | 导出 APK 安装、签名校验（apksigner verify）、覆盖升级数据保留、权限清单与运行记录一致 |
| 更新验证 | 检查更新→下载→安装（真实 GitHub Releases 演练） |

---

## 9. 技术选型与开源策略

**直接依赖（自研必输）**：sora-editor（编辑器，0.24.x 活跃）、JGit（Git）、apksig（签名）、OkHttp + kotlinx-serialization（网络/SSE）、Jetpack Compose、Coil（图标加载，如需）。

**化用魔改**：eruda——注入作高级调试面板（开关控制）；喂 AI 的采集器自研（轻量时间线结构）。

**参考设计理念（不搬代码）**：Claude Code（auto-compact 压缩、CLAUDE.md 项目记忆→zhique.md）、Aider（repo map→文件地图）、OpenHands（工具循环编排）、mem0（记忆分层）、Apilot（智能粘贴、应用锁、加密存储）。

**必须自研（核心竞争力）**：zq.* 权限桥、智能粘贴管道、调试采集器、Agent 编排 + 角色路由 + 三层记忆、发布状态机、端上打包导出管线、Aurora Glass UI + 动效体系。

**不采用**：LangChain4j 等服务端 Agent 框架（反射重、线程模型不合移动端），自研 ~2k 行薄编排层。

---

## 10. 里程碑（高层；详细任务见实施计划文档）

- **M0 地基**：脚手架、模块划分、CI、加密存储、项目仓库。
- **M1 能跑**：WebView 运行时 + 手动建项目 + 运行器（三模式）+ 调试采集。
- **M2 会粘**：智能粘贴管道 + 分享目标 + 首页。
- **M3 会聊**：Provider 三协议 + 对话面板 + 能力声明 + 输出上限/防截断续写 + 思考流折叠展示。
- **M4 会修**：调试代理 + Agent 循环 + 记忆 + 安全带 + 轻编辑器。
- **M5 能调系统**：权限桥全能力 + 权限中心。
- **M6 能出包**：导出管线 + 签名 + 密钥库 + 快捷方式 + 导出中心。
- **M7 能发布**：GitHub 发布 + 状态机 + 自更新。
- **M8 互联**：Apilot 桥接 + 提示词桥。
- **M9 打磨**：Aurora Glass 全面落地 + 动效 + 设置全集 + 首启引导。
- **M10 发布 0.1.0**：真机验收 + GitHub Release。

---

## 11. 风险与对策

| 风险 | 对策 |
|---|---|
| 端上 APK 组装/签名坑多（zip 结构、resources.arsc 改动） | 模板壳预编译、只动 assets 与最终签名；apksig 官方库；真机矩阵验证早期启动（M6 前置 spike） |
| WebGPU 在部分 WebView 不可用 | 能力检测 + WebGL 降级 + 标注；桌面 UA 选项 |
| 手机端 JGit 大仓库性能 | 项目均为小仓库（HTML 项目），性能无虞；限制仓库规模提示 |
| AI 输出格式不稳定（工具调用） | 三协议各自适配层容错 + 严格 JSON schema 校验 + 重试一次 |
| `com.example.api_manager` 是 Apilot 当前包名 | 做成可配置常量，Apilot 改包名只动一处 |
| 权限桥滥用面大 | 能力×项目细粒度 + 授权卡 + 吊销 + 审计；导出时最小权限清单 |

---

## 12. 明确不做（首版）

- iOS / 跨平台；
- 多项目同时运行（二期）；
- 应用商店上架与合规收敛；
- 云端打包服务器；
- 内置付费/账号体系；
- npm/构建工具链支持（项目形态就是静态 HTML 站点）。

---

## 附：决策记录（按对话顺序）

1. 自用 + GitHub 开源，不做商店合规；2. Android 17 主力机，minSdk API 31；3. AI 多协议原生适配；4. 粘贴一次到位，混排自动解析 + AI 兜底；5. 轻编辑 + AI 改码；6. 权限桥第一批四类全覆盖；7. 打包一步到位（真 APK 含端上签名），统一密钥库 + 每项目唯一包名 + 版本自增；8. 原生 Kotlin 路线；9. WebView 独立进程；10. 运行器三模式全做自由切换；11. Agent 最高权限（自动运行/调试/截图），带三道安全带；12. 项目文件夹式管理 + 分组；13. Apilot 桥接按官方 V2 Intent 协议；14. 内置 Git 发布；15. 设置全集（权限中心/API 管理/版本/更新日志/检查更新/开发者预留）；16. Aurora Glass + 去 AI 感 + RenderEffect 实时渲染；17. 全线非线性 spring 动效；18. 提示词桥；19. 发布双模式 + 持久化状态机断点续跑；20. 多 Provider 角色路由可组合；21. 三层记忆（全局/zhique.md/会话预算组装 + 压缩）；22. 模型能力声明三层填充 + 纯文本防幻觉硬隔离；23. 开源策略：底层用库、产品层自研；24. 输出上限体系（全局默认 16384，知识库按模型修正，可覆盖）；25. 防截断自动续写（截断信号检测→前缀续写无缝拼接，默认 3 段可调，触顶警告+一键继续，编辑工具未闭合代码强制续写）；26. 思考流与正文分离（默认折叠摘要行+展开回放，类 ZCode 形态，思考不混入正文历史）。
