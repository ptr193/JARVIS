# JARVIS 交付物与直连下载路径

> 生成日期：2026-11-05 | 版本：v0.0.1（Debug，含相机入口与远程医疗失败关闭）

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

APK 体积大于 100MB，仓库提供 `.7z` 压缩包。请下载后解压再安装。

```text
版本：   v0.0.1-debug（相机入口 + 远程医疗失败关闭）
压缩包： apk/jarvis-debug.apk.7z
压缩包大小： 79,406,462 字节（75.7 MB）
压缩包 SHA256： e64c880b89483dcaa69dca05f54839fba2f461dfb051e8b23bb98369971fc228
APK 大小： 219,077,328 字节（208.9 MB）
APK SHA256： e6918435c2d764fd27f093a43e551933c3fd374704633fc542a62f4be95c06f2
构建：   2026-11-05
构建命令：JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew assembleDebug --no-daemon --max-workers=2
Git：    origin/main（本提交）
直链：   https://github.com/ptr193/JARVIS/raw/main/apk/jarvis-debug.apk.7z
```

**下载与校验：**

```bash
# 下载压缩包
# https://github.com/ptr193/JARVIS/raw/main/apk/jarvis-debug.apk.7z

sha256sum apk/jarvis-debug.apk.7z
```

期望：

```text
e64c880b89483dcaa69dca05f54839fba2f461dfb051e8b23bb98369971fc228
```

解压后校验 APK：

```text
e6918435c2d764fd27f093a43e551933c3fd374704633fc542a62f4be95c06f2
```

本包含相机采集入口与远程医疗无平台 URL 时的失败关闭。BUG-2026-1103 启动闪退修复仍在。`*.apk` 不入 Git，仅提交 `.7z`。Release 仍阻断。

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
- 远程仓库 `https://github.com/ptr193/JARVIS`，分支 `main`。
- 构建产物 `app/build/` 为中间产物，可删除后重新构建。

---

## 6. 使用说明

1. 将 `apk/jarvis-debug.apk` 安装到 Android 6.0+ 设备。
2. 首次启动授予所需权限（电话/短信/定位/麦克风/通知/无障碍）。
3. 在 **设置 → 服务** 触发模型下载，或使用 **设置 → 工具** 导入本地模型。
4. 按 `docs/release_checklist.md` 第 2 节执行实机测试清单并反馈。

---

*文档版本：v3.2 | 生成日期：2026-11-05*
