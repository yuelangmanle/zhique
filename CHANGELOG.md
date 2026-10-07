# 更新日志

本项目每次迭代交付都递增版本号（`app/build.gradle.kts` 的 versionCode +1 / versionName 语义化）
并在本文件记录变更。versionCode 单调递增是 APK 覆盖安装的前提（决策 29）。

## 0.2.9（versionCode 11）· 2026-10-07

**日循环第十批：API 管理体检修复（魔搭 ModelScope 真实方案端到端打通）**

用户以魔搭真实方案（api-inference.modelscope.cn/v1）实测暴露的一批问题：

- 修复：**服务商表单「拉取模型列表」布局混乱**——35 个长模型名（`deepseek-ai/…`）
  塞在不换行的 Row 里只显示 6 个，超宽 chip 被挤成逐字竖排；改 FlowRow
  全量换行展示 + 单行截断（截图验证）
- 修复：**表单「保存/取消」永远点不到**——表单缺固定视口，内容超出屏幕被裁
  且滚动到底也露不出按钮行（TV 实测复现）；ProviderFormView 加 weight(1f)
  占满剩余高度，verticalScroll 在固定视口内滚动
- 新增：**角色路由「从列表选」快捷选模型**——此前五槽绑定只能手抄长模型名；
  现在每槽可选服务商（多服务商 chips）+ 从该服务商的模型列表弹层点选即绑定
  （无缓存一键现场拉取）
- 新增：**ProviderModelCatalog 模型目录**——拉一次全局可用（内存+磁盘缓存，
  按 providerId 互斥防重复请求）；服务商表单拉取成功自动入目录
- 修复：**连接诊断误报「✓ 连通」**——实测魔搭 /models 对任意 token（含坏 Key）
  都返回 200，坏 Key 被判连通、对话才暴露「Authentication failed」；诊断在
  /models 后追加最小 chat 探测（1 token 流式读首事件，流内 error → 认证失败），
  文案改「连通 · Key 已验证」；验证好 Key 通过、诊断不再误导
- 魔搭端到端实证：添加 Provider → 拉取 35 模型（FlowRow 布局）→ 角色路由
  点选 DeepSeek-V4-Flash 绑定对话伙伴 → 项目对话真实流式回复「MS-OK」
  （推理思考折叠 + token 计量正常）；连接诊断「✓ 连通 · Key 已验证」

## 0.2.8（versionCode 10）· 2026-10-07

**日循环第九批：使用逻辑重构第一步——交付回归项目、引导价值前置**

- **砍「导出中心」一级 Tab**：导出是项目的「结局」而非用户去处——底部导航
  收敛为两项（项目 / 设置，玻璃标签栏），导出中心以「交付与档案」迁入
  设置页（带返回条），权限中心的密钥备份跳转同步改道
- **交付入口进项目中枢**：项目卡长按菜单新增「交付安装包…」直达导出向导
  （menu-deliver），用户在项目上完成「满意 → 交付」闭环，不必再去别处找入口
- **引导页价值前置**：步骤 1 = 「先玩 30 秒」（▶ 玩示例主按钮，核心链路
  粘贴→运行零配置）；AI 配置降为步骤 2（标注可选，随时在设置里补）；
  Apilot 降为步骤 3——首启教学只教 time-to-run 这一件事
- 北极星指标对齐：time-to-run 漏斗（paste_attempt→run_success）已由 0.2.6
  调试后端可观测，重构效果后续用数据裁决
- TV 实证：两 Tab 玻璃栏、长按菜单「交付安装包…」→ 导出向导第 1/3 步直达、
  设置→交付与档案、清数据重现引导页新顺序；全量 build 绿（onboarding
  测试在重排后仍全通过）

## 0.2.7（versionCode 9）· 2026-10-07

**日循环第八批：iOS 设计语言 + 液态玻璃材质 + 调试桥接补齐**

- **iOS 设计语言落地**：重写 ZqTheme——分栏底（systemGroupedBackground 浅灰/
  纯黑）+ 卡片面（白/#1C1C1E）+ iOS 系统语义色（红 FF3B30/绿 34C759/橙）
  + HIG 字阶（largeTitle 34 → caption2 11，字重建层级）+ 12dp 卡圆角；
  强调色保留品牌「雀青」
- **液态玻璃（Liquid Glass）材质**：新 `LiquidGlass` 组件——半透明填充 +
  镜面高光描边（左上亮→右下弱）+ 顶部内高光（lensing），浮于 `ZqAmbient`
  环境底（分栏底 + 两团极低饱和品牌色晕，静态）之上；落地于底部标签栏
  （玻璃浮层胶囊）与主页新建钮（玻璃圆钮）。内容区保持 iOS 分栏不透明卡
  （Android 无公开 backdrop-blur API，玻璃观感由环境底透出逼近，注释说明）
