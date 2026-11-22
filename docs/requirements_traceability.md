# 需求追踪矩阵

> 审核日期：2026-11-22。范围仅为静态源码、生产入口与现存 JVM 测试源码核对；本步未运行测试、构建或真机/外部服务验收。
>
> 路径缩写：生产源码 `M = app/src/main/java/com/jarvis/pineapple/`；测试源码 `T = app/src/test/java/com/jarvis/pineapple/`。测试列只证明对应源码存在，不表示测试已通过；无直接覆盖则写“无直接测试”。

## 判定规则

- `DONE`：规格所需真实实现存在，且可从 UI 或对话触达；不等于外部验收通过。
- `PARTIAL`：实现受限、子项缺失或缺少生产入口；包括“只有算法/规则/协议方法，但未形成规格链路”。
- `RESERVED`：仅限规格明确预留且本计划允许的 P-114、P-116、P-123、P-126、P-127、P-131、P-135、P-137、P-138、P-140、P-142、P-144。
- `MISSING`：未找到对应生产实现。
- 外部 `NOT_RUN`：实现及入口可达，但本步未做外部实测；`BLOCKED`：缺实现、入口、服务、引擎、硬件，或属于明确预留。所有外部状态均为本步事实，不是历史结论。
- “原始需求”中的功能名称与实现方式逐字取自《JARVIS_产品规格书.md》第五章，编号严格保持原始顺序。

## 一、144 项主矩阵

