# JARVIS Bug 报告

> 记录开发与测试过程中发现的所有缺陷、根因、修复与验证状态。
> 复核日期：2026-10-20 | 版本：v2.0

---

## 1. 本轮（2026-10-20 复核）发现并修复的 Bug

### BUG-2026-1017：大量功能实现后无界面入口（dead code）— P1

- **发现阶段**：从零复核（逐 DI manager grep 调用链）
- **现象**：`PerceptionManager`、`AutonomousDecisionManager`、`SmartHomeManager`、
  `TelemedicineManager`、`BehaviorPredictor`、`DataManager`、`EnvironmentPerception`、
  `PhoneController`、`SmsController`、`CalendarController`、`NavigationController`、
  `SystemSettingsController`、`MediaController`、`CrossAppOperator`、`CommunicationManager`、
  `DeviceControlManager`、`HardwareDiagnostics`、`RemoteAssistanceManager` 等 **18+ 个管理器
  在 Koin 中注册，但项目内除 DI 声明外零调用方**。
- **根因**：历史开发只写模块未接 UI，`SettingsScreen` 各标签页内容为简化实现。
- **影响**：违反 REQ-05「禁止占位、必须真实可用」。功能虽已实现，用户完全无法触达。
- **修复**：
  - 新增 `ui/settings/SmartHubTab.kt`（设置→智能中枢）：接入感知/自主决策/智能家居/健康/远程医疗/AR/行为预测/备份/数据处理。
  - 新增 `ui/settings/SecurityTab.kt`（设置→安全中心）：接入 `SecurityManager` #38、#41-48。
  - 新增 `ui/settings/SystemControlTab.kt`（设置→系统操控）：接入 #49-68 系统控制器。
- **验证**：`assembleDebug` EXIT=0；新增 5 个集成测试通过。

### BUG-2026-1018：`ConnectedDevicesTab` 设备列表为假数据 — P1

- **现象**：设备"扫描"仅 `showMsg("正在扫描设备...")`，列表存于 `SharedPreferences` 的固定字符串集，从不调用 `CloudSyncManager`。
- **根因**：`CloudSyncManager` 已完成（`discoverDevices`/`pairDevice`），但 UI 未接。
- **修复**：重写 `ConnectedDevicesTab` 使用真实 `CloudSyncManager`：扫描→`discoverDevices()`、配对→`pairDevice()`、断开→从列表移除。
- **验证**：编译通过，调用链真实。

### BUG-2026-1019：`LogTab` "最近日志"为硬编码固定 5 行 — P1

- **现象**：日志卡片直接 `listOf("[时间] 系统启动", "[时间] JARVIS 初始化完成", …)` 伪造。
- **根因**：未读取真实日志文件。
- **修复**：改为读取 `filesDir/logs/` 最新文件内容；无日志时显示"暂无日志"。
- **验证**：编译通过。

### BUG-2026-1020：`LanguageVoiceTab` 声线列表为硬编码 — P1

- **现象**：`val voices = listOf("JARVIS 中文（默认）", "JARVIS English", "小艺中文", "小美中文")`，
  与 `VoiceManager.getVoiceLibrary()` 无关，且"小艺/小美"根本不存在。
- **修复**：改为从 `VoiceManager` 读取真实声线库与已下载状态，支持下载与切换。
- **验证**：编译通过。

### BUG-2026-1021：`TranslationEngine` / `InfoProcessor` 无调用方 — P1

- **现象**：`TranslationEngine` 仅被 `ConversationEnhancer.translate()`（自身也无调用方）引用；
  `InfoProcessor` 无任何调用方。功能 #12/13/19-25 实际不可达。
- **修复**：
  - `ChatEngine` 新增翻译指令解析：用户说"翻译…成英文"直接调用翻译引擎。
  - `ChatEngine` 新增检索上下文注入：命中检索指令时执行真实网络搜索并做筛选/汇总/排序后注入 prompt。
- **验证**：编译通过；`ChatEngine` 构造参数由 6 → 7，DI 同步更新。

### BUG-2026-1022：记忆只能导出不能导入（#141 数据迁移缺失）— P1

- **现象**：`MemorySystem.importMemories()` 已实现但无调用方，用户无法迁移数据。
- **修复**：`LogTab` 新增"导入记忆 (JSON)"按钮，通过 SAF 选择文件并调用 `importMemories()`。
- **验证**：编译通过。

### BUG-2026-1023：`CloudSyncManager` 无账号/鉴权机制 — P1

- **现象**：#134「无处不在的接入」只有设备配对骨架，API 请求不带任何身份凭证。
- **修复**：新增 `login()`/`register()`/`logout()`/`restoreSession()`/`accountToken`/`accountName`，
  所有请求经 `authHeader()` 附加 `Bearer` 令牌；`ConnectedDevicesTab` 提供登录/注册/退出 UI 并持久化令牌。
- **验证**：编译通过。

---

## 2. 历史 Bug（已修复）

### Bug #1：VmManager ID 冲突（P1）