- **设置页 iOS 化**：inset grouped 分组卡（灰标题 + 圆角卡包行 + chevron）
- **调试桥接补齐（0.2.6 遗留债务）**：
  - 运行器 WebView 事件镜像进 DebugHub（`web.web.console/metrics/js_error…`，
    文本截断 120 防日志洪水；zq_call 走能力分发不镜像）——curl 实证：
    进运行器即见「WebGL 就绪：WebGL2」等事件
  - 调试事件流页新增 web 类别过滤；Onboarding 四按钮迁 ZqKit 带
    `onb.recognize/test/finish/skip` 语义动作
- 修复：根 Surface 改透明导致无显式色文字变黑不可见（LocalContentColor
  失效）——恢复 Surface 底色、环境底画其上
- TV 实证：主页/设置页截图验证（大标题 + 玻璃浮层标签栏 + 玻璃 FAB +
  iOS 分组卡）；全量 build 绿

## 0.2.6（versionCode 8）· 2026-10-07

**日循环第七批：全链路调试后端（每按钮/流程/反馈可观测）+ 可商用级克制 UI 大改**

- 新增 `:core:telemetry` 模块（零内部依赖，可持续底层设施）：
  - **DebugHub**：统一事件中枢——环形缓冲 1000 条、JSONL 落盘 1MiB 轮换、
    崩溃钩子（链式保留原 handler）、schema 带版本（v=1 只加不改）；
    全 API 不抛异常，调试设施绝不反噬业务
  - **DebugServer**：仅绑定 127.0.0.1 的极简 HTTP 后端（8791，占用自动 +1）——
    `GET /debug/health`（会话/版本/当前屏幕）、`GET /debug/events?since=&limit=`
    （增量拉取）、`POST /debug/mark|toast|clear`；电脑侧 `adb forward` 后即可 curl
- 三层插桩全覆盖：①根级触摸传感器（每个按钮必产 `ui.tap`，未迁移按钮也有信号）；
  ②导航状态单点映射（每次屏幕/流程切换记 `flow.screen`）；③中心拦截
  （toast/notice→`feedback`、导出安装结果→`flow/error`、ZqKit 按钮→`ui.click`
  带点状动作名 `apilot.gateway` 等）
- 调试入口：设置→开发者→「后端调试」（HTTP 开关默认跟构建类型、JSONL 落盘
  开关、事件流入口）+ 新页「调试事件流」（实时滚动、类别过滤、状态行）
- **UI 大改（克制商用风，去 AI 感）**：重写 ZqTheme——暖白/石墨中性面 +
  单一「雀青」强调色 + 完整字阶（字重/灰阶建层级）+ 中小圆角；删除 Aurora
  渐变玻璃三件套（AuroraBackground/GlassCard/GlowButton）；主页示例库/项目卡/
  剪贴板横幅的彩色渐变全部改平色面+细线；设置页骨架改 56dp 顶栏+分隔线；
  新增 ZqKit 基建组件（ZqCard/ZqTopBar/ZqButton 系/触摸传感器），动效仍全 spring
- TV 实证：调试后端 health/events/mark/toast 四端点 curl 全通（health 正确上报
  当前屏幕）；事件流页实时呈现 ui/flow/feedback/bg 各类事件；深色主题新视觉
  （石墨面+雀青+细线）截图验证；示例库/剪贴板渐变已收敛为中性面
- 测试：telemetry 模块 11 项（环形封顶/增量/落盘轮换/崩溃链/HTTP 四端点）+
  主题守卫测试改造（AuroraGlassUiTest→ThemeKitUiTest）；全量 build 绿

## 0.2.5（versionCode 7）· 2026-10-07

**TV 夜循环第六批：Apilot 真机崩溃根治（16 位 requestCode）+ 三向流转全流程 TV 实证**

- 修复：**「Can only use lower 16 bits for requestCode」真机+TV 双复现**——
  androidx.activity 1.11.0 起 ActivityResultRegistry 用随机高位 requestCode
  （反编译证实 `Random.nextInt(2147418112)+65536`，恒 >0xFFFF），与设备框架层
  对 startActivityForResult requestCode 的低 16 位校验必然冲突。Apilot 流转
  （方案授权 PICK / 网关一键 GRANT_GATEWAY）整体迁移到 MainActivity 传统
  onActivityResult 通道，固定 requestCode 42001/42003
- 修复：**「同步到 Apilot」静默失效**——Apilot 的 IMPORT intent-filter 带
  mimeType 约束，我们小负载走 extras 分支时缺 type，系统解析不到组件直接
  中止（START 后无 UI、立即回 CANCELED）。补 `setType(MIME_IMPORT)`（回归
  测试固化）；并按 Apilot 官方协议（导入本就无回传，文档示例即普通
  startActivity）把同步改为「发起即记账 + 15 分钟延迟清理负载缓存」，
  不再依赖永不到来的 RESULT_OK
- 涉及 `ProvidersScreen`（删除 3 个 registry launcher）与 `OnboardingScreen`
  （删除 2 个）共 5 处注册点；启动失败（宿主未就绪/ActivityNotFound）改为
  提示文案不闪退
