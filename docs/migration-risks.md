# 手机版剩余高风险迁移项

作者：long

更新时间：2026-08-07 23:06:25（北京时间，UTC+8）

## 1. 当前基线

`v0.2.0` 已完成依赖升级、增量行程统计、定位回调线程化、SQLite 批量写入、R8、Baseline Profile 和 Pixel_9 发布验证。`v0.2.0` 之后已完成 M1 行程确认状态机、M2 定位前台服务、M3 最后确认检查点和进程重建恢复门禁，以及 M4 Room schema v4、旧版本显式迁移和数据损坏护栏；M5 权限状态机已落到当前工作树，并通过本地关卡与 Pixel_9 / API 35 权限矩阵，尚未完成跨 API 验收、提交或随新版本发布。当前继续保留 `minSdk = 27`。

M2 已消除“Activity 退到后台就主动停止定位”的旧结构，但完整 30 分钟监测被外部 `force-stop` 中断，且 API 27/API 29 尚未设备验收。现阶段只能确认 Pixel_9 上的服务启停、Home/锁屏短路径、Activity 重建、单定位线程、进程重建后的活动行程恢复和通知结束操作，不能扩展为跨版本后台可靠性结论。

本清单只记录会改变运行时语义、数据完整性或发布兼容性的迁移。普通 UI 微调和低风险依赖补丁不进入本清单。

## 2. 风险总览

| 编号 | 优先级 | 状态 | 迁移项 | 当前风险 | 完成前不能宣称 |
| --- | --- | --- | --- | --- | --- |
| M1 | P0 | 核心已验证，待发版 | 行程状态写入确认与单一所有者 | 命令已串行并等待存储确认；未承诺跨进程 exactly-once 命令语义 | 已在正式版本交付或断电零丢点 |
| M2 | P0 | 核心已验证，待跨 API/长测/发版 | 定位前台服务 | API 27/API 29 与完整 30 分钟未验收；新进程恢复边界已由 M3 补齐 | 已跨 Android 版本可靠后台记录 |
| M3 | P0 | 核心已验证，待跨 API/发版 | 进程异常退出与最后批次恢复 | 已恢复到最后确认检查点；最多约 1 秒内存尾批仍不保证落盘 | 杀进程、断电或 `force-stop` 零丢点 |
| M4 | P1 | 核心已验证，待跨 API/发版 | Schema 完整性与 Room 迁移 | Room v4 与损坏门禁已落地；API 27/API 29 升级安装仍随发布候选验收 | 已在所有受支持系统完成旧库升级 |
| M5 | P1；阻断发版 | Pixel_9 核心已验证，待跨 API/提交/发版 | 权限状态机 | API 35 系统弹窗、设置返回和阻断 UI 已验收；API 27/29/31/33 分支未验收 | 所有受支持 Android 版本的权限分支均已验证 |
| M6 | P1；阻断发版 | 待实现与验证 | 生命周期并发与真机长测 | Start 前定位、尾点与 End 仍由独立 coroutine 投递，顺序缺少单一事件队列保证 | 最后定位点必不丢失或已验证真实道路和长时功耗 |
| M7 | P1；阻断发版 | 待刷新 | Baseline Profile 热路径刷新 | Profile 仍含已删除类名，尚未覆盖行程开始和服务恢复 | 当前 Profile 与 M1-M6 代码一致 |
| M8 | P2 | 延后 | AGP 9 | 构建链仍为 AGP 8.13.2，升级可能引入插件与 R8 差异 | 已迁移到最新构建系统 |

## 3. 下一版发布阻断摘要

M1-M5 的核心实现已经通过 Pixel_9 / API 35 聚焦验证，但跨 API、M6 事件顺序和 M7 Profile 仍未完成，因此还不能直接发版。发布候选构建前至少完成以下四组关卡：

1. **跨 API 运行时**：在 API 27、API 29 和 API 35 完成 30 分钟 Home、切换应用、锁屏和停止回归；API 27/API 29 还要覆盖活动行程 `START_STICKY` 恢复。
2. **权限最小闭环**：覆盖精确、仅近似、首次拒绝、永久拒绝、系统定位关闭、Android 13+ 通知拒绝和设置返回收敛；所有失败分支都不得循环请求或误显示“正在记录”。
3. **事件顺序与生命周期并发**：用单一 Channel/事件队列串行化 Start、AppendPoint、Pause、Resume、End、Tick 和 Checkpoint；Service 必须等待 Start 存储确认后再启动定位，并覆盖尾点与 End、恢复与首点的竞态测试。
4. **性能资产一致性**：在 M1-M6 最终代码上重新生成 Baseline Profile，确认已删除的 `DashboardViewModel`、`DashboardViewModelFactory` 不再出现，并补行程开始与服务恢复热路径。