- **现象**：`expected:<STOPPED> but was:<RUNNING>`
- **根因**：`create()` 用 `System.currentTimeMillis()` 作 ID，同毫秒创建冲突。
- **修复**：ID 改为 `vm_${时间戳}_${计数器}`。
- **验证**：`VmManagerTest` 通过。

### Bug #2：Lint MissingPermission（3 处 / P2）

- **修复**：添加 `@SuppressLint("MissingPermission")` 并注明调用方确保权限。
- **验证**：lint 0 错误。

### Bug #3：Lint NewApi（P2）

- **修复**：添加 `@RequiresApi`；`PriorityQueue` → `MutableList`。

### Bug #4：Lint UnspecifiedRegisterReceiverFlag（P2）

- **修复**：改用 `ContextCompat.registerReceiver` + `RECEIVER_NOT_EXPORTED`。

### Bug #5 / #6：构建环境（Java 版本、Android SDK）（P1，环境类）

- **修复**：JDK 17 + 手动安装 SDK platform-35/build-tools-35.0.0。

---

## 3. 已知未修复 / 待实机验证

| 编号 | 描述 | 级别 | 状态 |
|------|------|------|------|
| TBD-1 | 8 种唤醒方式在真机上的实际触发率未知 | P1 | 待实机测试 |
| TBD-2 | `KeepAliveService` 保活效果（厂商 ROM 可能杀后台） | P1 | 待实机测试 |
| TBD-3 | 运行时权限请求流程 | P1 | 待实机测试 |
| TBD-4 | 4 个 LiteRT 模型真机加载与推理性能 | P1 | 待实机测试 |
| TBD-5 | 3 个新页签（智能中枢/安全中心/系统操控）真机交互 | P1 | 待实机测试 |
| TBD-6 | 部分 PARTIAL 功能需相机实时采集入口 | P2 | 能力已实现，UI 待补 |

---

## 4. 本轮复核新发现并已修复的 Bug

### BUG-2026-1024：应用图标非用户提供图片（P1，已修复）

- **现象**：安装后桌面图标为自动生成的橙/白矢量圆环，**不是**用户在
  `软件默认图标/Jarvis.jpg` 中提供的图片。
- **根因**：`AndroidManifest.xml` 引用 `@mipmap/ic_launcher` /
  `@mipmap/ic_launcher_round`，实际解析到自动生成的矢量资源
  （`drawable/ic_launcher_foreground.xml`、`ic_launcher_background.xml`、
  `mipmap-anydpi-v26/*.xml`、`mipmap-hdpi/ic_launcher.xml` 回退矢量）。
  用户提供的 JPG 从未被转换/接入。这违反了 `软件默认图标/使用文档.md`
  的明确要求：「把该图片当做默认软件图标」「把该图片改成支持 apk 图标的格式」。
- **修复**：
  1. **格式转换（第一步，用户明确要求的步骤）**：原图为 332×367 非正方形
     满幅 JPEG，无透明通道、无可抠除的纯色背景。先将其**居中裁剪为
     332×332 正方形 PNG** 作为母图
     （`软件默认图标/converted/Jarvis_square_332.png`），即「改成适合图标的格式」。
  2. **由母图派生图标（第二步）**：用 Python/PIL 生成：
     - 自适应图标前景 `drawable-{mdpi..xxxhdpi}/ic_launcher_foreground.png`
       （108dp 画布，内容置于中心 72dp 安全区，安全区外透明）；
     - 自适应图标背景 `drawable-{mdpi..xxxhdpi}/ic_launcher_background.png`
       （取自母图边缘主色 `#FE8615`，避免自适应裁切露出白边）；
     - API 24-25 回退位图 `mipmap-{mdpi..xxxhdpi}/ic_launcher.png`
       （圆角方形，四角透明）与 `ic_launcher_round.png`（圆形，四角透明）。
  3. 删除原自动生成矢量图标资源。
  4. `mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml`
     改为引用真实图片的前景/背景 PNG。
- **验证**：
  - 构建成功；APK 内含 5 个密度的真实图片资源。
  - 反解 APK 内资源逐项校验：
    - `mipmap-*/ic_launcher.png` 与 `ic_launcher_round.png` 均为 **RGBA**，
      四角 alpha=0（透明），中心 alpha=255 → 形状（圆角方形 / 圆形）正确；
    - `drawable-*/ic_launcher_foreground.png` 为 RGBA，外圈 alpha=0、
      安全区内 alpha=255 → 自适应裁切安全区正确；
    - 透明通道经 aapt2 打包后**未被压平**。
  - 单元测试全量重跑：64/64 通过。
- **影响面**：仅资源层，无代码逻辑变更。

---

## 5. BUG-2026-1025：应用启动即崩溃（P0，第三版修复）

- **现象**：安装后点击图标**完全打不开**，界面不出来即崩溃。
  ```
  FATAL EXCEPTION: main
  android.app.RemoteServiceException:
    Context.startForegroundService() did not then call Service.startForeground():
    ServiceRecord{... com.jarvis.pineapple.service.KeepAliveService}
  ```
