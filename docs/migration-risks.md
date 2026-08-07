# 手机版剩余高风险迁移项

作者：long

更新时间：2026-08-08 06:02:40（北京时间，UTC+8）

## 1. 当前基线

`v0.2.0` 已完成依赖升级、增量行程统计、定位回调线程化、SQLite 批量写入、R8、Baseline Profile 和 Pixel_9 发布验证。`v0.2.0` 之后的 M1-M6 已完成当前开发线的核心实现并提交；M7 Baseline/Startup Profile 已在 Pixel_9 重新生成并完成两轮冷启动对照。2026-08-08 又完成 API 27、API 29、API 33 的聚焦 instrumentation 与运行时回归。M1-M7 尚未随新版本发布，完整 30 分钟、API 31、真实设备和异常环境长测仍未完成。当前继续保留 `minSdk = 27`。

M2 已消除“Activity 退到后台就主动停止定位”的旧结构。API 27/API 29 已验证开始、Home、单定位线程、普通进程 `SIGKILL` 后 `START_STICKY` 恢复、结束和资源清理；但完整 30 分钟监测仍未完成，既有监测还曾被外部 `force-stop` 中断。聚焦短路径不能扩展为跨版本长时后台可靠性结论。

本清单只记录会改变运行时语义、数据完整性或发布兼容性的迁移。普通 UI 微调和低风险依赖补丁不进入本清单。

## 2. 风险总览

| 编号 | 优先级 | 状态 | 迁移项 | 当前风险 | 完成前不能宣称 |
| --- | --- | --- | --- | --- | --- |
| M1 | P0 | 核心已验证，待发版 | 行程状态写入确认与单一所有者 | 命令已串行并等待存储确认；未承诺跨进程 exactly-once 命令语义 | 已在正式版本交付或断电零丢点 |
| M2 | P0 | API 27/29 短路径已验证，待长测/发版 | 定位前台服务 | 完整 30 分钟 Home、切换应用和锁屏仍未验收 | 已跨 Android 版本可靠后台记录 |
| M3 | P0 | API 27/29 恢复已验证，待异常场景/发版 | 进程异常退出与最后批次恢复 | 已恢复到最后确认检查点；最多约 1 秒内存尾批仍不保证落盘 | 杀进程、断电或 `force-stop` 零丢点 |
| M4 | P1 | 跨 API fixture 已验证，待升级安装/发版 | Schema 完整性与 Room 迁移 | API 27/29/33 的 Room fixture 迁移已通过；正式旧包覆盖升级仍未验收 | 已在所有受支持系统完成旧库升级 |
| M5 | P1；阻断发版 | API 27/29/33 聚焦已验证，待 API 31/完整矩阵/发版 | 权限状态机 | API 29 近似定位和 API 33 通知拒绝已验收；API 31 与跨版本完整位置权限矩阵仍未完成 | 所有受支持 Android 版本的权限分支均已验证 |
| M6 | P1；阻断发版 | API 27/29 短路径已验证，待长测/发版 | 生命周期并发与真机长测 | 不可见 Start 等待已禁止后台定位；30 分钟和真实设备仍未完成 | 最后定位点必不丢失或已验证真实道路和长时功耗 |
| M7 | P1 | 核心已验证，待发版 | Baseline Profile 热路径刷新 | 当前 Profile 已一致；无预编译启动仍比 v0.2.0 历史基线慢 | 整体启动性能没有回退或模拟器收益等同真机 |
| M8 | P2 | 延后 | AGP 9 | 构建链仍为 AGP 8.13.2，升级可能引入插件与 R8 差异 | 已迁移到最新构建系统 |

## 3. 下一版发布阻断摘要

M1-M6 的核心实现已经通过本地关卡、Pixel_9 / API 35，以及 API 27/29/33 聚焦验证；M7 性能资产已刷新。跨 API 长测和完整权限矩阵仍未完成，因此还不能直接发版。发布候选构建前至少完成以下三组关卡：

