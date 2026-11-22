# JARVIS 最终审核与全面递增压测计划

## 一、目标与结论口径

本轮以《JARVIS_产品规格书》第五章 144 项和《JARVIS补充内容规格书》REQ-01～13 为验收基线，完成四件事：

1. 重建正确的“原需求 → 实现 → UI/对话入口 → 测试 → 外部验收”追踪关系。
2. 修复有代码证据的假成功、吞错、越状态执行、资源泄露及数据一致性缺陷，不增加规格外功能。
3. 执行第 1 轮主功能、第 2 轮轻量全功能、第 3～40 轮逐级加重的最终压力测试；失败即修复并重跑。
4. 重跑全量 JVM 测试、Lint、Debug 构建，更新文档、APK 压缩包和哈希，按修复独立提交并推送 `origin/main`。

最终结论区分“代码实现状态”和“外部验收状态”。JVM、Fake、Mock、静态扫描和 Lint 不得写成真机、模型推理、电话短信、传感器、云服务、arm64 PRoot、VNC 或 `su` 验收通过。若原需求仍缺失，必须保留 `PARTIAL/MISSING`，不得为了“最后审核”强行写成全部完成。

## 二、已确认的当前状态

### 2.1 追踪与文档失真

- `docs/requirements_traceability.md` 的 #49～144 与产品规格原编号错位，REQ-01～13 又混用了 `.monkeycode/specs` 的编号，旧 `128 DONE / 3 PARTIAL / 13 RESERVED / 0 MISSING` 不可作为验收结论。
- `docs/regression_test_report.md`、`docs/bug_report.md` 对接听电话 UI 的记录互相冲突；`docs/release_checklist.md`、`docs/项目完成报告.md`、`docs/repo_structure.md` 仍保留旧统计。
- `docs/DOWNLOAD.md` 的模型权重描述必须与 `configs/models/files/` 的真实文件大小和哈希核对；`docs/参考项目说明.md` 的 Wayland/GPU 描述与当前 Xvfb＋XFCE＋x11vnc 路线不一致。

### 2.2 有代码证据、可在本轮修复的问题

1. **会话与 ChatEngine**
   - `ConversationRepository.createConversation()` 忽略落盘失败，仍更新内存并返回会话。
   - 导入逐个写文件，失败可能留下半批数据；导入 ID 还需限制，避免被用作不安全文件名。
   - `ChatEngine.pendingDangerousAction` 是全局单值，新建、切换、删除、清空和导入会话未统一取消，可能确认执行另一会话遗留的危险命令。
   - 直接回复、无模型回复和流式完成路径忽略会话/记忆保存失败。

2. **长期记忆**
   - `MemorySystem` 主文件损坏后直接置空，没有最近可用备份恢复。
   - `forget`、`forgetByType`、`clearAll` 只清主文件，不归档清理，也没有按主题遗忘。
   - 所有写入入口未阻止密码、密钥、令牌等敏感原文默认入库；ChatEngine 主要保存摘要，不能据此宣称 REQ-11 的“每次对话完整原文＋SQLite＋JSONL”已完成。

3. **VM 生命周期与快照**
   - 同发行版多个 VM 共用 rootfs，而 PRoot 又按 rootfs 键管理会话，实例并不独立；VM 登记未持久化，ID 仅使用毫秒时间戳。
   - 启动缺少合法前置状态和幂等处理；停止失败一律恢复为 RUNNING；命令、包管理和删除缺少统一状态约束与每 VM 串行化。
   - 快照可在运行中打包/替换 rootfs；元数据读取失败被吞。删除 VM 与桌面后台进程的资源释放需要统一核验。
   - `VmConfigManager`、`VmNetworkManager` 不存在；`VmResourceMonitor` 读取的是宿主聚合 `/proc`，读取异常会归零，不能冒充 VM 隔离指标。

4. **镜像、模型与下载**
   - `VmImageImporter` 实际只导入 rootfs tar；OVA/OVF、QCOW2、VMDK、VHD/VHDX、RAW、ISO 能识别但不能启动，REQ-03 不得记 DONE。
   - `ModelDownloadManager` 初始化和 `getModelFilePath()` 只验大小，同尺寸损坏文件会被视为完成；并发下载与协程取消缺少统一保护。
   - `ModelFormatDetector` 的通用扩展名兜底会把随机字节认成模型。
   - `ModelImportManager` 只计算目标 SHA-256、不与源文件比较；LiteRT-LM 未执行加载和一次推理便报告导入成功，GGUF/Safetensors/PyTorch 只注册受限模型，不等于 REQ-04 推理闭环。