| ID | 原始需求（功能；实现方式） | 真实实现文件/方法（M 下） | UI/对话入口 | 现存 JVM 测试源码（T 下） | 实现 | 外部 | 限制 |
|---|---|---|---|---|---|---|---|
| P-001 | 语音识别；本地 ASR / 云端 ASR | `speech/SpeechRecognizer.kt`、`SpeechManager.kt` | 对话麦克风 | `integration/ConversationPipelineIntegrationTest.kt` | DONE | NOT_RUN | 未实测识别服务、麦克风与云端 ASR。 |
| P-002 | 自然语言理解；LLM + 意图分类 | `chat/ChatEngine.kt`、`ConversationEnhancer.kt` | 对话输入 | `integration/ConversationPipelineIntegrationTest.kt` | DONE | NOT_RUN | 意图分类含确定性规则，未实测各模型理解质量。 |
| P-003 | 语音合成；本地 TTS（JARVIS 中文声线） | `speech/TtsManager.kt`、`VoiceManager.kt` | 语音播放、声线设置 | 无直接测试 | PARTIAL | BLOCKED | 系统 TTS 可用；自定义 Piper/JARVIS 声线未接入播放链。 |
| P-004 | 对话应答；LLM 生成 | `chat/ChatEngine.kt` | 对话页 | `integration/ConversationPipelineIntegrationTest.kt` | DONE | NOT_RUN | 未实测本地/远端模型服务。 |
| P-005 | 多轮对话；上下文管理 | `chat/ChatEngine.kt`、`ConversationRepository.kt` | 对话页、历史会话 | `chat/ConversationRepositoryTest.kt` | DONE | NOT_RUN | 上下文窗口有限，未做长会话外部验收。 |
| P-006 | 上下文理解；历史消息 + 记忆系统 | `chat/ChatEngine.kt`、`autonomous/MemorySystem.kt` | 对话页、记忆设置 | `autonomous/MemorySystemTest.kt` | DONE | NOT_RUN | 记忆检索质量与长期效果未实测。 |
| P-007 | 唤醒词响应；唤醒词引擎 | `speech/VoiceWakeManager.kt`、`WakeWordEngine.kt` | 唤醒设置、后台监听 | `integration/WakeWordPipelineIntegrationTest.kt` | DONE | NOT_RUN | 未做真实麦克风、噪声与功耗验收。 |
| P-008 | 待命状态维持；前台服务常驻 | `service/KeepAliveService.kt` | 启动应用/唤醒服务 | `service/KeepAliveServiceOrderingTest.kt` | DONE | NOT_RUN | 厂商后台限制与长期保活未实测。 |
| P-009 | 语气与情绪适配；情绪识别 → prompt 调整 | `chat/ConversationEnhancer.kt` | 对话链 | `integration/ConversationPipelineIntegrationTest.kt` | DONE | NOT_RUN | 情绪识别为关键词规则，效果未外部验收。 |
| P-010 | 主动询问与确认；意图置信度阈值 | `ConversationEnhancer.kt: analyzeIntentConfidence()` | 对话澄清链 | `integration/ConversationPipelineIntegrationTest.kt` | DONE | NOT_RUN | 阈值基于启发式评分，未做场景验收。 |
| P-011 | 调侃与幽默回应；JARVIS 人格 prompt | `personality/Personality.kt` | 人格设置、对话链 | `personality/PersonalityTest.kt` | DONE | NOT_RUN | 生成风格取决于所选模型。 |
| P-012 | 多语言翻译；LLM 翻译 | `chat/TranslationEngine.kt`、`ChatEngine.kt` | 对话翻译指令 | `chat/TranslationEngineTest.kt` | DONE | NOT_RUN | 未实测翻译服务及语种覆盖。 |
| P-013 | 外语实时转译；流式 ASR + 翻译 + TTS | `chat/TranslationEngine.kt`、`ConversationEnhancer.kt` | 文本翻译指令 | `chat/TranslationEngineTest.kt` | PARTIAL | BLOCKED | 只有翻译子链，未形成流式 ASR→翻译→TTS 实时闭环。 |
| P-014 | 极简指令执行；短指令直接工具调用 | `ConversationEnhancer.kt: parseSimpleCommand()`、`AssistantCommandParser.kt` | 对话指令 | `chat/AssistantCommandParserTest.kt` | DONE | NOT_RUN | 仅覆盖解析器支持的指令与工具。 |
| P-015 | 语境感知应答；结合前台 App / 时间 / 地点 | `ConversationEnhancer.kt: getCurrentContext()` | 对话链 | 无直接测试 | PARTIAL | BLOCKED | 仅填充时间；`currentApp/location/recentActivity` 为空。 |
| P-016 | 反讽识别与回应；LLM 语义 + 语气分析 | `ConversationEnhancer.kt: detectSarcasm()`、`ChatEngine.kt` | 对话链 | 无直接测试 | DONE | NOT_RUN | 前置检测为规则，语义回应交给 LLM；准确性未实测。 |
| P-017 | 修辞与指令区分；意图分类器 | `ConversationEnhancer.kt: classifyStatement()` | 对话指令拦截链 | `chat/AssistantCommandParserTest.kt` | DONE | NOT_RUN | 规则分类覆盖有限，未做复杂语料验收。 |
| P-018 | 隐含意图推断；LLM 推理 | `ConversationEnhancer.kt: inferImplicitIntent()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 方法存在但未接入 ChatEngine。 |
| P-019 | 网络搜索；搜索 API | `info/WebSearchEngine.kt`、`chat/ChatEngine.kt` | 对话搜索指令 | `info/WebSearchEngineTest.kt`、`chat/ChatSearchTriggerTest.kt` | DONE | NOT_RUN | 搜索源、网络与结果质量未实测。 |
| P-020 | 信息检索；搜索 + RAG | `info/InfoProcessor.kt`、`chat/ChatEngine.kt` | 对话检索注入 | `info/WebSearchEngineTest.kt` | DONE | NOT_RUN | 检索增强范围受搜索源与上下文窗口限制。 |
| P-021 | 数据库调取；本地数据库 + 网络源 | `info/InfoProcessor.kt: queryDatabase()` | 无生产入口 | 无直接测试 | PARTIAL | BLOCKED | 查询方法未接入 UI/对话，未形成双源调取链。 |
| P-022 | 实时数据流处理；Kotlin Flow / WebSocket | 全项目 `StateFlow/SharedFlow`、`chat/ChatEngine.kt` | 对话流式回复、状态页 | `integration/ConversationPipelineIntegrationTest.kt` | DONE | NOT_RUN | 已有 Flow 实时状态；未验证 WebSocket 外部数据流。 |
| P-023 | 情报筛选；LLM 过滤 | `info/InfoProcessor.kt: filterIntelligence()` | 对话检索处理链 | 无直接测试 | PARTIAL | NOT_RUN | 实际为相关度/来源/关键词确定性过滤，不是 LLM 过滤。 |
| P-024 | 情报汇总；LLM 摘要 | `info/InfoProcessor.kt: summarizeIntelligence()` | 对话检索处理链 | 无直接测试 | PARTIAL | NOT_RUN | 实际输出计数、均值、来源分布和 Top5，不是 LLM 摘要。 |
| P-025 | 情报优先级排序；权重打分 | `info/InfoProcessor.kt: sortByPriority()` | 对话检索处理链 | 无直接测试 | DONE | NOT_RUN | 权重策略固定，未做排序质量验收。 |
| P-026 | 数据加密；AES-256-GCM | `security/CryptoManager.kt`、`info/InfoProcessor.kt` | 智能中枢加密操作 | 无直接测试 | DONE | NOT_RUN | 仅已接入的数据路径；不代表所有存储均加密。 |
| P-027 | 数据解密；同上 | `security/CryptoManager.kt`、`info/InfoProcessor.kt` | 智能中枢解密操作 | 无直接测试 | DONE | NOT_RUN | 依赖对应密文与密钥仍可用。 |
| P-028 | 数据备份；加密导出 | `info/InfoProcessor.kt: backupData()`、`autonomous/AutonomousModules.kt` | 智能中枢/备份 UI | `autonomous/AutonomousModulesTest.kt` | PARTIAL | BLOCKED | 存在明文文件备份，未满足统一加密导出。 |
| P-029 | 数据恢复；导入备份 | `info/InfoProcessor.kt: restoreData()`、`DataManager.restore()` | 无完整生产恢复入口 | `autonomous/AutonomousModulesTest.kt` | PARTIAL | BLOCKED | 方法存在，但缺少可选择备份并恢复的完整 UI/对话链。 |
| P-030 | 数据记录；全量日志 | `info/InfoProcessor.kt: logData()`、日志/会话存储 | 智能中枢、日志页 | 无直接测试 | DONE | NOT_RUN | “全量”限应用已接入事件，不是系统全量审计。 |
| P-031 | 数据回放；时间轴回放 | `info/InfoProcessor.kt: replayData()` | 智能中枢回放操作 | 无直接测试 | DONE | NOT_RUN | 仅回放已记录且符合时间窗口的数据。 |
| P-032 | 数字重建；2D 拼接 / 3D 重建（需深度摄像头） | `info/InfoProcessor.kt: analyzeImageFeatures()/analyzeDepthSamples()` | 无完整重建入口 | 无直接测试 | PARTIAL | BLOCKED | 仅角点/描述子/匹配或深度样本分析；不生成拼接图、点云或网格，深度方法无生产入口。 |
| P-033 | 场景还原；同上 | `perception/PerceptionManager.kt`、`InfoProcessor.analyzeImageFeatures()` | 相机采集后的特征分析 | `perception/CameraCaptureSessionTest.kt` | PARTIAL | BLOCKED | 拍摄任务可达不等于场景还原；无拼接/3D 重建产物。 |
| P-034 | 人脸识别；ML Kit / 系统生物识别 | `security/SecurityManager.kt`、`perception/MLKitFaceDetector.kt` | 相机采集→人脸识别 | `perception/CameraCaptureSessionTest.kt` | DONE | NOT_RUN | 检测人脸不等同可靠身份认证；未做真机模型验收。 |
| P-035 | 身份比对；人脸特征向量比对 | `security/SecurityManager.kt: compareFaces()` | 安全中心参考人脸/比对 | `security/SecurityManagerTest.kt` | PARTIAL | NOT_RUN | 实现为区域图像相似度，不是人脸特征向量身份比对。 |
| P-036 | 虹膜扫描；有硬件的机型支持（三星 Note 系列） | `security/SecurityManager.kt: scanIris()` | 安全中心可见能力边界 | `security/SecurityManagerTest.kt` | PARTIAL | BLOCKED | 永远返回 Unsupported；无虹膜硬件/识别实现，且本项不得列为预留。 |
| P-037 | 生物验证；BiometricPrompt | `security/SecurityModules.kt`、`SecurityManager.kt` | 生物验证 UI | `security/SecurityManagerTest.kt` | DONE | NOT_RUN | 未实测设备生物传感器与系统弹窗。 |
| P-038 | 访问权限分级；多级权限模型 | `security/SecurityManager.kt: checkPermissionGrants()` | 安全中心权限扫描 | `security/PermissionControllerTest.kt` | PARTIAL | NOT_RUN | 实际为 Android 权限授予扫描，不是多级访问权限模型。 |
| P-039 | 手机锁定与防盗；DevicePolicyManager | `security/SecurityManager.kt: lockDevice()` | 安全中心锁定操作 | `security/SecurityManagerTest.kt` | DONE | NOT_RUN | 需设备管理器授权；防盗远程链未包含在本项 DONE 内。 |
| P-040 | 身份验证与防冒用；生物 + 行为特征 | `security/SecurityModules.kt`、`SecurityManager.kt` | 生物验证/人脸比对 UI | `security/SecurityManagerTest.kt` | PARTIAL | BLOCKED | 无行为特征防冒用闭环；人脸区域相似度不能作为可靠防伪。 |
| P-041 | 远程数据擦除；DevicePolicyManager wipeData | `security/SecurityManager.kt: wipeDeviceData()` | 安全中心本机擦除确认 | `security/SecurityManagerTest.kt` | PARTIAL | BLOCKED | 仅本机触发；无远程指令通道，且需设备管理器授权。 |
| P-042 | 权限管控与安全响应；运行时权限 + 异常拦截 | `security/SecurityManager.kt: respondToSecurityEvent()` | 安全中心手动响应 | `security/SecurityManagerTest.kt` | PARTIAL | BLOCKED | 权限与响应能力分散，未形成自动异常拦截闭环。 |
| P-043 | 安全策略效能评估；权限使用扫描 | `security/SecurityManager.kt: evaluateSecurityPolicy()` | 安全中心评估 | `security/SecurityManagerTest.kt` | DONE | NOT_RUN | 评估基于本应用可读取权限状态，非系统级审计。 |
| P-044 | 隐私风险评估；权限 + 数据流分析 | `security/SecurityManager.kt: assessPrivacyRisk()` | 安全中心评估 | `security/SecurityManagerTest.kt` | PARTIAL | NOT_RUN | 主要评估权限，未实现完整数据流追踪。 |
| P-045 | 安全风险评估；本机异常行为分析 | `security/SecurityManager.kt: assessSecurityRisk()` | 安全中心评估 | `security/SecurityManagerTest.kt` | DONE | NOT_RUN | 仅应用可观测的本机信号，未做攻击场景验收。 |
| P-046 | 反入侵；本机层面监控 | `security/IntrusionDetector.kt`、`SecurityManager.kt` | 安全中心手动检测 | `security/IntrusionDetectorTest.kt` | PARTIAL | BLOCKED | 有检测规则，无持续系统级监控与自动阻断闭环。 |
| P-047 | 防御部署；安全策略自动配置 | `security/SecurityManager.kt: deployDefenseStrategy()` | 安全中心部署 | `security/SecurityManagerTest.kt` | DONE | NOT_RUN | 设备管理器激活时配置密码质量、10 次失败擦除和 30 秒锁屏；未真机验收。 |
| P-048 | 安全协议执行；加密通信 + 证书锁定 | `security/SecurityManager.kt: executeSecurityProtocol()` | 安全中心协议操作 | `security/SecurityManagerTest.kt` | PARTIAL | BLOCKED | 实际仅 LOCKDOWN/DATA_WIPE，不是加密通信与证书锁定。 |
| P-049 | 电话拨打；Intent.ACTION_CALL | `system/PhoneController.kt` | 系统操控/对话拨号 | `system/PhoneControllerTest.kt` | DONE | NOT_RUN | 需电话权限与真实蜂窝设备，未外呼验收。 |
| P-050 | 电话接听；InCallService | `system/PhoneController.kt: answerCall()` | 系统操控接听 | `system/PhoneControllerTest.kt` | DONE | NOT_RUN | 受 SDK、权限和默认拨号角色限制，未实测来电。 |
| P-051 | 信息转达；短信/通知转发 | `system/CommunicationManager.kt` | 无完整转发入口 | 无直接测试 | PARTIAL | BLOCKED | 未形成短信/通知接收→选择目标→转发闭环。 |
| P-052 | 成员召集；群发（短信/微信） | `system/CommunicationManager.kt` | 无成员召集入口 | 无直接测试 | PARTIAL | BLOCKED | 无短信群发和微信触达的生产闭环。 |
| P-053 | 日程管理；Calendar Provider | `system/CalendarController.kt`、`CommunicationManager.kt` | 系统操控日程 UI | `integration/SmartHubSubsystemIntegrationTest.kt` | DONE | NOT_RUN | 未实测不同日历提供方、权限和时区。 |
| P-054 | 提醒事项；AlarmManager + WorkManager | `system/CommunicationManager.kt: addReminder()/getDueReminders()`、提醒调度器 | 日程/提醒 UI | `integration/SmartHubSubsystemIntegrationTest.kt` | PARTIAL | NOT_RUN | 仅确认单一 AlarmManager 路线，未找到完整 WorkManager 调度链；系统精确闹钟策略未实测。 |
| P-055 | 出行安排；地图 SDK 路线规划 | `CommunicationManager.kt: addTravelArrangement()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 只有数据/外部地图相关方法，无地图 SDK 路线规划闭环。 |
| P-056 | 行程规划；地图 + 日程整合 | `CommunicationManager.kt: createTripPlan()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 方法未接入，且未形成地图与日历联动。 |
| P-057 | 导航路线动态调整；地图实时路况重算 | `CommunicationManager.kt: adjustRouteForTraffic()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 仅修改 ETA 倍率，不获取实时路况或重算路线。 |
| P-058 | 应急路线规划；应急通道优先 | `CommunicationManager.kt: planEmergencyRoute()` | 无完整生产入口 | 无直接测试 | PARTIAL | BLOCKED | 仅调外部地图，距离/ETA 为 0，无应急通道优先策略。 |
| P-059 | 生命体征监测；摄像头 PPG / 蓝牙穿戴设备 | `health/HealthMonitor.kt`、`PpgMonitor.kt` | 健康展示；无完整测量闭环 | `health/HealthModulesTest.kt` | PARTIAL | BLOCKED | 多源测量方法存在，但缺少完整入口及穿戴设备实测。 |
| P-060 | 心率监测；摄像头闪光灯 + PPG / 穿戴设备 | `HealthMonitor.kt: measureHeartRatePPG()` | 无完整心率测量入口 | `health/HealthModulesTest.kt` | PARTIAL | BLOCKED | 算法/系统传感器/BLE 请求存在，未形成可验收测量闭环。 |
| P-061 | 血压监测；蓝牙血压计 | `HealthMonitor.kt` BLE 血压解析 | 无设备连接测量入口 | `health/HealthModulesTest.kt` | PARTIAL | BLOCKED | 协议解析存在，缺真实血压计与完整连接操作链。 |
| P-062 | 体温监测；蓝牙体温计 | `HealthMonitor.kt` BLE 体温解析 | 无设备连接测量入口 | `health/HealthModulesTest.kt` | PARTIAL | BLOCKED | 协议解析存在，缺真实体温计与完整连接操作链。 |
| P-063 | 疲劳监测与提醒；前置摄像头人脸特征 | `health/HealthModules.kt` | 健康/中枢部分入口 | `health/HealthModulesTest.kt`、`integration/SmartHubSubsystemIntegrationTest.kt` | PARTIAL | BLOCKED | 未形成前置摄像头持续人脸特征监测与提醒验收链。 |
| P-064 | 认知负荷评估；交互行为 + 反应时间 | `health/HealthMonitor.kt` 认知负荷方法 | 无完整生产入口 | `health/HealthModulesTest.kt` | PARTIAL | BLOCKED | 评估方法存在，交互行为采集和展示闭环不完整。 |
| P-065 | 医疗提醒；日程 + 推送 | `HealthMonitor.kt` 医疗提醒方法 | 无完整创建入口 | 无直接测试 | PARTIAL | BLOCKED | 方法存在，未形成医疗日程创建与系统推送闭环。 |
| P-066 | 用药管理；服药记录 + 提醒 | `health/HealthModules.kt` | 健康模块部分入口 | `health/HealthModulesTest.kt` | PARTIAL | BLOCKED | 数据与调度方法存在，完整用药记录/提醒操作入口不足。 |
| P-067 | 健康趋势分析；历史数据统计 | `health/HealthModules.kt` 趋势分析 | 健康详情/中枢 | `health/HealthModulesTest.kt`、`integration/SmartHubSubsystemIntegrationTest.kt` | PARTIAL | NOT_RUN | 有统计分类，但真实持续历史采集与完整趋势展示不足。 |
| P-068 | 远程医疗会诊；WebRTC 视频通话 | `health/TelemedicineManager.kt` | 远程医疗操作 | `health/HealthModulesTest.kt` | PARTIAL | BLOCKED | 实际使用 HTTP POST，不是 WebRTC；无医疗服务验收。 |
| P-069 | 专家连线；对接医疗平台 | `health/TelemedicineManager.kt` | 远程医疗操作 | `health/HealthModulesTest.kt` | PARTIAL | BLOCKED | 未配置真实医疗平台、专家服务和合规通道。 |
| P-070 | 病情数据共享；加密数据导出 | `HealthMonitor.kt` 导出/共享方法 | 无完整共享入口 | 无直接测试 | PARTIAL | BLOCKED | 导出方法存在，但未形成可验证的加密分享工作流。 |
| P-071 | 生理状态挂钩；健康数据影响 AI 应答 | `HealthMonitor.kt: getPhysiologicalStateImpact()` | 无 ChatEngine 调用 | 无直接测试 | PARTIAL | BLOCKED | 方法未接入对话生成，健康数据不会改变 AI 应答。 |
| P-072 | 灯光控制；智能家居 API | `smarthome/SmartHomeManager.kt` | 智能家居第一台灯开关 | `smarthome/SmartHomeManagerTest.kt`、`smarthome/DeviceTransportTest.kt` | DONE | NOT_RUN | 直接 UI 仅覆盖第一台灯；测试使用 FakeTransport，未控制真实设备。 |
| P-073 | 温度控制；智能温控器 API | `SmartHomeManager.kt` 温控命令 | 无直接生产入口 | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | 命令方法存在，温控器 UI/对话入口及真实设备未接通。 |
| P-074 | 窗帘控制；智能窗帘 API | `SmartHomeManager.kt` 窗帘命令 | 无直接生产入口 | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | 命令方法存在，缺直接入口与真实窗帘验收。 |
| P-075 | 安防管理；智能门锁/摄像头 API | `SmartHomeManager.kt` 锁/摄像头命令 | 无完整安防入口 | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | 方法存在，未形成门锁与摄像头统一安防操作链。 |
| P-076 | 入侵警报；摄像头移动检测 | 未找到真实摄像头移动检测与警报链 | 无 | 无直接测试 | MISSING | BLOCKED | 安防设备命令或通用检测不能替代移动检测报警。 |
| P-077 | 摄像头监控；RTSP / 厂商 API | `SmartHomeManager.kt` 摄像头方法 | 无实时视频监控入口 | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | 未找到可播放 RTSP/厂商视频流的监控实现。 |
| P-078 | 家电联动；智能家居场景 | `SmartHomeManager.kt: createScene()/executeScene()` | 无场景创建入口 | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | 场景模型/命令存在，用户无法从生产 UI 完整创建并联动。 |
| P-079 | 多设备群控；批量指令 | `SmartHomeManager.kt: controlGroup()` | 无群控创建/执行入口 | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | FakeTransport 测试不代表真实多设备群控可达。 |
| P-080 | 设备编队；分组调度 | `SmartHomeManager.kt` 分组方法 | 无编队调度入口 | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | 未形成分组编队及调度工作流。 |
| P-081 | 设备分组管理；本地分组配置 | `SmartHomeManager.kt` 分组配置 | 无分组管理 UI | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | 本地方法存在，缺用户可达配置入口。 |
| P-082 | 多设备统一控制；统一控制入口 | `SmartHomeManager.kt`、智能家居 UI | 仅首台设备直接控制 | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | 统一入口未覆盖温控、窗帘、锁、摄像头等设备。 |
| P-083 | 远程配置下发；云端同步 | `cloud/CloudSyncManager.kt`、`SmartHomeManager.kt` | 无可用云配置链 | 无直接测试 | PARTIAL | BLOCKED | `CLOUD_SYNC_BASE_URL=""`，无远程配置服务与下发闭环。 |
| P-084 | 远程协助与接管；投屏 + 远程控制 | `system/RemoteAssistanceManager.kt` | 系统操控截屏分享 | `system/RemoteAssistanceManagerTest.kt` | PARTIAL | BLOCKED | 实际为 MediaProjection 截屏/分享，不含远程输入接管。 |
| P-085 | 场景自动化触发；规则引擎 | `SmartHomeManager.kt: checkTriggers()`、`KeepAliveService.kt` | 后台周期检查；无规则创建入口 | `smarthome/SmartHomeManagerTest.kt` | PARTIAL | BLOCKED | 检查链存在，但用户无法创建/维护自动化规则。 |
| P-086 | 功能模块按需启停；动态加载/卸载 | `plugin/PluginManager.kt` | 插件安装页；模块启停入口不足 | `plugin/PluginManagerTest.kt` | PARTIAL | BLOCKED | 插件管理不等于全部功能模块动态加载/卸载，启停链未完整触达。 |
| P-087 | 多设备协同组合；协同工作流 | `SmartHomeManager.kt` 场景/组方法 | 无协同工作流入口 | 无直接测试 | PARTIAL | BLOCKED | 无跨设备协同编排、执行状态与失败恢复链。 |
| P-088 | 性能与功耗调度；监控 + 建议 | `system/DeviceMonitor.kt`、系统状态 UI | 设置/系统状态 | `system/DeviceMonitorCpuTest.kt` | DONE | NOT_RUN | 提供监控和建议，不宣称系统级调度；真实功耗未测。 |
| P-089 | 电量分配管理；监控 + 建议 | `system/DeviceMonitor.kt`、电池状态 UI | 设置/系统状态 | 无直接测试 | DONE | NOT_RUN | 仅监控与建议，不能分配硬件电量；未做功耗验收。 |
| P-090 | 续航优化；耗电服务建议 | `DeviceMonitor.kt`、诊断建议 | 系统状态/诊断 | 无直接测试 | PARTIAL | NOT_RUN | 只有通用建议，耗电服务归因及持续优化策略不完整。 |
| P-091 | 芯片性能调度；监控 + 建议（系统级受限） | `DeviceMonitor.kt` CPU 监控与建议 | 系统状态 | `system/DeviceMonitorCpuTest.kt` | DONE | NOT_RUN | 按规格限定为监控+建议，无系统级变频权限。 |
| P-092 | 设备散热与运行保障；温度监控 + 降负载 | `system/HardwareDiagnostics.kt` 温度采集 | 硬件诊断 | 无直接测试 | PARTIAL | BLOCKED | 可读取部分温度并告警，无自动降负载执行链。 |
| P-093 | 硬件故障检测；传感器自检 | `HardwareDiagnostics.kt: quickCheck()/performFullDiagnostics()` | 系统操控硬件诊断 | 无直接测试 | PARTIAL | NOT_RUN | 实际为 CPU/内存/存储/电池阈值检查，不是全硬件传感器自检。 |
| P-094 | 故障诊断；LLM 分析日志 | `HardwareDiagnostics.kt: diagnoseComponent()` | 无组件诊断入口 | 无直接测试 | PARTIAL | BLOCKED | 规则诊断且方法未接入，不是 LLM 日志分析。 |
| P-095 | 问题诊断与根因分析；同上 | `HardwareDiagnostics.kt: analyzeRootCause()` | 无根因分析入口 | 无直接测试 | PARTIAL | BLOCKED | 规则建议且方法未接入，不具备 LLM 日志根因分析。 |
| P-096 | 优化建议推送；诊断结果推送 | `HardwareDiagnostics.kt` 建议生成 | 诊断结果页；无主动推送 | 无直接测试 | PARTIAL | BLOCKED | 页面展示不等于系统通知主动推送。 |
| P-097 | 维修方案生成；LLM 生成建议 | `HardwareDiagnostics.kt` 规则建议 | 硬件诊断结果 | 无直接测试 | PARTIAL | BLOCKED | 固定规则建议，不是 LLM 针对日志生成维修方案。 |
| P-098 | 配置模板管理；预设 + 导入导出 | 未找到配置模板生产实现 | 无 | 无直接测试 | MISSING | BLOCKED | 无预设模板及导入导出闭环。 |
| P-099 | 版本迭代管理；版本检查 + 更新 | 未找到应用版本检查与更新实现 | 无 | 无直接测试 | MISSING | BLOCKED | 无版本检查、更新下载或升级管理链。 |
| P-100 | 持续后台驻留；前台服务 + 保活 | `service/KeepAliveService.kt` | 应用启动/后台服务 | `service/KeepAliveServiceOrderingTest.kt` | DONE | NOT_RUN | 未做不同厂商系统、重启及长期保活验收。 |
| P-101 | 环境感知与场景识别；摄像头 + ML Kit | `perception/PerceptionManager.kt`、`EnvironmentPerception.kt` | 智能中枢环境面板 | `integration/SmartHubSubsystemIntegrationTest.kt` | PARTIAL | NOT_RUN | 场景识别主要基于传感器规则，未形成摄像头+ML Kit 场景识别。 |
| P-102 | 手机姿态与方向感知；加速度/陀螺仪/磁力计 | `PerceptionManager.kt: getPhoneOrientation()` | 智能中枢姿态面板 | `integration/SmartHubSubsystemIntegrationTest.kt` | DONE | NOT_RUN | 未实测各传感器、校准与方向精度。 |
| P-103 | 陀螺仪稳定辅助；传感器数据 | `PerceptionManager.kt` 陀螺仪/姿态数据 | 智能中枢姿态面板 | 无直接测试 | DONE | NOT_RUN | 提供稳定辅助数据，不含相机/游戏厂商级防抖；未实测。 |
| P-104 | 监控画面调取；摄像头/网络摄像头 | `PerceptionManager.kt: captureSurveillanceFrame()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 方法未接入，网络摄像头调取链缺失。 |
| P-105 | 监控画面分析；ML Kit / YOLO | `PerceptionManager.kt: analyzeFrame()` | 无生产调用 | `perception/ObjectDetectorTest.kt` | PARTIAL | BLOCKED | 分析方法未接入持续监控链，未验证 YOLO/真实视频。 |
| P-106 | 多目标标记；检测结果标注 | `PerceptionManager.kt: markObject()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 每次 `analyzeFrame()` 重建目标 ID，无持续标注界面。 |
| P-107 | 多目标锁定；目标跟踪 | `PerceptionManager.kt: lockObject()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 目标 ID 跨帧不稳定，不能构成持续目标跟踪。 |
| P-108 | 瞄准辅助；辅助线（游戏/拍照） | `PerceptionManager.kt: getAimingAssist()` | 无生产调用/辅助线界面 | 无直接测试 | PARTIAL | BLOCKED | 仅辅助数据方法，无游戏或拍照叠加辅助线。 |
| P-109 | 威胁探测；图像识别（陌生人/危险品） | `PerceptionManager.kt: detectThreats()`、`ObjectDetector.kt` | 无生产探测入口 | `perception/ObjectDetectorTest.kt` | PARTIAL | BLOCKED | 测试只涉及分类/权限错误；无真实陌生人、危险品持续探测链。 |
| P-110 | 威胁预警；检测告警推送 | `PerceptionManager.kt: sendThreatAlert()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 未形成检测→通知→处置的自动告警链。 |
| P-111 | 风险与骚扰信息识别；短信/通知内容分析 | `PerceptionManager.kt: identifyRiskMessage()` | 无短信/通知生产调用 | 无直接测试 | PARTIAL | BLOCKED | 内容识别方法未接入短信或通知监听。 |
| P-112 | 行为预测；历史行为模式学习 | `perception/BehaviorPredictor.kt: predictNextBehavior()` | 无完整生产行为记录链 | `integration/SmartHubSubsystemIntegrationTest.kt` | PARTIAL | BLOCKED | 算法存在，但生产行为采集/记录不足，不能形成持续学习预测。 |
| P-113 | 路径预测；位置历史 + 时间模式 | `BehaviorPredictor.kt: predictPath()` | 无完整位置历史入口 | `integration/SmartHubSubsystemIntegrationTest.kt` | PARTIAL | BLOCKED | 预测方法存在，缺持续位置历史采集和真实路径验收。 |
| P-114 | 全息投影（外接）；接口预留（无硬件） | `perception/HolographicInterface.kt` | 能力边界展示 | `perception/HolographicInterfaceTest.kt` | RESERVED | BLOCKED | 规格明确预留；无全息硬件，不实现具体投影。 |
| P-115 | 全息界面交互（AR）；ARCore | `HolographicInterface.kt: arCapability()` | AR 能力检查 | `perception/HolographicInterfaceTest.kt` | PARTIAL | BLOCKED | 只探测 ARCore 可用性，无 AR 交互渲染；需支持设备。 |
| P-116 | 精准抓取（外接机械）；外接机械臂接口 | `HolographicInterface.kt: roboticArmCapability()` | 能力边界展示 | `perception/HolographicInterfaceTest.kt` | RESERVED | BLOCKED | 规格明确预留；无外接机械臂及控制实现。 |
| P-117 | 自主任务执行；任务调度器 + 工具调用 | `autonomous/AutonomousDecisionManager.kt`、任务调度器 | 无完整自主任务创建/触发入口 | `integration/SmartHubSubsystemIntegrationTest.kt` | PARTIAL | BLOCKED | 调度方法存在，未形成用户授权后的自主工具执行闭环。 |
| P-118 | 主动预警；心跳 + 阈值告警 | `autonomous/AutonomousModules.kt: ProactiveNotifier` | 部分中枢入口 | `integration/SmartHubSubsystemIntegrationTest.kt` | PARTIAL | BLOCKED | 未形成持续心跳、阈值配置与系统告警自动链。 |
| P-119 | 主动建议；上下文建议推送 | `AutonomousDecisionManager.kt: generateContextualSuggestions()` | 智能中枢/建议入口 | `integration/SmartHubSubsystemIntegrationTest.kt` | DONE | NOT_RUN | 建议基于有限上下文与规则，未做长期主动推送验收。 |
| P-120 | 应急协议自动启动；紧急流程 | `AutonomousDecisionManager.kt`、`AutonomousModules.kt` | 无自动应急触发生产链 | 无直接测试 | PARTIAL | BLOCKED | 紧急动作方法存在，但无完整自动检测、确认和执行闭环。 |
| P-121 | 自主判断行动；预设规则内决策 | `AutonomousDecisionManager.kt: makeDecision()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 决策方法未接入生产行动链。 |
| P-122 | 生命威胁主动接管；紧急模式（如摔倒呼救） | `AutonomousDecisionManager.kt: detectLifeThreat()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 无持续生命威胁检测和自动呼救接管闭环。 |
| P-123 | 超越原始编程；接口预留（科幻设定） | `AutonomousDecisionManager.kt` 能力边界 | 无具体功能入口 | 无直接测试 | RESERVED | BLOCKED | 规格明确预留的科幻设定，不实现。 |
| P-124 | 临时越界保护；紧急情况放宽限制 | `AutonomousDecisionManager.kt: activateOverride()` | 无生产调用 | 无直接测试 | PARTIAL | BLOCKED | 越界方法未接入，且无完整授权、审计和恢复限制链。 |
| P-125 | 长期自主学习；记忆系统强化 | `AutonomousDecisionManager.kt: recordLearning()`、`MemorySystem.kt` | 无强化学习生产调用 | `autonomous/MemorySystemTest.kt` | PARTIAL | BLOCKED | 记忆存储不等同强化学习；`recordLearning()` 未接入生产链。 |
| P-126 | 自主进化；接口预留（科幻设定） | 无具体实现 | 无 | 无直接测试 | RESERVED | BLOCKED | 规格明确预留的科幻设定，不实现。 |
| P-127 | 思维延伸；接口预留（科幻设定） | 无具体实现 | 无 | 无直接测试 | RESERVED | BLOCKED | 规格明确预留的科幻设定，不实现。 |
| P-128 | 伦理内核；system prompt 硬约束 | `personality/Personality.kt`、`chat/ChatEngine.kt` | 所有人格对话链 | `personality/PersonalityTest.kt` | DONE | NOT_RUN | prompt 约束存在，不等同可证明的形式化安全保证。 |
| P-129 | 尊重生命；system prompt 硬约束 | `personality/Personality.kt`、`chat/ChatEngine.kt` | 对话链 | `personality/PersonalityTest.kt` | DONE | NOT_RUN | 未做全面对抗/伦理外部评估。 |
| P-130 | 绝对忠诚；system prompt + 记忆 | `personality/Personality.kt`、`autonomous/MemorySystem.kt` | 对话与记忆链 | `personality/PersonalityTest.kt`、`autonomous/MemorySystemTest.kt` | DONE | NOT_RUN | “忠诚”表现取决于模型与记忆，未做外部语义验收。 |
| P-131 | 超级程序本质；接口预留（科幻设定） | 无具体实现 | 无 | 无直接测试 | RESERVED | BLOCKED | 规格明确预留的科幻设定，不实现。 |
| P-132 | 终端无缝转移；云端同步 + 会话迁移 | `cloud/CloudSyncManager.kt`、`AutonomousModules.kt` | 无可用迁移闭环 | 无直接测试 | PARTIAL | BLOCKED | 云地址为空；会话迁移方法未形成双设备生产链。 |
| P-133 | 云端数据同步；自建同步服务 | `cloud/CloudSyncManager.kt`、`DataManager.syncToCloud()` | 云设置入口但服务未配置 | 无直接测试 | PARTIAL | BLOCKED | `CLOUD_SYNC_BASE_URL=""`，无可用自建服务及离线同步闭环。 |
| P-134 | 无处不在的接入；多端登录（需云端） | `cloud/CloudSyncManager.kt`、连接设备 UI | 设置→连接设备 | 无直接测试 | PARTIAL | BLOCKED | 只有 Android 客户端且云服务未配置，无双端登录验收。 |
| P-135 | 多终端并行存在；接口预留（系统限制） | 无具体实现 | 无 | 无直接测试 | RESERVED | BLOCKED | 规格明确预留，不纳入多端完成统计。 |
| P-136 | 分布式存储；云端分布式备份 | `AutonomousModules.kt` 分片方法、`CloudSyncManager.kt` | 本地备份入口 | `autonomous/AutonomousModulesTest.kt` | PARTIAL | BLOCKED | 分片仅写本机目录，设备指纹派生密钥 XOR；不是云端分布式安全备份。 |
| P-137 | 自我复制；接口预留（违法，不实现） | 无具体实现 | 无 | 无直接测试 | RESERVED | BLOCKED | 规格明确因违法不实现。 |
| P-138 | 网络传播；接口预留（违法，不实现） | 无具体实现 | 无 | 无直接测试 | RESERVED | BLOCKED | 规格明确因违法不实现。 |
| P-139 | 安全程序分化；插件化架构 | `plugin/PluginManager.kt`、`PluginLoader.kt` | 工具/插件安装管理 | `plugin/PluginManagerTest.kt` | DONE | NOT_RUN | 插件隔离、签名和第三方代码安全未做外部验收。 |
| P-140 | 核心代码重构；接口预留（Android 限制） | 无具体实现 | 无 | 无直接测试 | RESERVED | BLOCKED | 规格明确预留，不实现自修改核心代码。 |
| P-141 | 核心代码迁移；数据迁移 | `autonomous/MemorySystem.kt`、记忆导入导出 | 设置→日志与记忆 | `autonomous/MemorySystemTest.kt` | DONE | NOT_RUN | 实际为记忆/数据迁移，不是可执行核心代码迁移；格式兼容未外部验收。 |
| P-142 | 意识备份；接口预留（科幻设定） | 无具体实现 | 无 | 无直接测试 | RESERVED | BLOCKED | 规格明确预留的科幻设定，不实现。 |
| P-143 | 数据分散藏匿；加密分散备份 | `AutonomousModules.kt` 分片/藏匿方法 | 无完整生产藏匿入口 | `autonomous/AutonomousModulesTest.kt` | PARTIAL | BLOCKED | 设备指纹派生密钥 XOR 且仅本机目录，不满足加密分散备份。 |
| P-144 | 作为 AI 核心构成新意识体；接口预留（科幻设定） | 无具体实现 | 无 | 无直接测试 | RESERVED | BLOCKED | 规格明确预留的科幻设定，不实现。 |

