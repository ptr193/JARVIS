# 需求实施计划

- [x] 1. 下载镜像 URL 改写与 failover
  - 新增 `DownloadMirrors`：HuggingFace → hf-mirror，GitHub → ghproxy
  - `ModelDownloadManager` 主源失败后自动切镜像
  - `ProotManager.downloadToFile` 缩短超时，由上层切源
  - `VmDistroManager` 合并 mirrorUrls 与推导镜像
  - 向导去掉 30 秒整包超时
  - 对应 Requirement 2.1–2.5

  - [x] 1.1 编写镜像改写与 failover 单元测试
    - 对应 Requirement 2.1、2.2

- [x] 2. 设置页卡顿收敛
  - HorizontalPager `beyondBoundsPageCount = 0`
  - 状态页仅可见时采样，refresh 走 IO
  - 服务页复用 Koin 单例 ModelDownloadManager
  - 对应 Requirement 1.1–1.4

- [x] 3. Haven 发行版清单
  - 补 Kali（使用已有 SHA-256）aarch64 + 清华/官方源
  - Rocky / Windows 列为导入项
  - 无 checksum 时尝试 `.sha256` 侧车
  - 对应 Requirement 3.1–3.5

  - [x] 3.1 编写 Kali SHA 与导入项测试
    - 对应 Requirement 3.1、3.2

- [x] 4. 权限说明弹窗补全
  - 相机改用 `rememberOnDemandPermission`
  - 聊天页麦克风走同一入口
  - 拒绝时统一「未授予权限」
  - 对应 Requirement 4.1–4.4

  - [x] 4.1 编写相机/麦克风入口静态测试
    - 对应 Requirement 4.1、4.2

- [x] 5. 检查点 - 确保所有测试通过
  - `testDebugUnitTest` 全绿后 `assembleDebug`，打 7z，更新 DOWNLOAD.md，推 origin/main