5. **VNC、root 与审计**
   - VNC 已用 `readFully/skipFully` 支持 TCP 分包，但分辨率、名称、CutText、矩形面积缺少边界，键鼠写失败被吞。
   - 两类审计均有 500 条上限，但写失败被吞；HostRoot 审计读写未串行化，原命令可能携带凭据。
   - Cloud 同步方法没有完整调用链和离线队列，异常仅返回 false；设置页令牌明文持久化。未配置云端时只能记明确降级，不能宣称双设备闭环。

### 2.3 现有压测盲区

`ProgressiveLoadTest` 虽有 40 轮递增，峰值达到 8 worker、40 会话、每会话 40 条消息、2400 字符和 2600 条记忆，但主要覆盖纯函数、指令解析、会话文件、记忆归档和并发创建。当前没有直接 ChatEngine 行为测试、VM 生命周期行为测试或 `androidTest`；VNC 仅测单像素，危险 root 仅测确认词表。上一轮 367 项测试、40/40、Lint 0 error/43 warning 和 APK 哈希均是旧基线，本轮必须重新生成。

## 三、实施步骤

### 步骤 1：先恢复正确的验收基线

1. 重写 `docs/requirements_traceability.md`：
   - 产品规格条目固定为 `P-001～P-144`，逐行复制原功能名称，不使用旧矩阵编号反推原需求。
   - 补充规格固定为 `SUP-REQ-01～SUP-REQ-13`，子项保留 `S1…`；非功能要求单列，不与 `.monkeycode/specs` 混号。
   - 每项记录：需求原文、真实源码/方法、UI或对话入口、行为测试、实现状态、外部验收状态、限制说明。
   - `RESERVED` 只用于原文明示预留；`DONE` 必须实现和入口同时存在；真机未测不影响代码状态判断，但外部验收必须为 `NOT_RUN/BLOCKED`。
2. 重点逐条核定 SUP-REQ-01～04、06、07、11、12，废除旧统计和“MISSING=0”结论。
3. 以已确定的 Android＋PRoot 路线为当前实现口径；补充规格中的双宿主平台、双 Hypervisor、六类磁盘镜像启动及缺少引擎的模型推理仍按未闭环记录，不虚构 QEMU、SSH、云服务或模型引擎。
4. 核对 `minSdk 24` 与原 Android 6/API 23 的偏差、真实模型文件大小和桌面技术路线，将结论写入矩阵及 Release 阻断项。
5. 本步只改追踪与审计说明，核对无误后独立提交并立即 `git push origin HEAD:main`。

### 步骤 2：修复会话持久化与危险操作隔离

涉及：`ConversationRepository.kt`、`ChatEngine.kt`、`ChatViewModel.kt`、必要的 UI 调用点及对应测试。

1. 将仓库创建会话改为显式可失败结果：落盘失败不更新 StateFlow、不切换当前会话，调用方不得继续执行命令或模型请求；保留 `lastError` 并在 UI 显示。
2. 导入先完整解析、校验 ID/结构，再暂存并原子替换；任一文件失败则回滚本批导入，内存与磁盘保持一致。导出、归档、删除、重命名继续遵循“持久化成功后才改内存”。
3. 待确认危险动作绑定会话 ID；新建、切换、删除所属会话、清空、导入及明确取消均使其失效。确认时再次核对当前会话、目标 VM 和 root 授权状态。
4. 会话切换/删除失败时保持原 UI 状态；直接回复、流式完成、无模型及异常路径检查保存结果。命令真实执行结果与“记录保存失败”分开报告，不因保存失败伪造执行失败，也不吞掉保存失败。
5. 新增真实行为测试：创建/导入写失败、半批回滚、并发写后重载、跨会话确认、取消后确认、删除后确认、root 授权在确认前被撤销、流式成功/异常/取消及持久化失败。

完成后运行对应测试，独立提交并推送。

### 步骤 3：修复记忆完整性、隐私和遗忘语义

涉及：`MemorySystem.kt`、`ChatEngine.kt`、`Jarvis.sq`（仅用于 REQ-11 所需结构化记录）、`DatabaseFactory.kt` 及测试。

1. 主文件采用原子写入＋最近有效备份；损坏时依次尝试主文件和备份，明确报告恢复来源或不可恢复，不静默清空。
2. 单条、类型、主题和全部遗忘同时处理主存储与归档；删除失败保留原状态并报告，删除后的内容不得再进入 `buildMemoryContext()`。
3. 默认拒绝密码、私钥、API Key、令牌等敏感原文写入，返回可见原因；不能仅脱敏后宣称“完整原文”。
4. 按 SUP-REQ-11 接通每次对话完整原文的本地 JSONL 记录与 SQLite 结构化索引，记录用户/助手原文、会话 ID 和时间戳；直接命令、模型失败和流式失败也如实记录，禁止只存摘要冒充完整记录。旧 JSON 数据执行可回滚迁移，损坏导入不覆盖现有数据。
5. 覆盖重启恢复、主文件/备份损坏、归档后遗忘、敏感输入、并发记录、大文本、容量归档、导入导出及 IO 失败测试。