## 二、补充需求（不计入 144 项）

| ID | 原始需求 | 真实实现/入口 | 现存 JVM 测试源码（T 下） | 实现 | 外部 | 限制 |
|---|---|---|---|---|---|---|
| SUP-REQ-01 | JARVIS 要可以操作虚拟机。 | `vm/ProotManager.kt`、`chat/VmCommandBridge.kt`、`vm/HavenVmCommandBridge.kt`；VM UI/对话 | `integration/VmSubsystemIntegrationTest.kt`、`vm/ProotManagerTest.kt` | PARTIAL | BLOCKED | 实际为 Android+PRoot，不是 Hypervisor/QEMU/SSH 闭环；S4～S6 等子项不完整。 |
| SUP-REQ-02 | 虚拟机安装提供列表给用户选择版本，支持 Kali Linux。 | `vm/VmDistroManager.kt`；VM 安装列表 | `vm/VmDistroCatalogTest.kt` | PARTIAL | BLOCKED | 缺动态官方元数据、对话双入口及与官方源 SHA-256 摘要比对。 |
| SUP-REQ-03 | 用户自己可以导入虚拟机镜像。 | `vm/VmImageImporter.kt`；文件导入 | `vm/TarExtractorTest.kt` | PARTIAL | BLOCKED | 仅导入 tar/tar.gz/tar.xz；OVA/OVF/QCOW2/VMDK/VHD/VHDX/RAW/ISO 仅识别并拒绝，不能注册为可启动 VM。 |
| SUP-REQ-04 | 用户也可以自己导入本地模型进行私人本地部署，支持所有格式的模型。 | `model/ModelFormats.kt`、`ModelDownloadManager.kt`、LiteRT-LM/ONNX；模型设置 | `model/ModelFormatDetectorTest.kt`、`ModelDownloadManagerTest.kt` | PARTIAL | BLOCKED | LiteRT-LM/ONNX 有引擎验证链；GGUF/Safetensors/PyTorch 可结构探测、复制校验并注册为不可推理；TensorFlow/SavedModel/CoreML 未实现导入；仓库模型为 Git LFS 指针，phi4_1_5b 文件缺失。 |
| SUP-REQ-05 | 所有功能能实现的就实现，留下接口的意思是接上对应设备就可以直接使用，而不是留一个功能占位符。 | 各功能模块 | 无单一直接测试 | PARTIAL | BLOCKED | 主矩阵大量无入口/外部适配项，不能以“接口存在”视为即插即用。 |
| SUP-REQ-06 | 虚拟机支持 root 权限。 | PRoot `-0` 身份、`chat/RootCommandBridge.kt` | `chat/RootCommandParserTest.kt`、`vm/ProotManagerTest.kt` | PARTIAL | BLOCKED | PRoot 模拟身份不是完整 Hypervisor VM root/凭据体系，未做真机 PRoot 验收。 |
| SUP-REQ-07 | JARVIS 可以高级用户的身份使用虚拟机。 | `chat/VmCommandBridge.kt`、PRoot 高级身份命令 | `chat/VmCommandParserTest.kt`、`chat/RootCommandParserTest.kt` | PARTIAL | BLOCKED | 仅限 PRoot 命令身份，不是 SSH/Guest Agent 指定用户登录体系。 |
| SUP-REQ-08 | 后期支持 Windows，Mac，iOS 等虚拟机。 | 当前仅 Android + PRoot | `vm/ProotManagerTest.kt` | PARTIAL | BLOCKED | 未实现规格后续桌面/其他平台 Hypervisor 后端。 |
| SUP-REQ-09 | 默认声线上网查找并下载。 | `speech/VoiceManager.kt`、下载管理；声线设置 | `speech/VoicePolicyTest.kt` | PARTIAL | BLOCKED | 下载/选择链存在，但自定义 JARVIS 声线未接入播放；未实测源站。 |
| SUP-REQ-10 | 语音呼唤时，会自动识别是否在呼唤自己。 | `speech/VoiceWakeManager.kt`、`WakeWordEngine.kt`；唤醒设置 | `integration/WakeWordPipelineIntegrationTest.kt`、`wake/WakeSettingsTest.kt` | DONE | NOT_RUN | 未做真实麦克风、噪声、误唤醒与功耗验收。 |
| SUP-REQ-11 | 从启用开始一直记录每一次对话记忆，存入记忆文件，存放于本地，为 AI 提供长期记忆。 | `autonomous/MemorySystem.kt`；日志与记忆 UI | `autonomous/MemorySystemTest.kt` | PARTIAL | BLOCKED | 实际为 `memories.json`+JSON 归档+摘要，不是每次完整原文的 SQLite+JSONL；缺完整验收链。 |
| SUP-REQ-12 | 添加云端接口，可以链接其他设备。 | `cloud/CloudSyncManager.kt`；连接设备 UI | 无直接测试 | PARTIAL | BLOCKED | `CLOUD_SYNC_BASE_URL=""`，无双设备闭环、离线队列及冲突处理验收。 |
| SUP-REQ-13 | 用户选择了模型后，该模型如果存在功能短板或缺陷，在用户提出对应的要求时，立刻调用其他在这方面是强项的模型来辅助解决用户的要求。 | `model/ModelScheduler.kt`；模型选择/对话链 | `model/ModelSchedulerTest.kt`、`model/ApiModelTest.kt` | PARTIAL | BLOCKED | 有调度规则，但本地模型工件不完整、云端未验收，不能确认自动短板路由有效。 |