Room 全量迁移已经完成，不再列为剩余工作。真实设备 2 小时长测和 AGP 9 仍需分阶段推进，不能与权限/并发阻断修复塞入同一次架构改动。真实设备测试必须取得单独设备授权；当前 `Pixel_9` 授权不能自动扩展到 Redmi。

## 4. 推荐迁移顺序

### M1 行程状态写入确认与单一所有者

落地状态：

- `TripSessionCoordinator` 已成为进程内唯一行程会话所有者，使用同一串行入口处理恢复、开始、暂停、恢复、结束、定位点和时钟事件。
- `DashboardRuntime` 不直接持有 `TripAccumulator`、开始时间或暂停累计，只把协调器的只读状态映射到仪表状态。
- 开始、暂停、恢复和结束等元数据命令先发布“处理中”，等待 `awaitPendingWrites()` 确认后再切换模式；失败时保留上一个已确认模式并显示存储异常。定位点可先推进实时统计，但只有批次事务成功后才推进 `ActiveTripCheckpoint`。
- 重复开始、暂停、恢复和结束在当前进程内为 no-op；轨迹批次瞬时失败重试成功不误报，最终失败进入统一错误流。
- `TripSessionCoordinatorTest` 覆盖恢复、四类元数据失败、重复命令、两段暂停统计和异步错误；队列测试覆盖瞬时重试、终态失败及元数据屏障。

验证证据：2026-08-07 本地 `gps-core` 32/32 单测、`mobile-app` lint/debug 构建通过；Pixel_9 API 35 上 SQLite 4/4、Compose 1/1 通过，首屏无滚动节点且 crash buffer 为空。截图与 UI 树为 `artifacts/cargps-mobile-m1-pixel9.png`、`artifacts/cargps-mobile-m1-pixel9.xml`。

剩余边界：当前幂等保证针对单个 coordinator 进程；M3 已补齐服务重投和最后确认点边界。当前内部 Intent 与恢复策略不承诺跨进程 exactly-once 命令语义，也没有持久化命令账本，不能据此宣称断电零丢点。

### M2 定位前台服务

依赖：先确定 M1 的会话所有权，避免 Activity 和 Service 同时维护两份行程状态。

落地状态：

