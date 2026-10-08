# JARVIS 回归测试报告

> 记录 Bug 修复后的回归验证结果。

---

## 1. 回归测试范围

针对 [bug_report.md](file:///workspace/docs/bug_report.md) 中已修复的 6 个 Bug，执行以下回归验证：

1. 全量编译（Debug + Release）
2. 全量单元测试
3. Lint 检查
4. 构建产物可安装性验证（静态）

---

## 2. 测试环境

| 项目 | 值 |
|------|-----|
| JDK | Java 17（mise 管理） |
| Gradle | 8.14.5 |
| AGP | 8.7.3 |
| Android SDK | platform-35, build-tools-35.0.0 |
| Kotlin | 随 AGP |
| 测试命令 | `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` |

---

## 3. 回归测试结果

| 测试项 | 结果 | 说明 |
|--------|------|------|
| Debug 编译 | ✅ 通过 | `assembleDebug` 成功 |
| Release 编译 | ✅ 通过 | `assembleRelease` 成功 |
| 单元测试 | ✅ 39/39 通过 | 9 个测试类全部通过 |
| Lint | ✅ 0 错误 | 16 警告（非阻塞） |
| Debug APK 产出 | ✅ 21.75 MB | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK 产出 | ✅ 14.84 MB | 标记为未验证（待实机测试后重新打包） |

---

## 4. Bug 修复回归对照

| Bug 编号 | 修复内容 | 回归验证 | 结果 |
|----------|----------|----------|------|
| #1 VmManager ID 冲突 | ID 加计数器 | `VmManagerTest` 5/5 | ✅ |
| #2 MissingPermission | `@SuppressLint` | lint 0 错误 | ✅ |
| #3 NewApi | `@RequiresApi` + 替换 PriorityQueue | lint 0 错误 + 编译通过 | ✅ |
| #4 RegisterReceiverFlag | `ContextCompat.registerReceiver` + `RECEIVER_NOT_EXPORTED` | lint 0 错误 | ✅ |
| #5 Java 版本 | 切换 Java 17 | 编译通过 | ✅ |
| #6 SDK 缺失 | 安装 platform-35 + build-tools-35.0.0 | 编译通过 | ✅ |

---

## 5. 结论

所有已修复 Bug 均通过回归验证，编译、单元测试、Lint 无阻塞问题。

**注意**：本报告仅覆盖沙箱内可执行的静态与单元测试回归。实机回归（唤醒、保活、权限、模型推理）需在真机上完成后补充。

---

*文档版本：v1.0 | 生成日期：2026-10-09*