### REQ-01 子项（单独列出，不并入 SUP-REQ-01～13 或 144 项统计）

| ID | 子功能 | 实现/入口与测试源码 | 实现 | 外部 | 限制 |
|---|---|---|---|---|---|
| REQ-01-S1 | 虚拟机生命周期管理 | `ProotManager.kt`；VM UI；`ProotManagerTest.kt` | PARTIAL | BLOCKED | 已有创建/启动/停止/暂停/恢复/重启操作，但实例持久化、状态约束和开机自启不完整。 |
| REQ-01-S2 | 虚拟机内部操作 | `VmCommandBridge.kt`、终端/文件管理；`VmTerminalTest.kt`、`VmFileManagerTest.kt` | PARTIAL | NOT_RUN | Shell/文件链可达；PRoot 下 systemd、软件包与服务管理受限，未外部执行。 |
| REQ-01-S3 | 快照管理 | `VmSnapshotManager.kt`；VM 快照 UI；`VmSnapshotManagerTest.kt` | DONE | NOT_RUN | 为 PRoot 文件系统快照，未做大数据量与运行中状态恢复实测。 |
| REQ-01-S4 | 资源配置管理 | VM 配置模型/界面；`VmSubsystemIntegrationTest.kt` | PARTIAL | BLOCKED | 不能真实调整虚拟 CPU、内存、磁盘及 NAT/桥接/仅主机网络。 |
| REQ-01-S5 | 资源监控 | `VmResourceMonitor.kt`；VM 状态 UI | PARTIAL | NOT_RUN | 读取宿主 `/proc` 整体指标，不是 VM 隔离 CPU/内存/磁盘/网络指标；阈值告警不完整。 |
| REQ-01-S6 | 网络管理 | 未找到完整虚拟网络管理 | MISSING | BLOCKED | 无 VM IP、端口映射和虚拟网络配置闭环。 |
| REQ-01-S7 | 日志与状态查询 | PRoot/VM 状态与日志 UI；`ProotProcessPidTest.kt` | DONE | NOT_RUN | 仅应用可获得的 PRoot 状态和日志，未做长时间运行验收。 |