- `CarGpsApplication` 只创建一个 `DashboardRuntime` 和一条数据库写队列；Activity 重建和 Service 重连复用同一 `TripSessionCoordinator`。
- `TripRecordingService` 是唯一 `LocationEngine` 所有者，每秒向共享 Runtime 投递 Tick；Activity 只绑定本地 Binder、观察同一 `StateFlow` 并发送命令。
- 用户必须在可见 Activity 内明确开始行程，再调用 `startForegroundService()`。活动行程和暂停状态均保持前台服务，结束确认后移除通知并停止 Service。
- Manifest 已声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_LOCATION` 和 `android:foregroundServiceType="location"`；服务 `exported=false`，所有内部 Intent 均为显式目标，通知 `PendingIntent` 使用 `FLAG_IMMUTABLE`，不接收或转发 nested Intent。
- 通知提供返回应用和“结束行程”，只在模式变化、每 10 米或最多每 5 秒刷新；未申请 `ACCESS_BACKGROUND_LOCATION`，也不允许任意后台时刻新建服务。

验证证据：Pixel_9 / API 35 上 SQLite instrumentation 4/4、`mobile-app` instrumentation 3/3；运行时服务为 `location` 前台类型，通知和结束操作可用。Home、锁屏、Activity 重建、重复打开、覆盖安装触发进程重建和通知结束短路径通过，定位线程始终为 1，crash buffer 为空。证据位于本地 `artifacts/cargps-mobile-m2-*`。

未完成门槛：API 27、API 29 和 API 35 上分别执行完整 30 分钟 Home、切换应用和锁屏；通知持续可见，轨迹连续，停止后无回调，重建 Activity 不产生第二个定位监听。2026-08-07 的 30 分钟监测在第 5 个样本后被外部 `force-stop` 中断，退出类型为 `USER REQUESTED / FORCE STOP`，该次结果无效且不能计为通过。

官方依据：

- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://developer.android.com/develop/background-work/services/fgs/launch

### M3 进程异常退出与最后批次恢复

依赖：M1/M2 的会话所有权已经稳定。

落地状态：

- `ActiveTripCheckpoint` 已明确记录活动行程开始时间、确认点数、最后确认 sequence 和最后确认时间；Room v4 沿用既有表字段查询该边界。
- `QueuedTripStorage` 在批量事务成功并移除内存点后发布检查点，协调器只接受当前活动行程的确认通知；生命周期 `Checkpoint` 命令强制等待尾批次并更新 Runtime。
- `TripRecordingService.onTaskRemoved()` 异步请求尽力建立检查点；该回调不能阻塞主线程，也不保证系统回收前完成。系统未调用回调或请求尚未完成时，以最近一次定时批次确认边界恢复，未确认窗口仍最多约 1 秒。
- Service 收到 `START_STICKY` 的 null Intent 后先升恢复通知并等待 `DashboardRuntime.awaitInitialRestore()`，不再根据新进程初始 `IDLE` 自停。
- 恢复成功后恢复活动模式、暂停累计、已落库轨迹和确认检查点，并断开跨进程定位段；无活动行程或恢复失败后才停止 Service。

验证证据：本地 `gps-core` 36/36、手机版策略 4/4；Pixel_9 SQLite 5/5、手机版 3/3。对活动行程 PID 发送 `SIGKILL` 后，系统自动以新 PID 恢复 `location` 前台服务、通知、单定位线程和“记录中 / 已恢复”状态，结束后资源正常清除，crash buffer 为空。

剩余门槛：API 27/API 29 设备恢复、已知点序列在进程终止前后的损失窗口量化、设备重启和低存储场景仍未验证；`force-stop` 会阻止系统自动启动，不能与普通进程回收混为一谈。

### M4 Schema 完整性与 Room 迁移

落地状态：

- 生产装配已从 `SqliteTripStorage` 切换到 `RoomTripStorage`，使用 Room 2.8.4 和 schema v4；`SqliteTripStorage` 只保留为旧 schema fixture 与兼容验证工具。
- Room 注册显式 `MIGRATION_1_2`、`MIGRATION_2_3`、`MIGRATION_3_4` 并导出 v4 schema，未配置 destructive fallback。v3 到 v4 通过事务内改名、建表和复制建立 Room schema identity，失败时整体回滚。
- `ActiveTripLoadResult.Empty`、`Loaded`、`Corrupt` 已区分“没有活动行程”和“活动行程损坏”。非法 mode 返回 `Corrupt`，保留原始行；协调器令 `storageReady = false` 并拒绝开始新行程。
- Pixel_9 / API 35 已验证 v1 到 v4 活动行程与轨迹、v2 到 v4 历史统计与轨迹、v3 到 v4 暂停行程、v4 完整 `TripStorage` 事务契约、非法 mode 原始行保留，以及缺失 `total_paused` 时迁移失败后保留 v3 版本和旧表数据。

剩余门槛：把 Room v4 随 M1-M4 一起在 API 27/API 29 做升级安装回归，并保留正式发布前的完整 instrumentation、lint 与 Release 构建关卡。当前结果证明已覆盖 fixture 的无损迁移和失败保留，不宣称任意物理损坏都能自动恢复。

### M5 权限状态机

落地状态：

- `TripAccessPolicy` 以独立的 `Ready` / `Blocked` 状态判断行程是否可启动，不把权限阻断、定位质量和行程模式混为同一状态。
- 阻断原因已区分首次缺少定位、永久拒绝定位、仅近似定位、系统 GPS Provider 关闭、首次缺少通知和永久拒绝通知；API 27 不把通知运行时权限作为前置条件。
- Activity 记录权限请求历史，在权限回调、设置页返回、`onResume()` 和 Provider 变化时重新求值，并清理拒绝后的延迟启动意图；Service 启动、恢复和确保活动行程使用同一策略。
- 界面为不同阻断原因显示中文状态和对应的权限请求、应用设置、定位设置或通知设置入口；活动行程被阻断时保留结束入口。

本地验证证据：2026-08-07 完整本地关卡通过，包括 JVM、AndroidTest 编译、lint、lintVital、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease`；`TripAccessPolicyTest` 11/11，新增首次近似精度升级、升级仍可重试和升级拒绝后转设置三条边界。

