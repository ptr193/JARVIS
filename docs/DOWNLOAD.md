# JARVIS 交付物与直连下载路径

> 生成日期：2026-10-20 | 版本：v0.0.1（Debug，未经实机验证）

---

## 1. 交付物清单

| 交付物 | 路径 | 状态 |
|--------|------|------|
| 完整源码 | 本仓库根目录 | ✅ |
| Debug APK | `apk/jarvis-debug.apk` | ✅ 可用 |
| Release APK | — | ⛔ **未交付**（未实机测试，按计划 §5.2 阻断） |
| 需求追踪矩阵 | `docs/requirements_traceability.md` | ✅ |
| 仓库结构说明 | `docs/repo_structure.md` | ✅ |
| 模型配置 | `configs/models/*.yaml` | ✅ |
| 模型适配报告 | `docs/model_adaptation_report.md` | ✅ |
| 阶段一测试报告 | `docs/test_report_stage1.md` | ✅ |
| Bug 报告 | `docs/bug_report.md` | ✅ |
| 回归测试报告 | `docs/regression_test_report.md` | ✅ |
| 流程合规记录 | `docs/process_compliance.md` | ✅ |
| Release 前置清单 | `docs/release_checklist.md` | ✅ |

---

## 2. Debug APK（可直接下载）

```text
版本：   v0.0.1-debug
路径：   JARVIS/apk/jarvis-debug.apk
大小：   218,721,777 字节（208.6 MB）
SHA256： 613d87a1fb4d99152c26440da9c27960e3785c29b255e4480dc356305ae2c741
构建：   2026-10-22 14:30 UTC
构建命令：gradle -Pandroid.aapt2FromMavenOverride=<aapt2> clean assembleDebug
```

**完整性校验：**

```bash
sha256sum jarvis-debug.apk
# 期望：613d87a1fb4d99152c26440da9c27960e3785c29b255e4480dc356305ae2c741
```

> APK 时间戳（14:30）晚于全部源码最新修改时间，即 APK 与当前源码一致。
>
> 本次构建针对**第二轮模拟器测试**修复启动崩溃（BUG-2026-1025）。
> 第二轮证明第一版修复（仅调整 onCreate 内顺序）**不足以解决**问题，
> 因为 5 秒时限的时钟在 `startForegroundService()` 被调用时即开始，
> 而崩溃根因是主线程被唤醒引擎初始化阻塞。本版改为：
> ① 唤醒引擎初始化移出主线程（`Dispatchers.Default`）；
> ② 保活服务延后至首帧渲染完成后启动（`decorView.post`）；
> ③ 自主任务循环首轮延后 15 秒，避开冷启动窗口；
> ④ `onStartCommand` 增加幂等 `startForeground()` 兜底。
> 详见 `docs/bug_report.md`。

---

## 3. Release APK（未交付）

**不提供 Release 下载路径。**

原因：`JARVIS_补充计划.md` §5.2 / §6.2 规定，未完成实机测试不得打包发行版。
当前环境无法执行实机测试（无真机/模拟器，详见 `docs/process_compliance.md` 第 3 节），
因此 Release 打包被阻断。

历史目录中的 `jarvis-release.apk`（2026-10-18）为**此前流程违规产物**，
未经任何实机验证，**不作为有效交付物**，请勿使用。

---

## 4. 模型适配包 / 配置

| 模型 | 配置文件 | 权重 |
|------|---------|------|
| Gemma 4 E2B 2.5B | `configs/models/gemma4_e2b_2.5b.yaml` | `configs/models/files/gemma4_e2b_2_5b.litertlm.part.00/01` |
| Gemma 4 12B | `configs/models/gemma4_12b.yaml` | `configs/models/files/gemma4_12b.litertlm.part.00-03` |
| LFM 2.5 1.7B | `configs/models/lfm2.5_1.7b.yaml` | `configs/models/files/lfm2_5_1_7b.litertlm` |
| Qwen 3 0.6B | `configs/models/qwen3_0.6b.yaml` | `configs/models/files/qwen3_0_6b.litertlm` |

> 模型权重为本地分片文件（Gemma 4 12B 分 4 片）。真机冒烟测试尚未执行。

---

## 5. 源码包

- 源码位于本目录，可直接使用。
- 无 git 仓库（当前环境非 git 环境），以目录形式交付。
- 构建产物 `app/build/` 为中间产物，可删除后重新构建。

---

## 6. 使用说明

1. 将 `apk/jarvis-debug.apk` 安装到 Android 6.0+ 设备。
2. 首次启动授予所需权限（电话/短信/定位/麦克风/通知/无障碍）。
3. 在 **设置 → 服务** 触发模型下载，或使用 **设置 → 工具** 导入本地模型。
4. 按 `docs/release_checklist.md` 第 2 节执行实机测试清单并反馈。

---

*文档版本：v2.0 | 生成日期：2026-10-20*