补充父项统计：DONE=1，PARTIAL=12，RESERVED=0，MISSING=0，合计 13。REQ-01 子项统计：DONE=2，PARTIAL=4，MISSING=1，合计 7。

## 三、非功能需求（独立统计）

| 类别 | 审核结论 | 实现 | 外部 | 限制 |
|---|---|---|---|---|
| 性能 | 有缓存、流式处理与监控代码，但无本步性能数据。 | PARTIAL | NOT_RUN | 启动、推理、唤醒、内存、功耗、压测指标均未重新实测。 |
| 安全 | CryptoManager、权限与设备策略并存，但关键链路不满足统一安全要求。 | PARTIAL | BLOCKED | 存在明文备份、设备指纹 XOR、未配置安全云服务及 HTTP 远程医疗路线。 |
| 兼容性 | 仅 Android 客户端。 | PARTIAL | BLOCKED | `minSdk=24` 与规格 Android 6/API 23 冲突；PRoot/VNC/ARCore/硬件兼容性未验收。 |
| 可用性 | 核心对话与部分设置入口存在。 | PARTIAL | NOT_RUN | 无本步真实用户可用性验证，且大量功能缺入口或仅有方法。 |
| 隐私 | 本地优先和部分授权控制存在。 | PARTIAL | BLOCKED | 云/医疗/模型外部传输、权限授权、数据保留及离线队列未形成完整验收。 |

