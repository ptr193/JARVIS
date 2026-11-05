# JARVIS 回归测试报告

> 记录 Bug 修复后的回归验证结果。
> 更新日期：2026-10-20 | 版本：v2.0

---

## 1. 回归测试范围

针对 `bug_report.md` 中已修复的缺陷执行回归：

1. 全量编译（Debug）
2. 全量单元 + 集成测试
3. 占位符 / 死代码扫描
4. 构建产物一致性验证（APK 时间戳晚于最新源码）

---

## 2. 测试环境

| 项目 | 值 |
|------|-----|
| 运行环境 | Haven PRoot (Debian) on Android aarch64 |
| JDK | 17（`/opt/jdk`） |
| Gradle | 8.9（`/opt/gradle-8.9`） |
| Android SDK | platform-35, build-tools-35.0.0（`/opt/android-sdk`） |
| 构建命令 | `gradle -Pandroid.aapt2FromMavenOverride=… assembleDebug` |
| 测试命令 | `gradle -Pandroid.aapt2FromMavenOverride=… testDebugUnitTest` |

---

## 3. 回归测试结果

| 测试项 | 结果 | 说明 |
|--------|------|------|
| Debug 编译 | ✅ 通过 | `assembleDebug` BUILD SUCCESSFUL |
| Release 编译 | ⏸️ 未执行 | 实机测试前禁止（计划 §5.2） |
| 单元 + 集成测试 | ✅ **64/64 通过** | 13 个测试类，0 失败 0 错误 0 跳过 |
| 占位符扫描 | ✅ 0 命中 | `grep -rn "TODO\|UnsupportedOperationException"` |
| 空 `onClick` 扫描 | ✅ 0 命中 | `grep "onClick = {}"` |
| 死代码扫描 | ✅ 已修复 | 主要 DI manager 均已接入 UI/对话链路 |
| Debug APK 产出 | ✅ 222,581,518 B | SHA256 `2589a8b2…159af` |

---

## 4. Bug 修复回归对照

| Bug 编号 | 修复内容 | 回归验证 | 结果 |
|----------|----------|----------|------|
| BUG-2026-1017 | 新增 3 个 UI 页签接入 18+ 死代码模块 | 编译通过 + 集成测试通过 | ✅ |
| BUG-2026-1018 | `ConnectedDevicesTab` 接真实 `CloudSyncManager` | 编译通过 | ✅ |
| BUG-2026-1019 | `LogTab` 读真实日志文件 | 编译通过 | ✅ |
| BUG-2026-1020 | `LanguageVoiceTab` 接 `VoiceManager` | 编译通过 | ✅ |
| BUG-2026-1021 | `TranslationEngine`/`InfoProcessor` 接入 `ChatEngine` | 编译通过 + DI 更新 | ✅ |
| BUG-2026-1022 | 记忆导入（SAF） | 编译通过 | ✅ |
| BUG-2026-1023 | `CloudSyncManager` 账号/令牌鉴权 | 编译通过 | ✅ |
| #1 VmManager ID 冲突 | ID 加计数器 | `VmManagerTest` 通过 | ✅ |
| #2-#4 Lint | 注解/API 修正 | 编译通过 | ✅ |
| #5-#6 构建环境 | JDK17 + SDK35 | 编译通过 | ✅ |

---

## 5. 结论

本轮修复的全部缺陷均通过回归验证，编译、单元测试、集成测试无阻塞问题。

**注意**：本报告仅覆盖沙箱内可执行的静态与自动化测试回归。
实机回归（唤醒、保活、权限、模型推理、3 个新页签交互）需在真机上完成后补充。

---

---

## 6. 2026-11-01 阶段 5.1 回归

| 测试项 | 结果 | 说明 |
|--------|------|------|
| `testDebugUnitTest` | 102/102 通过 | Gradle 8.11.1，JDK 17，SDK 35 |
| `assembleDebug` | EXIT=0 | `apk/jarvis-debug.apk` 218,835,095 B，SHA256 `92da4ca5…0ffd` |
| `lintDebug` | 记录 | 34 errors / 49 warnings，不阻断 Debug |
| `assembleRelease` | 未执行 | 计划 §5.2，待实机清单 |

本轮覆盖权限门面、相机失败关闭、无心率硬件提示、设备管理员 lock/wipe、记忆导入导出、设置页真实绑定。

---

## 7. 2026-11-05 任务 10–11 回归

| 测试项 | 结果 | 说明 |
|--------|------|------|
| `testDebugUnitTest` | BUILD SUCCESSFUL | Gradle 8.11.1，JDK 17，SDK 35，43s |
| `assembleDebug` | EXIT=0 | `apk/jarvis-debug.apk` 219,077,328 B，SHA256 `e6918435…06f2` |
| `apk/jarvis-debug.apk.7z` | 79,406,462 B | SHA256 `e64c880b…c228` |
| `assembleRelease` | 未执行 | 计划 §5.2，待实机清单 |

本轮覆盖相机采集入口（#33/#34/#35/#94/#112/#113）与远程医疗无平台 URL 失败关闭。

*文档版本：v3.1 | 生成日期：2026-11-05 | 上一版本：v3.0（2026-11-01）*
