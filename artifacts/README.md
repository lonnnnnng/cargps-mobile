# 手机版验证产物

本目录存放 `com.cargps.mobile` 在 `Pixel_9` 和跨 API 模拟器上产生的本地验证资料。除本说明外，其余文件默认不进入 Git；所有设备命令都显式指定 serial，不操作 Redmi 真机。当前已有 JVM 普通存储失败/背压恢复首点分段证据、`LocationEngineSessionController` 启停幂等 seam（4/4）、真实 Room/SQLite 存储层只读写失败证据、受控真实 `SQLITE_FULL` 与 Runtime/Room 背压链路证据，但尚无物理低存储截图或通知/GPS 恢复产物。控制器 seam 没有独立设备产物，需与 Service 全路径故障注入区分记录。

## 当前验证

- `cargps-split-mobile.png`：拆分为独立项目后的 Pixel_9 单屏截图。
- `cargps-split-mobile.xml`：同次验证的 UI Automator 树；`scrollable="true"` 节点数量应为 0。
- `cargps-mobile-upgrade-pixel9.png`：依赖、架构和性能升级后的 Pixel_9 单屏截图。
- `cargps-mobile-upgrade-pixel9.xml`：同次升级验证 UI 树；实测 `scrollable="true"` 节点数量为 0。
- `cargps-mobile-m1-pixel9.png`：行程协调器 M1 接入后的 Pixel_9 debug 首屏截图。
- `cargps-mobile-m1-pixel9.xml`：同次 M1 回归 UI 树；实测 `scrollable="true"` 节点数量为 0，crash buffer 为空。
- `cargps-mobile-m2-recording.png`、`cargps-mobile-m2-recording.xml`：M2 前台定位服务记录中的首屏和 UI 树。
- `cargps-mobile-m2-recreated.xml`、`cargps-mobile-m2-repeated-activity.xml`：Activity 重建和重复打开后的状态树；同一进程内始终只有一个定位线程。
- `cargps-mobile-m2-notification.xml`、`cargps-mobile-m2-notification-expanded.png`：`location` 前台服务通知及“结束行程”操作。
- `cargps-mobile-m2-after-update.xml`、`cargps-mobile-m2-notification-ended.xml`：覆盖安装触发进程重建后的活动行程恢复，以及从通知结束后回到空闲状态的证据。
- `cargps-mobile-m3-after-sigkill.png`、`cargps-mobile-m3-after-sigkill.xml`：活动行程进程被 `SIGKILL` 后由 `START_STICKY` 自动重建的 Pixel_9 画面和 UI 树，显示“记录中 / 已恢复”。
- `cargps-mobile-api27-v020-before.db`、`cargps-mobile-api27-upgraded-after.db`：API 27 Debug 同签名覆盖升级前后的 SQLite v3/Room v4 数据库，39 点、33.50 米保持不变。
- `cargps-mobile-api29-v020-before.db`、`cargps-mobile-api29-upgraded-after.db`：API 29 Debug 同签名覆盖升级前后的数据库，29 点、33.50 米保持不变。
- `cargps-mobile-api27-release-v020-before.db`、`cargps-mobile-api27-release-upgraded-after.db`：API 27 公开正式 `v0.2.0` 覆盖当前同证书 Release 前后的数据库，29 点、37.62 米保持不变。
- `cargps-mobile-api29-release-v020-before.db`、`cargps-mobile-api29-release-upgraded-after.db`：API 29 公开正式 `v0.2.0` 覆盖当前同证书 Release 前后的数据库，30 点、28.74 米保持不变。
- `cargps-mobile-api27-location-*.xml`：API 27 首次拒绝、勾选“不再询问”后的永久拒绝、应用设置授权、系统定位关闭与恢复证据。
- `cargps-mobile-api27-30min-summary.md`：Android 8.1 / API 27 的 41 个样本、1816 秒后台回归摘要，覆盖 Home、系统设置、锁屏睡眠和解锁返回。
- `cargps-mobile-api27-30min-before.xml`、`cargps-mobile-api27-30min-started.xml`、`cargps-mobile-api27-30min-recording.xml`、`cargps-mobile-api27-end-confirm.xml`、`cargps-mobile-api27-30min-ended.xml`：干净起点、开始、长测结束时仍在记录、结束确认和历史增加后的 UI 树。
- `cargps-mobile-api27-reboot-summary.md`：API 27 活动行程整机重启边界验证；重启后不自动拉起 Service，打开应用后显示“已恢复”并恢复定位，活动数据保留。
- `cargps-mobile-api29-location-*.xml`：API 29 首次拒绝、`Deny & don’t ask again`、`USER_FIXED`、应用设置授权、系统定位关闭与恢复证据。
- `cargps-mobile-api29-30min-summary.md`：Android 10 / API 29 的 41 个样本、1816 秒后台回归摘要，覆盖 Home、系统设置、锁屏睡眠和解锁返回。
- `cargps-mobile-api29-30min-before.xml`、`cargps-mobile-api29-30min-started.xml`、`cargps-mobile-api29-30min-recording.xml`、`cargps-mobile-api29-end-confirm.xml`、`cargps-mobile-api29-30min-ended.xml`：干净起点、开始、长测结束时仍在记录、结束确认和历史增加后的 UI 树。
- `cargps-mobile-api31-*.xml`：API 31 初始未授权、真实 Approximate/Precise 升级、首次/永久拒绝、应用设置返回、系统定位开关、前台记录、锁屏返回、活动行程撤权及受阻结束证据。
- `cargps-mobile-api33-location-*.xml`：API 33 Approximate/Precise 升级、首次/永久拒绝、应用设置授权、系统定位关闭与恢复证据；通知拒绝证据沿用既有 API 33 产物。
- `cargps-mobile-api35-30min-summary.md`：Pixel_9 / API 35 的 41 个样本、1831 秒后台回归摘要，覆盖 Home、系统设置、锁屏睡眠和解锁返回。
- `cargps-mobile-storage-readonly-summary.md`：在 Room 实际打开的 SQLite 连接上执行 `PRAGMA query_only = ON` 后，Pixel_9/API 35 与 Android 8.1/API 27 的存储类各 13/13 通过；批量写失败时活动行程、已确认点和检查点保留且没有部分批次。该证据不等价于物理 `ENOSPC`。
- `cargps-mobile-runtime-backpressure-summary.md`：通过真实 Room 连接把只读故障推进到 `QueuedTripStorage -> DashboardRuntime`，Pixel_9/API 35 与 Android 8.1/API 27 专项用例各 1/1、完整 `gps-core` instrumentation 各 14/14；验证前 16 点保留、第 17 点拒绝、活动行程不清除、恢复后 16 点检查点确认。该证据仍不等价于物理 `ENOSPC` 或完整 `TripRecordingService` 设备路径。
- `cargps-mobile-sqlite-full-summary.md`：通过测试数据库 `max_page_count` 限页触发真实 `SQLiteFullException`，不填满共享模拟器磁盘；Pixel_9/API 35 与 Android 8.1/API 27 的存储类 14 项、Runtime 2 项合计各 16/16，验证批次回滚、16 点有界背压和恢复检查点。该证据仍不等价于物理磁盘 `ENOSPC` 或真实 Service/GPS 故障路径。
- `cargps-mobile-controller-instrumentation-20260808.md`：控制器接入和 `LocationEngine.start()` 失败回滚后的双设备完整 instrumentation 摘要；Pixel_9/API 35 与 Android 8.1/API 27 各为 `gps-core` 14/14、手机版 6/6。该摘要明确区分本轮完整套件与此前 Runtime/Room 背压专项证据。
- `cargps-mobile-m7-refresh-startup-benchmark-run1.json`：当前热路径 Profile 刷新后的第一轮 Pixel_9 冷启动对照；无预编译中位 244.03ms，Baseline Profile 中位 201.43ms，改善 17.46%，SHA-256 为 `4963bff7a11198f4464bb8f44100bc551e1cb03827cbe4d47a28372f5c0579cd`。
- `cargps-mobile-m7-refresh-startup-benchmark-run2.json`：第二轮对照；无预编译中位 393.52ms，Baseline Profile 中位 297.62ms，改善 24.37%，SHA-256 为 `687e769211f6c6fcf60a805f9ce433e45207a5d52c85d0d849a03a99ed625b93`。该轮波动明显，且设备未锁 CPU，只能用于同一 AVD 相对比较。
- `cargps-mobile-api35-30min-recording.xml`、`cargps-mobile-api35-end-confirm.xml`、`cargps-mobile-api35-30min-ended.xml`：长测结束时仍在记录、结束确认和历史增加后的 UI 树。
- `release-v0.2.0/CarGPS-Mobile-v0.2.0.apk`：`0.2.0 (3)` 正式签名 APK，SHA-256 为 `8fc1238c1fdc45db0e49d3d78243abdfe834fe15e87008e53004ae3eea366bc2`。
- `release-v0.2.0/Pixel_9-v0.2.0.png`、`Pixel_9-v0.2.0.xml`：正式包安装后的 Pixel_9 画面和 UI 树，滚动节点数量为 0。
- `release-v0.3.0/CarGPS-Mobile-v0.3.0.apk`、`SHA256SUMS`：`0.3.0 (4)` 正式签名 APK 与只包含该 APK 的 SHA-256 校验文件；APK SHA-256 为 `0f82aec8ebf7414146a53d958c9dc2feb0006cc849772d5535a5aaee33058c09`，大小为 `1,117,952` 字节。
- `release-v0.3.0/Pixel_9-v0.3.0-initial.png`、`Pixel_9-v0.3.0-recording.png`、`Pixel_9-v0.3.0.png` 及对应 XML：正式包在 Pixel_9 / API 35 的未授权首屏、34 米记录中和结束归档画面；三阶段滚动节点均为 0，结束并 Home 后 Service、通知、定位线程和 crash 记录均为 0。