完成后运行对应测试，独立提交并推送。若 SQLite＋JSONL 未全部接通，SUP-REQ-11 必须保持 PARTIAL。

### 步骤 4：收紧 VM 状态机、实例隔离与资源释放

涉及：`HavenVmManager.kt`、`ProotManager.kt`、`VmDistroManager.kt`、`VmSnapshotManager.kt`、`VirtualMachineScreen.kt`、`AppModule.kt` 及 VM 测试。

1. 用 UUID 创建实例；同发行版第二实例使用独立 rootfs 副本，复制失败不登记。持久化 VM 登记，重启后不得把已不存在的进程恢复成 RUNNING。
2. 明确状态转换：STOPPED/ERROR 可启动，RUNNING 重复启动幂等，PAUSED 只能恢复或停止，STOPPED 重复停止幂等；停止失败恢复操作前真实状态。非法转换返回 false，不启动隐藏会话。
3. 对同一 VM 的创建、启停、暂停、恢复、重启、删除、命令和包管理串行化；不同 VM 可并发。命令和包操作只允许在合法状态执行，失败保留退出码和错误。
4. 删除、快照回滚前统一停止 PRoot 与桌面相关后台会话；快照创建/回滚只允许在 STOPPED，回滚失败恢复原 rootfs，元数据错误通过状态暴露。UI 在非法状态下禁用操作并显示真实失败。
5. 为运行时边界引入可替换的最小接口，生产仍调用真实 `ProotManager`，JVM 使用 Fake 只验证状态机和调用顺序；不得据此宣称 PRoot 真机通过。
6. 资源监控增加“宿主共享资源/数据不可用”语义，读取失败不归零冒充正常；无可强制执行的 CPU、内存、NAT/桥接配置时不提供假开关，SUP-REQ-01-S4/S5/S6 如实标 PARTIAL/MISSING。

覆盖重复/并发/非法转换、暂停后启动、停止失败、进程重启恢复、同发行版双实例、删除清理、运行中快照拒绝、损坏元数据及文件压力。完成后独立提交并推送。

### 步骤 5：修复模型校验、VNC 边界和审计可见性

本步按文件边界拆成独立修复提交，每个提交后立即推送。

**模型与镜像**

- `ModelDownloadManager.kt`：大小符合的本地文件先进入 VERIFYING，在 IO 线程完成 SHA-256 后才 COMPLETED；`getModelFilePath()` 只返回已校验文件。下载串行化或用每模型锁保护，取消/异常在 `finally` 复位状态，保留可续传分片，错误页、错误 Range 响应、校验失败不得完成。
- `ModelFormats.kt`：移除“已知扩展名即识别成功”的通用兜底，按格式验证结构；随机字节、截断头、异常长度全部拒绝。
- `ModelImportManager.kt`：比较源/目标 SHA-256；LiteRT-LM 与 ONNX 在报告可用前执行真实加载和一次推理，失败注销并清理目标。缺少引擎的格式只能报告“已保存但不可推理”，不得写成 REQ-04 全部完成。
- `VmImageImporter.kt`：保持对不支持磁盘镜像的明确拒绝，校验格式/大小/空间与归档安全；不把识别扩展名当作可启动证明。

**VNC**

- `VncClient.kt`：限制总像素、名称和 CutText 长度，使用 Long 检查矩形乘法与边界，拒绝非法编码、截断帧和溢出；键鼠发送返回真实成功/失败，写失败触发断开并通知，连接/断开回调和资源释放幂等。
- `VncClientTest.kt`：用本地测试服务覆盖逐字节分包、多矩形 RAW 帧、Bell/CutText、非法分辨率、超大长度、截断、写失败和主动断开；Fake 服务只验证协议实现。

**root、审计与云设置**

- `HostRootManager.kt`、`VmAuditLog.kt`：审计串行追加、原子裁剪、敏感参数脱敏，写失败通过返回值和 `lastError` 可见；500 条上限在并发下仍成立。真实 `su -c id` 和退出码语义不变。
- `CloudSyncManager.kt`、设置页：增加可见错误状态，使用现有加密能力/Android Keystore 保存令牌并清除旧明文；云未配置、未登录、TLS/HTTP/超时失败均明确区分。
- 不伪造离线补执行和双设备闭环；没有真实服务端与第二设备时，SUP-REQ-12 保持 PARTIAL，外部验收阻断。

每项补充故障注入和边界测试，不能用源码字符串断言替代行为验证。

### 步骤 6：重构并执行最终 40 轮全面递增压测

涉及：`load/ProgressiveLoad.kt`、`load/ProgressiveLoadTest.kt`，必要时增加按子系统拆分的负载测试，但保持统一轮次与负荷配置。