1. **跨 API 运行时**：API 27/API 29 的活动行程 `START_STICKY` 聚焦恢复已通过；仍需在 API 27、API 29 和 API 35 完成 30 分钟 Home、切换应用、锁屏和停止回归。
2. **权限最小闭环**：API 29 仅近似定位与 API 33 通知首次/永久拒绝已通过；仍需补齐 API 27/29/31/33 的精确定位、位置首次/永久拒绝、系统定位关闭和设置返回收敛。所有失败分支都不得循环请求或误显示“正在记录”。
3. **事件顺序与生命周期并发**：确认客户端不可见且 Start 等待时保持停止、可见页面只保留定位预览、活动行程在后台继续定位；复核单一 Channel/事件队列按 FIFO 串行化 Start、AppendPoint、Pause、Resume、End、Tick 和 Checkpoint，并覆盖尾点与 End、恢复与首点、双击 Toggle、队列关闭与失败传播。

性能资产一致性关卡已在当前 M1-M6 代码上通过；后续若继续修改启动、Service、Room 或行程热路径，发布候选必须重新生成并复核。

Room schema 与显式迁移实现已经完成，API 27/29/33 上的 12 个 fixture/事务/损坏门禁 instrumentation 均已通过；正式旧包覆盖升级安装仍属于发布关卡。真实设备 2 小时长测和 AGP 9 仍需分阶段推进，不能与权限/并发阻断修复塞入同一次架构改动。真实设备测试必须取得单独设备授权；当前模拟器授权不能自动扩展到 Redmi。

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

验证证据：Pixel_9 / API 35 上 SQLite instrumentation 4/4、`mobile-app` instrumentation 3/3；运行时服务为 `location` 前台类型，通知和结束操作可用。Home、锁屏、Activity 重建、重复打开、覆盖安装触发进程重建和通知结束短路径通过，定位线程始终为 1，crash buffer 为空。2026-08-08，API 27 与 API 29 又分别通过 `gps-core` 12/12、手机版 6/6 instrumentation；两者均完成开始、Home、单定位线程、进程恢复、结束和 Home 后资源清理。证据位于本地 `artifacts/cargps-mobile-api27-*`、`artifacts/cargps-mobile-api29-*`。

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

补充验证证据：API 27 的活动行程从 PID `3424` 恢复为 `3618`，API 29 从 PID `3587` 恢复为 `4159`；两者 `restartCount = 1`、前台服务和单条 `cargps-location` 线程恢复，Activity 返回后显示“记录中 / 已恢复”。API 29 使用可 root 的 `google_apis` AVD 发送真实 `SIGKILL`，未使用 `force-stop`。

剩余门槛：已知点序列在进程终止前后的损失窗口量化、设备重启和低存储场景仍未验证；`force-stop` 会阻止系统自动启动，不能与普通进程回收混为一谈。

### M4 Schema 完整性与 Room 迁移

落地状态：

- 生产装配已从 `SqliteTripStorage` 切换到 `RoomTripStorage`，使用 Room 2.8.4 和 schema v4；`SqliteTripStorage` 只保留为旧 schema fixture 与兼容验证工具。
- Room 注册显式 `MIGRATION_1_2`、`MIGRATION_2_3`、`MIGRATION_3_4` 并导出 v4 schema，未配置 destructive fallback。v3 到 v4 通过事务内改名、建表和复制建立 Room schema identity，失败时整体回滚。
- `ActiveTripLoadResult.Empty`、`Loaded`、`Corrupt` 已区分“没有活动行程”和“活动行程损坏”。非法 mode 返回 `Corrupt`，保留原始行；协调器令 `storageReady = false` 并拒绝开始新行程。
- Pixel_9 / API 35 以及 API 27、API 29、API 33 均已验证 v1 到 v4 活动行程与轨迹、v2 到 v4 历史统计与轨迹、v3 到 v4 暂停行程、v4 完整 `TripStorage` 事务契约、非法 mode 原始行保留，以及缺失 `total_paused` 时迁移失败后保留 v3 版本和旧表数据；每个 API 环境均为 12/12。

剩余门槛：使用真实 `v0.2.0` 旧包和旧数据库执行覆盖升级安装回归，并保留正式发布前的完整 instrumentation、lint 与 Release 构建关卡。当前结果证明已覆盖 API 27/29/33 fixture 的无损迁移和失败保留，不等同于正式 APK 升级，也不宣称任意物理损坏都能自动恢复。

### M5 权限状态机

落地状态：