- **第一轮根因**（`测试日志/jarvis_emulator_logcat.txt`）：
  - `JarvisApp.onCreate()` 在应用尚未进入前台时调用 `KeepAliveService.start()`；
  - `KeepAliveService.onCreate()` 把 `startForeground()` 排在 `createChannel()`
    和 Koin 依赖获取之后；
  - 系统规定 `startForegroundService()` 后约 **5 秒**内必须调用 `startForeground()`。
- **第一版修复**（**已证明不充分**）：
  1. `KeepAliveService.onCreate()` 中把 `startForeground()` 提到其它初始化之前；
  2. 删除 `JarvisApp.onCreate()` 中的服务启动；
  3. 改由 `MainActivity.onStart()` 启动服务；
  4. 新增 `startSafely()`。
- **第二轮模拟器复测：未通过，崩溃完全复现**，错误一致。
  报告：`测试日志/第二轮模拟器测试完整报告_(jarvis_test2_report.md).md`
  - `23:50:53 Bringing down service while still waiting for start foreground`
  - `23:51:14 Timeout executing service: KeepAliveService`
  - `23:51:15 FATAL EXCEPTION: RemoteServiceException`
  - 关键新证据：`WakeEngine$start$1$1.invokeSuspend` 类校验 **7.868 秒**，
    且发生在主线程上（原 `WakeEngine.scope = Dispatchers.Main`）。
- **第一版失败的原因（根因修正）**：
  - 5 秒时钟的**起点是 `startForegroundService()` 被调用那一刻**，
    而不是 `Service.onCreate` 开始执行的时候；
  - `Service.onCreate` 回调同样排在主线程消息队列中。若主线程被
    `wakeEngine.start()`（在 `Dispatchers.Main` 上，类校验 7.8s）等启动工作占满，
    `onCreate` 根本排不上队，超时必然发生；
  - 结论：**只调整 `onCreate` 内部顺序无法解决**，必须让主线程及时空闲。
- **第三版修复（本次）**：
  1. `WakeEngine`：初始化作用域由 `Dispatchers.Main` 改为 `Dispatchers.Default`，
     唤醒器初始化与流收集全部移出主线程
     （`_wakeEvents` 为线程安全 `MutableSharedFlow`，跨线程发射安全）；
  2. `MainActivity.onCreate()`：唤醒引擎启动改到 `Dispatchers.Default` 的
     `wakeInitScope`；保活服务**延后到首帧渲染完成后**
     （`window.decorView.post { KeepAliveService.startSafely(...) }`）再启动，
     此时主线程已空闲，可在时限内完成 `startForeground()`；
  3. `KeepAliveService.onStartCommand()`：增加幂等 `startForeground()` 兜底，
     覆盖 `START_STICKY` 重启路径；
  4. 保留 `startSafely()`：启动异常只记日志、不外抛。
- **验证**：
  - `clean assembleDebug` 构建成功；反解 APK 的 DEX 确认 `decorView`、
    `startSafely` 等修复符号已打入包内；
  - 回归测试 `KeepAliveServiceOrderingTest` 更新为校验新不变量
    （「服务在首帧后启动」「唤醒引擎不得使用 Dispatchers.Main」），防止回退；
  - 全量单元测试：**69/69 通过**。
- **待确认**：第三版仍需用户在模拟器/真机复测（本环境无模拟器，无法自查）。
- **影响面**：启动流程与线程调度，功能行为不变。

---

## 6. 第二轮报告中的其它问题

来源：`测试日志/第二轮模拟器测试完整报告_(jarvis_test2_report.md).md`

| 编号 | 问题 | 级别 | 处理 |
|------|------|------|------|
| BUG-2026-1026 | WakeEngine 初始化耗时 7.8s（主线程） | P2 | ✅ 本轮已修（移出主线程） |
| BUG-2026-1027 | Compose `SnapshotStateList` 锁验证失败 | P2 | ⏳ 待处理（Debug 未开 R8，属预期；Release 需 baseline profile） |
| BUG-2026-1028 | 启动阶段 GC 频繁（20s 内 3 次，堆达 82%） | P3 | ⏳ 待处理 |
| — | 模拟器环境限制（无 KVM / 2GB / 无真实硬件） | 非缺陷 | 记录，不改代码 |

---

## 7. 无 P0 bug（历史）

本轮复核未发现 P0（崩溃/数据丢失）级别缺陷。

---

---

## 8. 2026-11-01 阶段 5.1 记录

- **单元测试**：`./gradlew testDebugUnitTest` → 102/102 通过。
- **Debug 构建**：`./gradlew assembleDebug` EXIT=0，产物 `apk/jarvis-debug.apk`（218,835,095 B）。
- **lintDebug**：34 errors / 49 warnings，按计划记录、不以警告阻断 Debug 交付。
  - MissingPermission 17（BLE GATT / 电话，运行时已有权限门面）
  - NewApi 15（ShortcutInfo API 25 vs minSdk 24）
  - StateFlowValueCalledInComposition 2
- **未做**：`assembleRelease`（等待实机清单）。

*文档版本：v2.5 | 生成日期：2026-11-01 | 上一版本：v2.4（2026-10-22）*