Pixel_9 / API 35 验证证据：`gps-core` instrumentation 12/12、手机版 6/6；新增测试校验应用/定位/通知设置 Intent、初始未授权单屏无滚动，以及活动行程受阻时仍可结束。真实系统 UI 已覆盖精确、仅近似、近似升级拒绝、定位首次/永久拒绝、通知首次/永久拒绝、通知设置返回和系统定位关闭/恢复；所有分支均保持行程空闲，不自动开始或循环弹窗。证据位于本地 `artifacts/cargps-mobile-m5-*`。

剩余验收边界：

- 在 API 27、29、31、33 环境重复精确、近似、首次/永久拒绝、系统定位关闭与恢复，以及适用版本的通知权限回归。
- 前台服务所需位置权限必须在启动前满足；通知不可见时不能让用户误判后台记录状态。后台位置权限不是当前前台服务的默认前置条件。

发布前最小门槛：Pixel_9 / API 35 已完成；正式候选仍需结合第 3 节跨 API 关卡。任何失败分支都不得崩溃、循环请求或错误显示“正在记录”，通知不可见时也不能让用户误判后台记录状态。

完整验收门槛：在 API 27、29、31、33、35 覆盖精确、近似、首次拒绝、永久拒绝、系统定位关闭与恢复，并验证从系统设置返回后的状态收敛。

### M6 生命周期并发与真实设备长测

当前缺口：Service 目前先启动 `LocationEngine` 再异步确认 Start，首个定位点可能在行程进入记录状态前到达；定位点和 End 分别由独立 coroutine 投递，协调器的 Mutex 只能保证互斥执行，不能把调用先后固化为可测试的事件顺序。`onTaskRemoved()` 检查点仍是尽力执行，不能承诺进程回收前完成。

改造边界：

- 使用单一 Channel/事件队列串行处理 Start、AppendPoint、Pause、Resume、End、Tick 和 Checkpoint；Service 等待 Start 存储确认后再启动定位，避免 `TripAccumulator`、UI state 与数据库命令交错。
- 覆盖冷启动恢复期间首个定位点、Activity 重建、服务重连、暂停与定位同时发生、结束与尾批次冲刷同时发生。
- 在前台服务和存储迁移完成后进行真实道路与静止长测，模拟器 Macrobenchmark 只保留为版本间相对基线。

验收门槛：至少一台 Android 12 以上手机和一台 API 27 环境完成 2 小时记录；收集定位点完整率、数据库增长、CPU、内存、耗电、温升、ANR 和崩溃。真实设备是否使用 Redmi 需由用户单独授权，不能沿用 Pixel_9 授权范围。

### M7 Baseline Profile 热路径刷新

当前生成的 `baseline-prof.txt` 仍包含已删除的 `DashboardViewModel` 和 `DashboardViewModelFactory`。M5/M6 收口后必须先在 Pixel_9 重新生成 Profile，确认旧类名消失；Profile 生成器还应覆盖行程开始、服务恢复等关键热路径，而不只覆盖冷启动首屏。

验收门槛：生成任务在 `Pixel_9 / emulator-5554` 完成；旧类名搜索结果为 0；Release APK 内含新的 `baseline.prof` 与 `baseline.profm`；冷启动基准与 v0.2.0 使用同一 AVD 配置对比，不能把模拟器结果解释为真机绝对性能。

### M8 AGP 9

AGP 9 属于构建链迁移，不是当前最紧迫的运行时风险。应在 M5 至 M7 的行为和测试稳定后使用独立分支升级，避免把 Kotlin 内置支持、插件兼容和 R8 差异与权限/并发回归混在同一提交。

验收门槛：API 27 安装启动、JVM 与 instrumentation、Release R8、资源压缩、Baseline Profile 生成和 GitHub 发布链全部通过，APK 包名与签名身份不变。

## 5. 发布门禁

M1-M6 的发布候选必须先通过第 3 节四组阻断关卡。即使通过，发布说明仍必须保留以下边界：模拟器性能不代表真机；恢复上限是最后确认检查点而非零丢点；`force-stop`、断电、设备重启和低存储不属于当前已验证恢复结论；Room 迁移已完成但跨 API 升级安装、跨 API 权限矩阵和真实道路长测仍未完成。

每完成一个迁移项，应同步更新本文件状态、技术设计、测试基线和发布说明，并把对应自动化与设备证据放到 `artifacts/`，不能只修改计划文字。