1. **轮次规则**
   - 第 1 轮：轻量主链路——ChatEngine 直接命令、流式回复、会话落盘/重载、完整记忆、危险命令确认/取消。
   - 第 2 轮：轻量覆盖全部可 JVM 执行链——上述主链路，加 VM 状态机 Fake、快照/rootfs 文件、VNC 本地异常服务、模型格式/下载故障、root Fake、Cloud Mock、审计、导入导出和异常输入。
   - 第 3～40 轮：所有维度严格递增，综合 `weight` 不得下降；保留并至少达到原峰值：8 worker、40 会话、每会话 40 消息、2400 字符、2600 记忆。
2. **递增负载内容**
   - 并发会话创建/切换/写消息/重载/归档/导入导出，穿插损坏 JSON、半批写入、只读/缺失目录、重复 ID、空输入、畸形输入和超长文本。
   - 记忆持续写入、检索、归档、主题遗忘、敏感拦截、备份恢复；长任务中流式分块、错误和取消交替出现。
   - VM 重复/非法/并发转换、启停失败、删除清理、快照与文件 IO；VNC 分包帧、异常帧和断开；审计超过 500 条；下载超时、镜像回退、Range 错误、SHA 失败和磁盘异常。
   - 记录每轮工作量、耗时、失败数；最后一轮增加混合 soak 批次，采样 JVM heap、线程数和可用时的 FD 数，检查持续回落趋势和资源上限。该数据只代表构建主机 JVM。
3. **失败纪律**
   - 任一轮失败即停止继续加重，定位并修复，重跑失败轮；修复提交并推送后，再从第 1 轮完整跑完 40 轮作为最终证据。
   - 不得删除失败用例、把异常当预期成功、为通过而无依据放宽预算，或把普通散测计入 40 轮。
4. 使用 Gradle JUnit XML 与 `LOAD_ROUND` 日志生成真实统计，报告每类负荷及沙箱无法覆盖的外部项目。

### 步骤 7：最终静态审核、全量门禁和交付

1. 扫描并人工复核 TODO/FIXME、占位实现、空回调、硬编码成功、无效 `return true`、吞错 `runCatching/catch` 和不可达 UI；只修有证据的问题，不机械删除合理异常处理。
2. 使用既定 JDK 17、Android SDK 和代理环境依次运行：
   - `./gradlew --no-daemon ... :app:testDebugUnitTest`
   - `./gradlew --no-daemon ... :app:lintDebug`
   - `./gradlew --no-daemon ... :app:assembleDebug`
3. 用 Python `xml.etree.ElementTree` 汇总 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 的 tests/failures/errors/skipped/time；记录 `app/build/reports/lint-results-debug.*` 的实际 error/warning，不沿用旧数字。
4. 更新 `regression_test_report.md`、`bug_report.md`、`release_checklist.md`、`项目完成报告.md`、`repo_structure.md`、`参考项目说明.md`、`DOWNLOAD.md` 和追踪矩阵，消除互相矛盾与旧统计。Release 清单必须同时保留“原需求未闭环项”和“真机/外部服务未验收项”。
5. 将最终 Debug APK 复制为 `apk/jarvis-debug.apk` 用于打包校验，生成 `apk/jarvis-debug.apk.7z`，执行 `7z t`，分别计算压缩包及包内 APK 的 SHA-256；Git 只保留 `.7z`，更新直链、大小、代码基线和哈希。
6. 文档修正、压测体系、最终测试报告、APK 交付分别独立提交，每次立即推送；最终确认工作树、提交哈希与 `origin/main` 一致。
7. 只交付 Debug，不运行 `assembleRelease`。最终回复列出实际测试数、40 轮结果、Lint、APK 哈希、剩余实现缺口和必须真机验收的阻断项，不写“全功能已通过”。

## 四、约束与决策

- 当前权威实现路线保持 Android＋PRoot；不临时引入 QEMU/KVM、VirtualBox、云后端或新模型引擎来制造表面闭环。
- AI 与用户终端操作同一 VM；VM 内 root 默认开启，同一 root 开关对两者生效。宿主机 root 仅在设备已 root、用户开启授权且真实 `su` 成功时可用。
- 测试替身只验证状态机、协议和异常路径；其通过不提升外部验收状态。
- 不加规格外功能，不通过隐藏功能、删除测试或修改需求原文获得“完成”。
- 每个可验收修复先跑对应测试，再独立提交并立即推送；最终必须重跑全部门禁。

## 五、最终交付物

- 正确编号的原 144 项＋补充 REQ-01～13 追踪矩阵。
- 已修复代码及行为/故障注入测试。
- 最终 40 轮递增加压证据与全量测试、Lint、构建报告。
- 一致的缺陷、回归、Release 阻断和下载文档。
- 可校验的 `apk/jarvis-debug.apk.7z` 及 SHA-256，所有提交已推送 `origin/main`。