- TV 模拟器全流程实证（Apilot 2.8.4+70）：①网关一键接入→Apilot 授权页→
  「授权并启动网关」→回跳落 Provider（http://127.0.0.1:8787/v1 · mock-mini）
  且列表即时刷新；②方案授权接入→PICK V2 scope 页→默认档授权→「（无 Key）」
  导入（密钥不出 Apilot 的安全语义正确）；③同步到 Apilot→审查页→「导入
  完成：3 个」→织雀推送时间记账
- 修复：滞留测试 `HomeControllerTest.移动分组与导出zip`（0.2.2 起 toast
  文案已改「已打包…」），补分享失败兜底用例；服务商列表在 Apilot 接入落库后
  即时刷新（LaunchedEffect 以接入时间为信号）

## 0.2.4（versionCode 6）· 2026-10-07

**TV 夜循环第五批续：隐私 12+ 补全 + eruda 收束**

- 隐私：`dataExtractionRules` 全域排除云备份/设备迁移——`allowBackup=false`
  在部分 Android 12+ 设备被忽略，新机制须显式声明（应用锁盐哈希+密钥密文
  不随备份导出，质量审查 Important-3 的 12+ 侧补全）
- eruda：面板主题跟随系统深浅色；入口按钮 CSS 上移让位调试抽屉
  （浮标在 TV WebView 上不渲染记 P3 债务，本体调试抽屉已覆盖三大面板）

## 0.2.3（versionCode 5）· 2026-10-07

**TV 夜循环第五批：手机端高频损伤修复 + 安全默认**

- 修复：**手机旋转/分屏丢运行器**——MainActivity 缺 configChanges 声明，
  尺寸变化重建 Activity 把导航状态全丢；与模板壳对齐，交由 Compose 自适应
- 修复：**应用锁冷启动内容闪现**——锁状态读盘前按「可能上锁」渲染空白壳，
  不再泄漏 ~2 秒完整主页内容；应用锁全链路（设 PIN→冷启动锁屏→解锁）TV 实证
- 修复：**PIN 设置无二次确认**——错一次就锁死应用，现须两遍一致才可保存
- 修复：**授权卡停留超 30 秒被误报超时**——会弹「授权卡+系统权限框」的能力
  （相机/麦克风/截屏/文件）超时统一放宽到 120 秒，拒绝语义得以正确回传
- 改进：调试抽屉把手支持点按展开/收起（此前只能拖拽）
- 改进：项目对话空历史显示欢迎语（告诉用户绑定了哪个项目、能干什么）

## 0.2.2（versionCode 4）· 2026-10-07

**TV 夜循环第四轮：导出安装双 P0 根因 + AI 对话链路修复**

- 修复：**点「安装」没反应**——PackageInstaller 待用户确认时系统返回的确认页
  Intent 从未被启动，确认框永远不会出现。现已正确拉起；拉不起的设备提示
  「存到下载目录后从文件管理器安装」
- 修复：**第二个项目导出的包安装必失败**（INSTALL_FAILED_CONFLICTING_PROVIDER）——
  模板 androidx-startup 的 authorities 没随导出包名改写，所有导出包共用一个
  provider 名。现 authorities 与 receiver 权限引用均随包名改写（含回归测试）
- 修复：**AI 对话 404**——Base URL 带 /v1 时对话请求拼出 /v1/v1/chat/completions；
  三协议 chat 端点统一走版本段感知拼接（模型列表此前已修、chat 漏修）
- 修复：**本地模型服务「无法连接」**——明文 HTTP（Apilot 环回网关、LM Studio/
  Ollama 局域网端点）被系统默认禁明文拦截；新增网络安全配置允许用户自填 http
- 修复：服务商表单「拉取模型列表」成败无反馈——提示上移到按钮旁，成功显示
  「已拉取 N 个模型」
- 改进：Token 用量统计显示服务商/项目名（不再显示 UUID）；角色路由无绑定时
  显示「预设推荐」而非误导性的「当前」；空白项目改为自解释欢迎页；
  「已降级 WebGL」改中性「WebGPU 不可用 · 已用 WebGL」；设置二级页返回恢复
  滚动位置；更新日志不再直出 Markdown 标记；密钥库警示卡黄底深色文字

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
  文件管理器路径对纯 v2/v3 包校验更严）
- 修复：编辑器改码后重跑仍是旧页——本地资源响应补 `Cache-Control: no-store`
  （WebView HTTP 缓存命中）
- 修复：**项目"退出再进数据消失"**——损坏的 project.json 自动重建（从 `<title>` 取回
  项目名并修复写回），不再是静默跳过；含回归测试
- 新增：**示例库**——预设示例扩充到 6 个（权限检测中心 / 粒子星云 / 极简番茄钟 /
  流光画板 / 星空 / 相机测试），空态网格 + 有项目时横向条，同名自动加序号
- 新增：**全权限检测中心示例**——10 项能力（相机/麦克风/定位/传感器/蓝牙/通知/
  剪贴板/分享/文件/截屏）逐个真实调用检测，结果 localStorage 持久化
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