## 历史资料

- `cargps-mobile-*.png`、`cargps-mobile-*.xml`：手机版持久化、历史行程和定位状态验证。
- `mobile-compact-*`、`mobile-rebalanced-*`：单屏密度与区块间距调整过程。
- `mobile-final-*`、`mobile-one-screen-*`：最终单屏布局和行程状态快照。
- `release-v0.1.0/`、`release-v0.1.1/`、`release-v0.2.0/`、`release-v0.3.0/`：手机版 APK、Pixel_9 截图和 `SHA256SUMS`。

发布目录中的校验文件只列 `CarGPS-Mobile-*.apk`。移动或替换 APK 后必须重新运行 SHA-256 校验，不能把旧摘要当作新构建证据。

M2 早期一次 30 分钟后台监测在第 5 个一分钟样本后被外部 `force-stop` 中断；系统退出记录为 `USER REQUESTED / FORCE STOP`，crash buffer 为空。该次监测既不计为通过，也不计为应用崩溃。2026-08-08 已重新完成 Pixel_9 / API 35 的 41 个样本、1831 秒回归、Android 10 / API 29 的 41 个样本、1816 秒回归，以及 Android 8.1 / API 27 的 41 个样本、1816 秒回归；随后 API 27 完成整机重启边界验证：活动行程保留，但系统不自动拉起手机版 Service，打开应用后恢复。当前受控真实 `SQLITE_FULL` 回归在 API 35/API 27 的存储类各 14/14、Runtime/Room 类各 2/2，`gps-core` instrumentation 合计各 16/16；原只读连接 13/13 与背压专项 1/1、完整 14/14 摘要保留为历史证据。M3 probe-only 真实 Room 探针已经把 Checkpoint 提交前的尾批损失量化为 16 点，M6 外部脚本也闭环了 Activity/Service 同进程回收重绑。仍没有物理低存储截图、通知/GPS 停止恢复或真实道路长测产物。

