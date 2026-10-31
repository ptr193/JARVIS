# JARVIS 阶段一测试报告（实现 + 单元/集成测试）

> 对应《JARVIS_补充计划.md》第 1 节与第 6 节、`docs/实施计划.md` 阶段 1。
> 最近复测：2026-10-31 | 环境：MonkeyCode 沙箱 Linux x86_64，
> JDK 17 `/usr/lib/jvm/java-17-openjdk-amd64` + Gradle 8.11.1 wrapper + Android SDK 35 `/opt/android-sdk`。
> 历史记录：2026-10-20 Haven PRoot (Debian) on Android aarch64，JDK 17 `/opt/jdk` + Gradle 8.9。

---

## 1. 测试概览

| 项目 | 状态 | 详情 |
|------|------|------|
| 全量编译（Debug） | ✅ 通过 | `assembleDebug` BUILD SUCCESSFUL（EXIT=0） |
| 全量编译（Release） | ⏸️ 未执行 | 按计划 §5.2/§6.2 门槛，实机测试前禁止打包 Release |
| 单元测试 | ✅ 通过 | 见下表 |
| 集成测试 | ✅ 通过 | 4 个集成测试类 |
| **测试合计** | ✅ **77/77 通过** | 15 个测试类，0 失败 0 错误 0 跳过（2026-10-31 复测） |
| Debug APK 产出 | ✅ 218,738,213 B (~208.6 MB) | `app/build/outputs/apk/debug/app-debug.apk` |
| 实机测试 | ❌ 未执行 | 环境无真机/模拟器（卡点见 `process_compliance.md`） |
| 模型冒烟测试 | ❌ 未执行 | 需真机加载 LiteRT 模型 |

---

## 2. 测试明细

### 2.1 单元测试（11 类）

| 测试类 | 覆盖模块 |
|--------|----------|
| ChatMessageTest | 对话消息、会话、角色枚举 |
| PersonalityTest | 人格默认值、系统提示词、禁忌清单、夜间模式 |
| AutonomousModulesTest | 任务调度、记忆系统、应急协议、主动预警 |
| VmManagerTest | 虚拟机创建/启动/停止/删除、多机独立运行 |
| SmartHomeAdapterTest | 设备增删、分组群控、场景自动化 |
| PermissionControllerTest | 主人/访客/临时权限分级 |
| HealthModulesTest | 疲劳检测、用药管理、生命体征、趋势分析 |
| ObjectDetectorTest | 目标检测、威胁分级 |
| ModelFormatDetectorTest | 模型格式识别 |
| KeepAliveServiceOrderingTest | 保活服务启动顺序 |
| StartupHardeningTest | 启动加固 |

### 2.2 集成测试（4 类，本轮新增 1 类）

| 测试类 | 用例 | 覆盖链路 |
|--------|------|----------|
| WakeWordPipelineIntegrationTest | 5 | MFCC 特征 → VAD → 静音裁剪 → DTW 匹配/拒绝 |
| VmSubsystemIntegrationTest | 4 | VM 生命周期 + Haven 类型 + 配置应用 + root/审计 |
| ConversationPipelineIntegrationTest | 4 | 人格原则 → 消息流 → 上下文窗口 → 100+ 语言 |
| **SmartHubSubsystemIntegrationTest（新增）** | **5** | **行为预测、任务调度优先级、主动预警发射、健康趋势分类、疲劳检测** |

集成测试断言均为多模块端到端串联（例如：记录 5 条行为 → 统计分布 → 预测置信度落在 [0,1]）。

---

## 3. 结果数据

```
2026-10-31 沙箱复测
./gradlew testDebugUnitTest --no-daemon --max-workers=2
BUILD SUCCESSFUL in 5m 33s
tests=77 failures=0 errors=0 skipped=0
测试结果目录：app/build/test-results/testDebugUnitTest/（15 个 XML）

./gradlew assembleDebug --no-daemon --max-workers=2
BUILD SUCCESSFUL in 2m 37s
APK：app/build/outputs/apk/debug/app-debug.apk  218,738,213 B
```

构建调整（仅本沙箱可编译所需）：
- Gradle wrapper 8.5 → 8.11.1，匹配当前 AGP/Kotlin
- `org.gradle.jvmargs=-Xmx1536m`，避免 4G 堆在 cgroup 限制下 OOM
- 生成 `/root/.android/debug.keystore` 后 `validateSigningDebug` 通过

---

## 4. 本轮修复的问题（详见 bug_report.md）

| 编号 | 描述 | 级别 |
|------|------|------|
| BUG-2026-1017 | 40+ 功能实现后无界面入口（dead code），违反 REQ-05 | P1 |
| BUG-2026-1018 | `ConnectedDevicesTab` 设备列表为硬编码/无实际云端调用 | P1 |
| BUG-2026-1019 | `LogTab` 最近日志为硬编码假日志 | P1 |
| BUG-2026-1020 | `LanguageVoiceTab` 声线列表为硬编码，未接 `VoiceManager` | P1 |
| BUG-2026-1021 | `TranslationEngine`/`InfoProcessor` 无调用方 | P1 |
| BUG-2026-1022 | 记忆只能导出不能导入（#141 数据迁移缺失） | P1 |

---

## 5. 未覆盖项（需实机测试）

- 8 种唤醒方式的实际触发
- 前台服务保活效果
- 运行时权限请求流程
- 4 个 LiteRT 模型的加载与推理性能
- 系统控制（电话/短信/日程/导航）实际调用
- 语音识别与 TTS 实际效果
- 智能中枢 / 安全中心 / 系统操控 3 个新页签的真机交互

---

*文档版本：v2.1 | 生成日期：2026-10-31 | 上一版本：v2.0（2026-10-20，64 用例）*
