# 手机版验证产物

本目录存放 `com.cargps.mobile` 在 `Pixel_9` 和跨 API 模拟器上产生的本地验证资料。除本说明外，其余文件默认不进入 Git；所有设备命令都显式指定 serial，不操作 Redmi 真机。

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
- `cargps-mobile-api31-*.xml`：API 31 初始未授权、真实 Approximate/Precise 升级、首次/永久拒绝、应用设置返回、系统定位开关、前台记录、锁屏返回、活动行程撤权及受阻结束证据。
- `release-v0.2.0/CarGPS-Mobile-v0.2.0.apk`：`0.2.0 (3)` 正式签名 APK，SHA-256 为 `8fc1238c1fdc45db0e49d3d78243abdfe834fe15e87008e53004ae3eea366bc2`。
- `release-v0.2.0/Pixel_9-v0.2.0.png`、`Pixel_9-v0.2.0.xml`：正式包安装后的 Pixel_9 画面和 UI 树，滚动节点数量为 0。

## 历史资料

- `cargps-mobile-*.png`、`cargps-mobile-*.xml`：手机版持久化、历史行程和定位状态验证。
- `mobile-compact-*`、`mobile-rebalanced-*`：单屏密度与区块间距调整过程。
- `mobile-final-*`、`mobile-one-screen-*`：最终单屏布局和行程状态快照。
- `release-v0.1.0/`、`release-v0.1.1/`、`release-v0.2.0/`：手机版 APK、Pixel_9 截图和 `SHA256SUMS`。

发布目录中的校验文件只列 `CarGPS-Mobile-*.apk`。移动或替换 APK 后必须重新运行 SHA-256 校验，不能把旧摘要当作新构建证据。

M2 的 30 分钟后台监测在第 5 个一分钟样本后被外部 `force-stop` 中断；系统退出记录为 `USER REQUESTED / FORCE STOP`，crash buffer 为空。该次监测既不计为通过，也不计为应用崩溃，不能替代 API 27/API 29/API 35 的完整 30 分钟或真实道路长测。

M3 破坏性验证使用应用自身 UID 对 PID `9235` 发送 `SIGKILL`。系统记录为 `SIGNALED / status=9`，随后自动创建 PID `9355`；未手工重启 Service，新进程恢复为 `location` 前台类型、通知持续存在、定位线程为 1，UI 显示“已恢复”，结束行程后通知和 Service 正常清除，crash buffer 为空。该场景不会先等待 `onTaskRemoved()` 的异步检查点请求，恢复结果以最近一次已确认批次为上限。