API 35 长测期间 PID 始终为 `4395`、前台服务持续、`cargps-location` 线程始终为 1、crash buffer 始终为 0；锁屏进入 `Dozing/Asleep` 后仍持续记录。正常结束后历史从 0 段增加为 1 段，Home 后 Service、活动通知和定位线程均归零，GPS provider 为 `OFF` 并记录应用注销事件。

API 29 长测期间 PID 始终为 `3365`、前台服务和活动通知持续、`cargps-location` 线程与活动 GPS 注册始终为 1、crash buffer 始终为 0；锁屏 `Asleep` 后仍持续记录。正常结束后历史从 0 段增加为 1 段，Home 后 Service、活动通知、定位线程和 GPS 注册均归零，GPS provider 为 `mStarted=false`。

API 27 长测期间 PID 始终为 `3382`、前台服务、活动通知和 GPS 注册持续；独立 `/proc` 线程侧车 115 次采样均为 1 条 `cargps-location`，crash buffer 始终为 0。锁屏 `Asleep` 后仍持续记录。正常结束后历史从 0 段增加为 1 段，Home 后 Service、活动通知、定位线程和 GPS 注册均归零，GPS provider 为 `mStarted=false`。

M3 破坏性验证使用应用自身 UID 对 PID `9235` 发送 `SIGKILL`。系统记录为 `SIGNALED / status=9`，随后自动创建 PID `9355`；未手工重启 Service，新进程恢复为 `location` 前台类型、通知持续存在、定位线程为 1，UI 显示“已恢复”，结束行程后通知和 Service 正常清除，crash buffer 为空。该场景不会先等待 `onTaskRemoved()` 的异步检查点请求，恢复结果以最近一次已确认批次为上限。

API 27 整机重启验证以活动行程 `RECORDING` 为前置条件：重启前观察到 `active_point` 118 个（sequence `2004..2121`），重启完成且未打开应用时没有手机版进程、Service、通知或 GPS 注册，但 `active_trip` 仍保留；手动打开后 UI 显示“记录中 / 已恢复”，Service 和 GPS 注册恢复为 1，确认结束后资源归零。该结果只证明“数据保留 + 用户打开后恢复”，不证明开机自动继续或尾批零丢失。详见 `cargps-mobile-api27-reboot-summary.md`。
