# 需求实施计划

- [x] 1. 设置十标签说明、横滑与记忆
  - 用 `HorizontalPager` 替换 `TabRow` 点击切换，十页对应规格书 3.3
  - 每页标题下增加一行功能说明（代理=灵魂、服务=API、工具=技能、虚拟机=参数、状态=CPU 等、仓库=Git、连接设备=家居、语言语音=声线、日志=文件、通用=背景/唤醒）
  - 选中索引写入 `jarvis_settings` 并在进入时恢复
  - 对应 Requirement 1.1、1.2、1.3

  - [x] 1.1 编写设置标签说明与索引持久化单元测试
    - 锁定十个标题与说明文案
    - 对应 Requirement 1.1

- [x] 2. 通用页补默认助手与规格遗漏开关
  - 增加「一键设为默认 AI 助手」按钮，跳转系统语音输入/默认助手设置
  - 通用页补齐背景图、图标说明、唤醒开关（已有项保留）
  - 对应 Requirement 1.4、Requirement 3.3

- [x] 3. 状态页 CPU 实时采样与设备列表自动刷新
  - `DeviceMonitor` 用两次 `/proc/stat` 差分计算 CPU%；缺文件返回中文原因
  - 状态页可见时每 2 秒自动刷新，去掉「必须点刷新才有数」
  - 连接设备页可见时按间隔刷新发现列表
  - 对应 Requirement 2.1、2.2、2.3

  - [x] 3.1 编写 CPU 差分计算与缺文件失败关闭测试
    - 对应 Requirement 2.2

- [x] 4. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 5. 主题色生效与非聊天页顶部空白
  - `JarvisTheme` 把自定义色写入 primary/secondary 并驱动重组
  - 去掉虚拟机/设置双重 TopAppBar 与 Scaffold 叠加 padding
  - 对应 Requirement 3.2、3.3

- [x] 6. 导航记住设置页，避免切回默认第一页
  - 设置路由 `saveState/restoreState`；设置内部索引走 SharedPreferences
  - 对应 Requirement 3.4

- [x] 7. 按需权限：冷启动不批量申请
  - 启动路径去掉批量 `requestPermissions`
  - 麦克风/电话/短信/日历/相机/定位仅在对应用户操作后说明用途再请求
  - 对应 Requirement 4.1、4.2

  - [x] 7.1 编写启动路径不请求危险权限的静态/单元测试
    - 对应 Requirement 4.1

- [x] 8. 首次运行模型安装向导
  - 无本地模型时展示向导，用户确认后才下载；超时/失败中文错误 + 重试
  - 关闭「自动下载缺失模型」默认开启导致的静默下载
  - 对应 Requirement 4.3、4.4

  - [x] 8.1 编写未确认不下载、超时失败关闭测试
    - 对应 Requirement 4.4

- [x] 9. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 10. 虚拟机发行版清单与 PRoot 免初始化
  - `buildCatalog()` 改为 Debian 12 / Alpine 3.21 / Debian 13 / Ubuntu 24.04 / Arch / Void 及对应 x86_64 模拟条目
  - PRoot 随包或缓存就绪即 `isReady`，去掉强制初始化步骤
  - x86_64 在缺少模拟组件时提示「当前设备无法模拟 x86_64」
  - 对应 Requirement 5.1–5.4

  - [x] 10.1 编写发行版目录与 x86_64 不可用提示测试
    - 对应 Requirement 5.1、5.4

- [x] 11. 默认钢铁侠中文男声
  - 默认声线 id `jarvis_zh_cn`、gender=male；源失败降级系统中文 TTS 并提示
  - 对应 Requirement 6.1

  - [x] 11.1 编写默认声线性别与降级提示测试
    - 对应 Requirement 6.1

- [x] 12. 聊天历史列表与对话操作
  - 聊天页接入 `ConversationRepository`：列出、切换、新建、删除
  - 发送后持久化当前对话
  - 对应 Requirement 6.2、6.3

  - [x] 12.1 编写对话切换与持久化测试
    - 对应 Requirement 6.3

- [x] 13. 检查点 - 确保所有测试通过
  - 确保所有测试通过,如有疑问请询问用户

- [x] 14. 全量回归、打包 Debug APK 并上传 7z
  - `testDebugUnitTest` 全绿后 `assembleDebug`
  - 更新 `apk/jarvis-debug.apk.7z` 与 `docs/DOWNLOAD.md`
  - 对应 Requirement 7.1