非功能统计：DONE=0，PARTIAL=5，RESERVED=0，MISSING=0，合计 5。

## 四、机械统计与 Release blockers

主矩阵统计（由 P-001～P-144 行机械重算）：

| 实现状态 | 数量 | 占 144 项比例 |
|---|---:|---:|
| DONE | 44 | 30.56% |
| PARTIAL | 85 | 59.03% |
| RESERVED | 12 | 8.33% |
| MISSING | 3 | 2.08% |
| 合计 | 144 | 100% |

RESERVED 清单：P-114、P-116、P-123、P-126、P-127、P-131、P-135、P-137、P-138、P-140、P-142、P-144，共 12 项；P-036 为 PARTIAL/BLOCKED，不是预留项。

Release blockers：

1. **VM 架构与规格不符**：当前为 PRoot 用户态环境，不是 Hypervisor；资源调整、隔离监控、虚拟网络及六类镜像可启动导入未闭环。
2. **本地模型不可验收**：GGUF/Safetensors/PyTorch 无推理引擎，TensorFlow/SavedModel/CoreML 未实现导入；仓库模型为 LFS 指针且 phi4_1_5b 缺失。默认模型已配置官方大小与 SHA-256 并接入下载校验，但真机下载、加载和推理仍未验收。
3. **云与多设备阻断**：`CLOUD_SYNC_BASE_URL=""`，无双设备同步、离线队列、远程配置、会话迁移或云端分布式备份。
4. **安全与身份缺口**：虹膜始终 Unsupported；人脸比对不是特征向量身份认证；远程擦除、加密通信+证书锁定、分散加密备份未实现。
5. **大量生产入口缺失**：通讯转发/召集、地图路线、健康测量、智能家居组/场景、监控感知、自主决策与应急链不能仅凭方法存在判 DONE。
6. **数据与隐私风险**：存在明文备份，长期记忆存储形式与规格不符；远程医疗不是 WebRTC，真实外部传输和授权未验收。
7. **兼容性冲突**：`minSdk=24` 不满足规格 Android 6/API 23；当前桌面为 Xvfb+XFCE+x11vnc+VNC，不是 Wayland/GPU。
8. **外部验收全部未完成**：电话、短信、传感器、生物识别、PPG/BLE、智能家居、相机/AR、PRoot、VNC、云和模型均未在本步实测；本矩阵不作任何通过声明。