- `TripAccessPolicy` 以独立的 `Ready` / `Blocked` 状态判断行程是否可启动，不把权限阻断、定位质量和行程模式混为同一状态。
- 阻断原因已区分首次缺少定位、永久拒绝定位、仅近似定位、系统 GPS Provider 关闭、首次缺少通知和永久拒绝通知；API 27 不把通知运行时权限作为前置条件。
- Activity 记录权限请求历史，在权限回调、设置页返回、`onResume()` 和 Provider 变化时重新求值，并清理拒绝后的延迟启动意图；Service 启动、恢复和确保活动行程使用同一策略。
- 界面为不同阻断原因显示中文状态和对应的权限请求、应用设置、定位设置或通知设置入口；活动行程被阻断时保留结束入口。

本地验证证据：2026-08-07 完整本地关卡通过，包括 JVM、AndroidTest 编译、lint、lintVital、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease`；`TripAccessPolicyTest` 11/11，新增首次近似精度升级、升级仍可重试和升级拒绝后转设置三条边界。

Pixel_9 / API 35 验证证据：`gps-core` instrumentation 12/12、手机版 6/6；新增测试校验应用/定位/通知设置 Intent、初始未授权单屏无滚动，以及活动行程受阻时仍可结束。真实系统 UI 已覆盖精确、仅近似、近似升级拒绝、定位首次/永久拒绝、通知首次/永久拒绝、通知设置返回和系统定位关闭/恢复；所有分支均保持行程空闲，不自动开始或循环弹窗。证据位于本地 `artifacts/cargps-mobile-m5-*`。

跨 API 聚焦证据：API 27 验证未授权阻断、精确定位后可开始且不要求通知运行时权限；API 29 验证未授权、仅授予 `ACCESS_COARSE_LOCATION` 时显示“当前仅有大致位置”，授予精确定位后可开始；API 33 验证通知首次拒绝后仍可重试，第二次拒绝产生 `USER_FIXED` 并切换为“打开通知设置”，重新授权后可启动前台行程。三个环境的 `gps-core` 12/12、手机版 6/6 instrumentation 均通过。

剩余验收边界：

- 补齐 API 31 环境；在 API 27、29、31、33 完成位置首次/永久拒绝、系统定位关闭与恢复和设置返回，API 27/29 还需补齐位置拒绝后的完整重试路径。
- 前台服务所需位置权限必须在启动前满足；通知不可见时不能让用户误判后台记录状态。后台位置权限不是当前前台服务的默认前置条件。

发布前最小门槛：Pixel_9 / API 35 已完成；正式候选仍需结合第 3 节跨 API 关卡。任何失败分支都不得崩溃、循环请求或错误显示“正在记录”，通知不可见时也不能让用户误判后台记录状态。

完整验收门槛：在 API 27、29、31、33、35 覆盖精确、近似、首次拒绝、永久拒绝、系统定位关闭与恢复，并验证从系统设置返回后的状态收敛。

### M6 生命周期并发与真实设备长测

当前实现：

- `TripSessionEventQueue` 使用唯一 `Channel.UNLIMITED` actor，启动后固定先执行 Restore，再按入队顺序处理 Start、AppendPoint、Pause、Resume、End、Tick 和 Checkpoint。
- `DashboardRuntime.startTripAndAwait()` 等待协调器确认并同步发布 `DashboardState` 后返回；`TripStartOrchestrator` 的直接启动回调只在 Start 确认且权限仍可用时执行。
- `LocationEnginePolicy` 把 Service 状态归一为 `IDLE`、`START_PENDING` 和 `ACTIVE`：客户端不可见时只有 `ACTIVE` 可启动后台定位；客户端可见且定位权限可用时，`IDLE` 与 `START_PENDING` 仍保留仪表定位预览。
- 定位点、时钟、切换、结束和生命周期检查点已改为进入同一队列；Toggle 在消费时读取最新行程模式，避免入队时使用过期模式。
- 队列关闭会取消当前和缓冲区中的等待型命令；actor 未预期异常会关闭入口、失败等待者并拒绝后续事件，不能继续“假接收”。
- `onTaskRemoved()` 检查点仍是尽力执行，不能承诺进程回收前完成；队列只保证单进程内顺序，不提供跨进程 exactly-once 语义。

验证证据：

- 2026-08-08 本地 `gps-core` 44/44、手机版 24/24 JVM 通过；AndroidTest 编译、lint、lintVital、Debug/Release、R8、资源压缩和 benchmark 构建全部成功。
- `TripSessionEventQueueTest` 5/5，覆盖 Restore/FIFO、等待型 Start、关闭取消、actor 异常终止和连续 Toggle；协调器测试确认结束前尾点进入统计、End 后点明确拒绝，Runtime 测试确认等待型 Start 返回前已发布记录状态。
- `TripStartOrchestratorTest` 4/4，覆盖纯编排回调在确认前不调用 `startLocation`、Start 失败、等待期间权限失效和异常清理请求中标记；它不等价于 Service 全路径竞态测试。
- `LocationEnginePolicyTest` 5/5；修复前“不可见 Start 等待”稳定得到 `expected:<STOP> but was:<START>`，策略只允许 `ACTIVE` 后连续 3 次聚焦回归通过，同时保留可见空闲/等待预览。
- Pixel_9 / API 35：`gps-core` instrumentation 12/12、手机版 6/6；开始后为 `location` 前台服务，应用 UID 连续两次 ensure 后定位线程仍为 1，结束后历史增加一段，Home 后 Service 清除，crash buffer 无 CarGPS 记录。
- API 27/API 29：开始、Home、单定位线程、普通进程 `SIGKILL` 后 sticky 恢复、“已恢复”、结束历史增加和 Home 后定位线程清除均通过。API 33 在通知完整授权后完成开始、前台通知、单定位线程和结束清理。

尚未验证的边界：

- 可见页面的定位预览按产品语义可在 Start 确认前运行，不能把“定位引擎已运行”直接等价为“行程已开始”；正式候选仍需在设备上复核预览样本不会在 Runtime `IDLE` 时写入活动行程。
- 冷启动恢复与首点、Activity 重建、服务重连和 `onTaskRemoved()` 并发仍需在 API 27/API 29 的完整 30 分钟关卡中复核；当前只证明聚焦短路径。
- 在前台服务和存储迁移完成后进行真实道路与静止长测，模拟器 Macrobenchmark 只保留为版本间相对基线。

验收门槛：至少一台 Android 12 以上手机和一台 API 27 环境完成 2 小时记录；收集定位点完整率、数据库增长、CPU、内存、耗电、温升、ANR 和崩溃。真实设备是否使用 Redmi 需由用户单独授权，不能沿用 Pixel_9 授权范围。

### M7 Baseline Profile 热路径刷新

落地状态：

- `generateStartup` 只采集授权后的稳定首屏并设置 `includeInStartupProfile = true`；`generateCriticalUserJourneys` 覆盖开始、Home、返回重绑 Service、暂停、继续和结束确认。
- Pixel_9 / API 35 生成 Baseline Profile 50,591 行、Startup Profile 49,417 行；旧 `DashboardViewModel` 与 Factory 命中为 0，新 Runtime、事件队列、Service、定位策略和 Room 路径均已命中。
- Release APK 内含新的 `assets/dexopt/baseline.prof` 与 `baseline.profm`。
- 两轮 5 次冷启动对照中，Baseline Profile 中位数分别从 306.48ms 降至 253.11ms、从 312.96ms 降至 256.06ms，改善约 17.4% 和 18.2%。
- 当前无预编译中位仍慢于 v0.2.0 历史 241.9ms，不能把 Profile 收益解释为架构扩展没有启动成本；模拟器数据也不能替代真机性能结论。

验收结论：当前代码已满足生成设备、旧类名归零、APK 二进制 Profile 和同 AVD 冷启动对照四项门槛。后续热路径代码发生变化时必须重新采集。

### M8 AGP 9

AGP 9 属于构建链迁移，不是当前最紧迫的运行时风险。应在 M5 至 M7 的行为和测试稳定后使用独立分支升级，避免把 Kotlin 内置支持、插件兼容和 R8 差异与权限/并发回归混在同一提交。

验收门槛：API 27 安装启动、JVM 与 instrumentation、Release R8、资源压缩、Baseline Profile 生成和 GitHub 发布链全部通过，APK 包名与签名身份不变。

## 5. 发布门禁

下一发布候选必须先通过第 3 节三组阻断关卡。即使通过，发布说明仍必须保留以下边界：模拟器性能不代表真机；事件队列只保证单进程内顺序；恢复上限是最后确认检查点而非零丢点；`force-stop`、断电、设备重启和低存储不属于当前已验证恢复结论；Room fixture 迁移已跨 API 验证，但正式旧包覆盖升级、API 31、完整位置权限矩阵和真实道路长测仍未完成。

每完成一个迁移项，应同步更新本文件状态、技术设计、测试基线和发布说明，并把对应自动化与设备证据放到 `artifacts/`，不能只修改计划文字。
