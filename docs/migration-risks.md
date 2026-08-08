# 手机版剩余高风险迁移项

作者：long

更新时间：2026-08-08 18:17:17（北京时间，UTC+8）

## 1. 当前基线

`v0.2.0` 已完成依赖升级、增量行程统计、定位回调线程化、SQLite 批量写入、R8、Baseline Profile 和 Pixel_9 发布验证。`v0.2.0` 之后的 M1-M6 已完成当前开发线的核心实现；M7 曾在 Pixel_9 重新生成 Baseline/Startup Profile 并完成两轮冷启动对照。2026-08-08 又完成 API 27、API 29、API 31、API 33 的聚焦 instrumentation、运行时回归和完整位置权限矩阵，在 API 27/API 29 完成公开正式 `v0.2.0` 到当前同证书 Release 的 SQLite v3 -> Room v4 覆盖升级，并分别在 Pixel_9 / API 35、Android 10 / API 29、Android 8.1 / API 27 完成 41 个样本、1831/1816/1816 秒的后台回归；API 27 另完成整机重启边界验证。最新开发线进一步把未确认定位点限制为 16 个：磁盘持续不可写时第 17 个点同步拒绝，Service 停止定位输入、保留前台通知和结束入口，成功确认尾批后自动恢复。普通持久化失败也已并入同一输入阻断策略；错误未被新检查点确认前，Service 停止定位注册，Runtime 不再向行程队列投递新点，并清除上一定位样本与速度平滑基线。JVM seam 已验证普通失败与背压两类恢复首点都不跨故障窗口补算距离、速度和确认点序列。新增的 Room/Runtime 专项又在真实 Room 连接上验证了同一故障进入背压链路：Pixel_9/API 35 与 Android 8.1/API 27 专项各 1/1，完整 `gps-core` instrumentation 各 14/14；前 16 点保留、第 17 点拒绝、活动行程不清除，恢复后 16 点检查点确认。事件 actor 未预期终止时现在会关闭入口、停止定位并发布“恢复中/终态失败”；每个 Runtime 最多自动重建一次，新队列先 Restore 已确认存储状态，初始 `START_STICKY` 会等待重建结果。真实 Service 生命周期 seam 在两档设备各 6/6，新增验证 End 前尾点保留、End 后定位拒绝，以及 actor 异常后的通知、定位停止和单次恢复重启；原有 Start 门禁、可见性重绑、可恢复写失败和任务移除 Checkpoint 场景继续通过。当前已通过 `gps-core` 58/58、手机版 33/33 JVM、AndroidTest 编译、lint、lintVital、Debug/Release、R8、资源压缩和 benchmark 构建；两档设备完整 `gps-core` instrumentation 各 14/14、手机版各 12/12。M1-M7 尚未随新版本发布；物理低存储下的通知与真实 GPS 恢复、actor 连续终态失败、活动行程 Activity/Service 复杂重建、Checkpoint 完成前的进程回收、尾批损失量化、Profile 重采集、真实设备和最终候选复验仍未完成。当前开发构建仍沿用 `0.2.0 (3)`，并继续保留 `minSdk = 27`。

### 1.1 证据分层

- **已验证（存储层）**：Room 实际打开的 `SupportSQLiteDatabase` 被置为 `PRAGMA query_only = ON` 后，批量事务返回 `SQLiteException`；此前已确认的活动轨迹和 `ActiveTripCheckpoint` 保留，失败批次没有部分写入。Pixel_9/API 35 与 Android 8.1/API 27 的存储类均为 13/13。
- **已验证（Runtime/Room 连接级集成）**：`RoomRuntimeBackpressureInstrumentedTest` 在同一真实 Room 连接故障上验证前 16 点保留、第 17 点同步拒绝、`RECORDING` 与最后确认边界保留，恢复可写后 16 点重新确认；Pixel_9/API 35 与 API 27 各 1/1，当前完整 `gps-core` instrumentation 各 14/14。
- **已验证（JVM 运行时 seam）**：16 点未确认上限、第 17 点拒绝、背压后的检查点恢复，以及恢复首点不跨故障窗口补算距离/速度/确认序列。
- **已验证（Service 定位控制器 seam）**：`LocationEngineSessionControllerTest` 4/4 覆盖 Start 等待期间不可见不启动、Activity 重绑/`START_STICKY` 恢复重复到达只启动一次、存储失败停止并在新检查点后重启，以及底层启动失败不缓存错误动作；`LocationEngine.start()` 同时对部分注册失败执行回滚。该层是可注入的 JVM seam，不等价于真实 Service 的设备级故障注入。
- **已验证（真实 Service 生命周期 seam）**：`TripRecordingServiceLifecycleInstrumentedTest` 在 Pixel_9/API 35 与 API 27 各 6/6，使用真实 Service 和真实 `DashboardRuntime` 验证 Start 存储确认前定位启动次数为 0、确认后只启动一次，Activity 可见性解绑/重绑的启停幂等，注入式可恢复写失败后通知立即显示“等待存储恢复”、结束入口保留、定位停止并在检查点恢复后只重启一次，任务移除 Checkpoint 在 Service 销毁和等待协程取消后仍由 Runtime 完成，End 前尾点纳入最终事务、End 后到达定位被拒绝，以及 actor 异常时通知显示“行程处理异常”、定位停止并从确认状态单次恢复；存储与系统 GPS 注册由测试边界隔离。
- **未验证（发布阻断）**：物理磁盘 `ENOSPC`/低存储下的通知与真实 GPS 注册停止/恢复、actor 连续终态失败、活动行程 Activity/Service 复杂重建、Checkpoint 完成前整个进程被回收、尾批损失量化、最终候选版本号升级复验、热路径 Profile 重采集和真实设备长测。

M2 已消除“Activity 退到后台就主动停止定位”的旧结构。API 27/API 29 已验证开始、Home、单定位线程、普通进程 `SIGKILL` 后 `START_STICKY` 恢复、结束和资源清理；API 31 已验证开始、Home、锁屏、单定位线程、活动行程撤权后的受阻结束和资源清理，但未执行普通进程恢复。Pixel_9 / API 35、Android 10 / API 29 和 Android 8.1 / API 27 已完成 Home、系统设置、锁屏睡眠和解锁返回的完整 30 分钟监测，期间同一 PID、前台服务、活动通知和单定位线程持续，正常结束后资源完整清理。API 27 整机重启边界已补测为“活动数据保留、系统不自动拉起、用户打开应用后恢复”。三档模拟器长测均通过，剩余风险转为低存储、异常竞态、最终候选和真机证据。

本清单只记录会改变运行时语义、数据完整性或发布兼容性的迁移。普通 UI 微调和低风险依赖补丁不进入本清单。

## 2. 风险总览

| 编号 | 优先级 | 状态 | 迁移项 | 当前风险 | 完成前不能宣称 |
| --- | --- | --- | --- | --- | --- |
| M1 | P0 | 核心、16 点背压、恢复首点 JVM、Room/Runtime 连接级故障与 Service 可恢复失败 seam 已验证，待物理低存储/发版 | 行程状态写入确认与单一所有者 | 命令已串行并等待存储确认；同步点写失败、背压和定位分段断点进入统一失败流；连接级故障已保留确认边界，Service 注入故障已验证通知和定位编排；未承诺跨进程 exactly-once 命令语义 | 已在正式版本交付、物理低存储无损或断电零丢点 |
| M2 | P0 | 常规 API 27/29/35 30 分钟、API 27 重启边界与注入式 Service 故障编排已验证，待物理低存储/发版 | 定位前台服务 | Service seam 已验证故障通知、定位停止和检查点恢复重启；真实低存储下的系统 GPS 注册、恢复耗时、厂商电源管理和真实道路长时行为仍未验收；当前不支持开机自动拉起 | 已覆盖物理低存储、所有厂商后台场景或开机自动恢复 |
| M3 | P0 | API 27/29 `SIGKILL` 恢复、API 27 重启数据保留、恢复首点 JVM 与任务移除 Service seam 已验证，待尾批/异常场景/发版 | 进程异常退出与最后批次恢复 | 确认边界支持晚到订阅者读取；任务移除 Checkpoint 可跨 Service 销毁继续完成，但整个进程在完成前被回收时仍只保证最近确认边界。正常批次延迟约 1 秒，异常写失败时最多 16 个未确认点且不保证时间上限；设备级尾批损失仍未量化，重启后需用户打开应用 | 杀进程、断电或 `force-stop` 零丢点，或宣称开机自动继续 |
| M4 | P1 | API 27/29 正式升级已验证，待最终候选复验/发版 | Schema 完整性与 Room 迁移 | 当前代码与签名升级链已通过，但开发构建仍和旧包同为 `0.2.0 (3)` | 最终候选版本的升级链已验收，或任意物理损坏都能自动恢复 |
| M5 | P1 | 跨 API 完整矩阵已验证，待发版 | 权限状态机 | 当前无未完成运行时分支；后续修改 Activity、Service 或权限策略必须重跑完整矩阵 | 已在正式版本交付 |
| M6 | P1；阻断发版 | API 27/29/35 30 分钟、本地完整关卡、Pixel_9/API 27 完整套件及真实 Service 6/6 seam 已通过；异常环境、低存储与真机待验收 | 生命周期并发与真机长测 | 不可见 Start 等待已禁止后台定位；控制器 4/4 JVM 与 Service 生命周期 6/6 设备 seam 已覆盖确认门禁、重绑幂等、注入式可恢复故障、已排队 Checkpoint、End 前后定位顺序和 actor 单次恢复，但 Checkpoint 完成前的进程回收、actor 连续终态失败、真实低存储/系统 GPS、复杂重建、尾批量化和真实设备仍未完成 | 最后定位点必不丢失或已验证真实道路和长时功耗 |
| M7 | P1；重新进入候选门禁 | 旧 Profile 已验证，当前热路径改动后待重采集 | Baseline Profile 热路径刷新 | `DashboardRuntime`、Service、定位策略和存储路径已经变化，旧 Profile 不再代表最终候选；无预编译启动仍比 v0.2.0 历史基线慢 | 当前 Profile 已覆盖最终代码、整体启动无回退或模拟器收益等同真机 |
| M8 | P2 | 延后 | AGP 9 | 构建链仍为 AGP 8.13.2，升级可能引入插件与 R8 差异 | 已迁移到最新构建系统 |

## 3. 下一版发布阻断摘要

M1-M6 的核心实现已经通过本地关卡、Pixel_9 / API 35，以及 API 27/29/31/33 聚焦验证；最新 16 点背压和 actor 单次恢复加固后又通过完整本地构建，并在 Pixel_9 / API 35 与 API 27 各完成 26 项当前 instrumentation（`gps-core` 14/14 + 手机版 12/12）。M5 跨 API 完整权限矩阵已闭环，API 27/API 29 的公开正式旧包覆盖升级也已完成。API 27/29/35 的 30 分钟后台回归和 API 27 整机重启边界已经通过，但物理低存储、Service 其余异常环境、最终候选身份、真实设备和热路径 Profile 重采集仍未完成，因此还不能直接发版。发布候选至少还要完成以下四组关卡：

1. **跨 API 运行时**：Pixel_9 / API 35、Android 10 / API 29、Android 8.1 / API 27 已分别完成 41 个样本、1831/1816/1816 秒的 Home、系统设置、锁屏和解锁返回回归；跨 API 30 分钟门禁已完成，后续转入异常环境验证。
2. **低存储与事件顺序**：Room 实际连接的永久只读写失败已经验证存储层原子性（API 35/API 27 存储类各 13/13），Runtime/Room 专项在两档设备各 1/1、完整 `gps-core` 各 14/14 验证前 16 点保留、第 17 点拒绝和恢复后检查点确认；真实 Service 6/6 seam 又验证 Start 确认门禁、可见性重绑、注入式可恢复写失败、任务移除 Checkpoint、End 前后定位顺序和 actor 单次恢复。仍需制造物理 `ENOSPC`/低存储并接入真实 Room 与系统 GPS 注册，量化恢复耗时和进程终止前后的尾批损失，并补冷启动恢复首点、活动行程 Activity/Service 复杂重建、actor 连续终态失败和 Checkpoint 完成前整个进程被回收的设备场景。API 27 整机重启已证明当前边界是“用户打开应用后恢复”，不应写成开机自动继续。
3. **最终候选身份与升级链**：提升 `versionCode`/`versionName`，生成最终签名 Release；从公开 `v0.2.0` 在 API 27/API 29 覆盖安装，重新核对证书、版本、Room v4、活动行程数据和前台服务恢复。当前同版本号 APK 的成功结果不能替代这一步。
4. **候选性能与真机**：背压改动触及 `DashboardRuntime`、Service、定位策略和存储热路径，必须重新生成 Baseline/Startup Profile 并复跑冷启动对照；取得单独设备授权后，再完成 Android 12+ 真机和 API 27 环境的 2 小时记录与功耗、温升、ANR、崩溃采集。

权限最小闭环已经完成：API 27/29/31/33/35 的适用精确/近似、首次/永久拒绝、系统定位关闭/恢复和设置返回均已验收，API 33/35 通知拒绝矩阵也已通过。后续只有相关代码发生变化时才重新进入发布阻断。

性能资产一致性关卡曾在上一版 M1-M6 代码上通过；本轮已经修改 Service、Runtime 和存储热路径，因此该证据现在只保留为历史基线，发布候选必须重新生成并复核。

Room schema 与显式迁移实现已经完成，API 27/29/33 上的 12 个 fixture/事务/损坏门禁 instrumentation 均已通过；API 27/API 29 的公开正式旧包升级也已保留真实数据库证据。Room 只读连接故障用例在 API 35/API 27 存储类各 13/13 通过，Runtime/Room 背压专项各 1/1、完整 `gps-core` 各 14/14，证明连接级永久写失败不会清除已确认活动数据，并能在运行时保留 16 点尾批后恢复检查点，但不等价于物理 `ENOSPC`。API 27/29/35 的 30 分钟结果和 API 27 重启数据保留结果已完成，JVM seam 又补齐了点写失败统一失败流、确认边界 replay、任务移除等待、Start 清理竞态、16 点有界背压、两类取消策略和 actor 单次恢复；真实 Service 6/6 seam 已覆盖注入故障、End 前后定位顺序和 actor 自动恢复，并证明已排队 Checkpoint 不会随 Service 协程取消而丢弃。剩余发布风险集中为物理低存储下的真实 Room/系统 GPS 恢复、actor 连续终态失败、活动行程 Activity/Service 复杂重建、Checkpoint 完成前的进程回收、尾批损失量化、最终版本号候选是否仍保持同一升级结果、Profile 重采集，以及真实设备 2 小时长测。当前开机自动恢复不是已交付能力；若产品要改变这一边界，必须单独评审后台位置权限和系统启动规则。AGP 9 仍需分阶段推进，不能与异常恢复和最终候选复验塞入同一次架构改动。真实设备测试必须取得单独设备授权；当前模拟器授权不能自动扩展到 Redmi。

### 当前剩余高风险摘要

| 优先级 | 剩余项 | 已有证据 | 下一步与发布影响 |
| --- | --- | --- | --- |
| P0 | 物理低存储与尾批损失量化 | Room/SQLite 真实只读连接失败已在 API 35/API 27 存储类各 13/13 通过；Runtime/Room 专项在两档设备各 1/1、完整 `gps-core` 各 14/14 验证未确认点最多 16 个、第 17 个拒绝、恢复后原尾批确认和配额释放；Service 6/6 seam 已验证注入故障后的通知、结束入口、定位停止/重启、End 前后定位顺序和 actor 单次恢复，并验证任务移除 Checkpoint 跨 Service 销毁继续完成；JVM seam 已验证拒绝点不会成为恢复首点的距离/速度基准 | 制造物理 `ENOSPC`/低存储并接入真实 Room 与系统 GPS，断言恢复耗时和实际注册变化；量化进程终止前后的确认点与丢点窗口，完成前不能宣称零丢点 |
| P0 | 最终候选身份与覆盖升级 | API 27/29 已用公开 `v0.2.0` 同证书验证 Room v4 无损迁移 | 提升版本号，生成最终签名 Release，再重复 API 27/API 29 覆盖升级；完成前不能发新版本 |
| P1 | Service/队列异常竞态 | JVM seam 已覆盖 Start 状态清理、检查点等待、重绑 replay、背压停止、队列取消和 actor 单次恢复策略；真实 Service seam 在 API 35/API 27 各 6/6 覆盖 Start 确认门禁、可见性重绑、注入式故障恢复、已排队 Checkpoint、End 前后定位顺序和 actor 自动恢复；三档常规 30 分钟已通过 | 补活动行程 Activity/Service 复杂重建、actor 连续终态失败、物理低存储/真实 GPS 恢复和 Checkpoint 完成前整个进程被回收的设备集成测试；当前仍是 M6 发布阻断 |
| P1 | 真实设备 2 小时长测 | 目前只有受控模拟器证据 | 取得单独设备授权后在 Android 12+ 真机和 API 27 环境采集完整率、功耗、温升、ANR、崩溃；不能把 Redmi 授权默认视为已取得 |
| P1 | Profile 与启动性能复验 | 旧 Profile 在上一版热路径上有效，两轮模拟器中位改善约 17.4%/18.2% | 当前 Service/Runtime/存储热路径已变化，重新生成 Baseline/Startup Profile、检查旧类归零和新路径命中，并复跑同 AVD 冷启动对照 |
| P1（产品决策） | 开机自动恢复定位 | API 27 已验证当前不自动拉起、打开应用后可恢复 | 若要改成自动继续，需新增 receiver、后台位置权限/Play 政策评审和 API 29/34/35/厂商电源测试；当前产品不以此阻断发布 |
| P2 | AGP 9 | 已决定延后，当前 AGP 8.13.2 稳定 | 独立分支迁移，完成 API 27、R8、Profile 和发布链回归；不与运行时风险混合 |

P0 低存储项仍包括设备级恢复首点的定位分段校验：Room/SQLite 只读连接测试和 Runtime/Room 专项已证明连接级失败不会清除已确认数据，前 16 点可保留并在恢复后确认，JVM `DashboardRuntimePersistenceTest` 已证明被拒绝样本不会成为恢复后第一点的连接基准，Service seam 已验证注入故障的通知与定位编排；但物理 `ENOSPC`、复杂 Service 回调竞态和真实系统 GPS 停止/恢复仍需设备证据。

## 4. 推荐迁移顺序

### M1 行程状态写入确认与单一所有者

落地状态：

- `TripSessionCoordinator` 已成为进程内唯一行程会话所有者，使用同一串行入口处理恢复、开始、暂停、恢复、结束、定位点和时钟事件。
- `DashboardRuntime` 不直接持有 `TripAccumulator`、开始时间或暂停累计，只把协调器的只读状态映射到仪表状态。
- 开始、暂停、恢复和结束等元数据命令先发布“处理中”，等待 `awaitPendingWrites()` 确认后再切换模式；失败时保留上一个已确认模式并显示存储异常。定位点可先推进实时统计，但只有批次事务成功后才推进 `ActiveTripCheckpoint`。
- 重复开始、暂停、恢复和结束在当前进程内为 no-op；轨迹批次瞬时失败重试成功不误报，最终失败进入统一错误流。
- `TripSessionCoordinatorTest` 覆盖恢复、四类元数据失败、同步点写失败、16 点背压、重复命令、两段暂停统计和异步错误；队列测试覆盖瞬时重试、终态失败、未确认配额上限、恢复后继续写入及元数据屏障。`TripStorageFailureIntegrationTest` 组合协调器与真实队列验证失败检查点保持和尾批恢复。

验证证据：M1 初始门禁为 2026-08-07 本地 `gps-core` 32/32、Pixel_9 SQLite 4/4 和 Compose 1/1；最新背压、普通存储失败输入阻断、恢复首点分段、队列取消语义和 actor 单次恢复加固后完整 JVM 为 `gps-core` 58/58、手机版 33/33，并通过完整本地构建。Pixel_9/API 35 与 API 27 当前完整 `gps-core` instrumentation 各 14/14、手机版各 12/12，Runtime/Room 背压专项各 1/1；存储类另为 13/13。`DashboardRuntimePersistenceTest` 11/11 验证背压与普通错误恢复首点、actor 异常立即阻断、初始 Restore 等待单次重建，以及第二次 actor 异常进入终态；`TripSessionEventQueueTest` 7/7 验证恢复/FIFO、取消和终止传播。`TripRecordingServiceLifecycleInstrumentedTest` 在两档设备各 6/6 验证真实 Service 的 Start 存储确认门禁、前台服务升起、可见性重绑、注入式可恢复写失败、任务移除 Checkpoint、End 前后定位顺序和 actor 单次恢复；该 seam 使用真实 `DashboardRuntime`，但存储与系统 GPS 仍由测试边界隔离，不覆盖物理低存储、actor 连续终态失败或 Checkpoint 完成前整个进程被回收。`RoomRuntimeBackpressureInstrumentedTest` 又在 Room 实际连接上验证前 16 点保留、第 17 点拒绝、活动行程不清除和恢复后检查点确认；摘要为 `artifacts/cargps-mobile-runtime-backpressure-summary.md`。`SqliteTripStorageInstrumentedTest.roomStorageKeepsActiveTripWhenSqliteConnectionIsReadOnly` 的存储层原子失败摘要仍见 `artifacts/cargps-mobile-storage-readonly-summary.md`。截图与 UI 树仍为初始 M1 的 `artifacts/cargps-mobile-m1-pixel9.png`、`artifacts/cargps-mobile-m1-pixel9.xml`；当前没有物理低存储或真实系统 GPS 恢复证据。

剩余边界：当前幂等保证针对单个 coordinator 进程；M3 已补齐服务重投和最后确认点边界。当前内部 Intent 与恢复策略不承诺跨进程 exactly-once 命令语义，也没有持久化命令账本，不能据此宣称断电零丢点。

### M2 定位前台服务

依赖：先确定 M1 的会话所有权，避免 Activity 和 Service 同时维护两份行程状态。

落地状态：

- `CarGpsApplication` 只创建一个 `DashboardRuntime` 和一条数据库写队列；Activity 重建和 Service 重连复用同一 `TripSessionCoordinator`。
- `TripRecordingService` 是唯一 `LocationEngine` 所有者，每秒向共享 Runtime 投递 Tick；Activity 只绑定本地 Binder、观察同一 `StateFlow` 并发送命令。
- 用户必须在可见 Activity 内明确开始行程，再调用 `startForegroundService()`。活动行程和暂停状态均保持前台服务，结束确认后移除通知并停止 Service。
- Manifest 已声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_LOCATION` 和 `android:foregroundServiceType="location"`；服务 `exported=false`，所有内部 Intent 均为显式目标，通知 `PendingIntent` 使用 `FLAG_IMMUTABLE`，不接收或转发 nested Intent。
- 通知提供返回应用和“结束行程”，只在模式变化、每 10 米或最多每 5 秒刷新；未申请 `ACCESS_BACKGROUND_LOCATION`，也不允许任意后台时刻新建服务。
- 普通持久化失败或未确认点达到 16 个时，`LocationEnginePolicy` 优先停止定位；Service 保留前台状态和结束入口，通知改为“等待存储恢复”。后台尾批成功落库并发布新检查点后清除错误/背压并恢复定位。

验证证据：Pixel_9 / API 35 上 SQLite instrumentation 4/4、`mobile-app` instrumentation 3/3；运行时服务为 `location` 前台类型，通知和结束操作可用。Home、锁屏、Activity 重建、重复打开、覆盖安装触发进程重建和通知结束短路径通过，定位线程始终为 1，crash buffer 为空。2026-08-08 又完成 API 35、API 29、API 27 各 41 个样本的完整后台回归，实际 1831/1816/1816 秒；三轮 Home、系统设置、锁屏和解锁返回期间 PID 均未变化、前台服务和通知持续、活动 GPS 注册始终为 1、crash buffer 始终为 0，正常结束后历史增加且资源归零。API 27 的定位线程使用 `/proc` 侧车 115 次采样确认始终为 1。证据位于本地 `artifacts/cargps-mobile-api35-30min-*`、`artifacts/cargps-mobile-api29-30min-*`、`artifacts/cargps-mobile-api27-30min-*`。

未完成门槛：三档模拟器 30 分钟门禁和 API 27 整机重启边界已通过；Room/Runtime 连接级背压专项已证明活动行程、16 点尾批和检查点恢复边界，但仍需用物理 `ENOSPC` 验证通知、GPS 注册停止、结束入口、恢复耗时和自动恢复定位，并量化异常终止前后的尾批损失、冷启动恢复首点、Activity 重建与 Service 重连竞态。2026-08-07 被外部 `force-stop` 中断的旧监测仍然无效，不与后续成功结果合并计数。当前手机版不注册 `BOOT_COMPLETED`，因此重启后需用户打开应用恢复；若改变这一边界，另立后台位置权限与系统启动迁移。

官方依据：

- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://developer.android.com/develop/background-work/services/fgs/launch

### M3 进程异常退出与最后批次恢复

依赖：M1/M2 的会话所有权已经稳定。

落地状态：

- `ActiveTripCheckpoint` 已明确记录活动行程开始时间、确认点数、最后确认 sequence 和最后确认时间；Room v4 沿用既有表字段查询该边界。
- `QueuedTripStorage` 在接收点前原子预留未确认配额，最多 16 点；批量事务成功并移除内存点后释放配额、发布检查点，协调器只接受当前活动行程的确认通知。第 17 个点同步拒绝且不进入实时累计；生命周期 `Checkpoint` 命令强制等待尾批次并更新 Runtime。
- `TripRecordingService.onTaskRemoved()` 以 `CoroutineStart.UNDISPATCHED` 启动等待逻辑，确保回调返回前把 Checkpoint 同步交给应用级 Runtime；该命令使用 `KEEP_QUEUED`，所以 Service 销毁和等待协程取消不会移除已经排队的尾批检查点。若系统未调用回调，或整个进程在 Checkpoint 完成前被回收，则仍以最近一次定时批次确认边界恢复；正常批次延迟约 1 秒，异常写失败时只保证未确认点最多 16 个，不保证时间上限。
- Service 收到 `START_STICKY` 的 null Intent 后先升恢复通知并等待 `DashboardRuntime.awaitInitialRestore()`，不再根据新进程初始 `IDLE` 自停。
- 恢复成功后恢复活动模式、暂停累计、已落库轨迹和确认检查点，并断开跨进程定位段；无活动行程或恢复失败后才停止 Service。

验证证据：本地 `gps-core` 36/36、手机版策略 4/4；Pixel_9 SQLite 5/5、手机版 3/3。对活动行程 PID 发送 `SIGKILL` 后，系统自动以新 PID 恢复 `location` 前台服务、通知、单定位线程和“记录中 / 已恢复”状态，结束后资源正常清除，crash buffer 为空。

补充验证证据：API 27 的活动行程从 PID `3424` 恢复为 `3618`，API 29 从 PID `3587` 恢复为 `4159`；两者 `restartCount = 1`、前台服务和单条 `cargps-location` 线程恢复，Activity 返回后显示“记录中 / 已恢复”。API 29 使用可 root 的 `google_apis` AVD 发送真实 `SIGKILL`，未使用 `force-stop`。

整机重启边界验证：API 27 活动行程在普通 `adb reboot` 后 `active_trip` 仍保留；未打开应用前没有手机版进程、Service、通知或 GPS 注册，手动打开后 UI 显示“记录中 / 已恢复”，Service 和 GPS 注册恢复，结束后资源清理。该结果已保存于 `artifacts/cargps-mobile-api27-reboot-summary.md`。

剩余门槛：已知点序列在进程终止前后的损失窗口量化、物理低存储下 16 点边界与恢复耗时、Activity 重建/Service 重连，以及 Checkpoint 完成前整个进程被回收的场景仍未完成；Room 只读连接测试已覆盖存储层失败保留，但尚未在真实 Service 背压路径和回调竞态中验收恢复首点分段。`force-stop` 会阻止系统自动启动，整机重启当前也不会自动启动，不能与普通进程回收混为一谈。若未来要支持开机自动恢复，必须单独处理 `BOOT_COMPLETED`、后台位置权限和 Android 14/15 规则。

官方边界（2026-08-08 重新核验）：

- [后台启动前台服务限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)：Android 14+ 对需要 while-in-use 位置权限的前台服务在后台创建有额外限制；location 服务在后台持续访问位置时需单独评估 `ACCESS_BACKGROUND_LOCATION`。
- [Android 15 行为变化](https://developer.android.com/about/versions/15/behavior-changes-15)：`BOOT_COMPLETED` 接收器启动 `dataSync`、`camera`、`mediaPlayback`、`phoneCall`、`mediaProjection`、`microphone` 等类型受限；location 不在该列表中，但这不等于绕过位置 while-in-use 或后台位置权限要求。
- [location 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#location)：location 类型需要位置服务开启及至少一项运行时位置权限。
- [后台位置访问](https://developer.android.com/develop/sensors-and-location/location/background)：Android 8.0+ 对后台位置更新有限制；后台位置权限应仅用于核心功能并需单独评审。
- [后台优化](https://developer.android.com/topic/performance/background-optimization)：目标 API 33+ 的应用若被用户置为受限状态，系统可能延迟 `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`，直到应用因其他原因启动。

### M4 Schema 完整性与 Room 迁移

落地状态：

- 生产装配已从 `SqliteTripStorage` 切换到 `RoomTripStorage`，使用 Room 2.8.4 和 schema v4；`SqliteTripStorage` 只保留为旧 schema fixture 与兼容验证工具。
- Room 注册显式 `MIGRATION_1_2`、`MIGRATION_2_3`、`MIGRATION_3_4` 并导出 v4 schema，未配置 destructive fallback。v3 到 v4 通过事务内改名、建表和复制建立 Room schema identity，失败时整体回滚。
- `ActiveTripLoadResult.Empty`、`Loaded`、`Corrupt` 已区分“没有活动行程”和“活动行程损坏”。非法 mode 返回 `Corrupt`，保留原始行；协调器令 `storageReady = false` 并拒绝开始新行程。
- Pixel_9 / API 35 以及 API 27、API 29、API 33 均已验证 v1 到 v4 活动行程与轨迹、v2 到 v4 历史统计与轨迹、v3 到 v4 暂停行程、v4 完整 `TripStorage` 事务契约、非法 mode 原始行保留，以及缺失 `total_paused` 时迁移失败后保留 v3 版本和旧表数据；原有 M4 套件每个 API 环境均为 12/12，存储类为 13/13，新增 Runtime/Room 背压专项为 1/1，当前完整 `gps-core` instrumentation 为 14/14。

覆盖升级证据：Debug 同签名组在 API 27 保留 39 点/33.50 米、API 29 保留 29 点/33.50 米；公开正式 `v0.2.0` 同证书组在 API 27 保留 29 点/37.62 米、API 29 保留 30 点/28.74 米。四组来源库均为 SQLite v3，覆盖后均为 Room v4，活动模式、开始时间、点数、距离、sequence 和点时间范围保持；正式组还恢复“记录中 / 已恢复”、前台服务和单定位线程。Room identity hash 为 `f87ebb25691d962beb3c76e9a6f9a505`。

剩余门槛：当前公开旧包与开发构建都报告 `0.2.0 (3)`。最终发布候选必须先提升版本号，再在 API 27/API 29 重复同证书覆盖升级，并保留完整 instrumentation、lint、Release 构建和数据库证据。当前结果不宣称任意文件级物理损坏都能自动恢复。

### M5 权限状态机

落地状态：

- `TripAccessPolicy` 以独立的 `Ready` / `Blocked` 状态判断行程是否可启动，不把权限阻断、定位质量和行程模式混为同一状态。
- 阻断原因已区分首次缺少定位、永久拒绝定位、仅近似定位、系统 GPS Provider 关闭、首次缺少通知和永久拒绝通知；API 27 不把通知运行时权限作为前置条件。
- Activity 记录权限请求历史，在权限回调、设置页返回、`onResume()` 和 Provider 变化时重新求值，并清理拒绝后的延迟启动意图；Service 启动、恢复和确保活动行程使用同一策略。
- 界面为不同阻断原因显示中文状态和对应的权限请求、应用设置、定位设置或通知设置入口；活动行程被阻断时保留结束入口。

本地验证证据：2026-08-07 完整本地关卡通过，包括 JVM、AndroidTest 编译、lint、lintVital、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease`；`TripAccessPolicyTest` 11/11，新增首次近似精度升级、升级仍可重试和升级拒绝后转设置三条边界。

Pixel_9 / API 35 验证证据：`gps-core` instrumentation 12/12、手机版 6/6；新增测试校验应用/定位/通知设置 Intent、初始未授权单屏无滚动，以及活动行程受阻时仍可结束。真实系统 UI 已覆盖精确、仅近似、近似升级拒绝、定位首次/永久拒绝、通知首次/永久拒绝、通知设置返回和系统定位关闭/恢复；所有分支均保持行程空闲，不自动开始或循环弹窗。证据位于本地 `artifacts/cargps-mobile-m5-*`。

跨 API 完整矩阵证据：API 27 首次拒绝后保留“允许精确定位”，第二次系统弹窗勾选“不再询问”后切换“打开应用设置”；在应用设置授权和系统定位设置恢复后均回到“等待开始”，不误启动行程。API 29 首次拒绝可重试，第二次选择 `Deny & don’t ask again` 后精确与粗略权限均产生 `USER_FIXED`；应用设置授权、系统定位关闭/恢复和仅粗略定位分支均通过。API 33 完成 Approximate -> Precise 升级、首次/永久位置拒绝、应用设置授权、系统定位关闭/恢复，以及既有通知首次/永久拒绝矩阵。三个环境的 `gps-core` 12/12、手机版 6/6 instrumentation 均通过，crash buffer 无 CarGPS 记录。

API 31 完整位置矩阵证据：新增 `CarGPS_Pixel_9_API31 / Google APIs ARM64 / 1080x2424` AVD，`gps-core` 12/12、手机版 6/6 instrumentation 通过。真实系统弹窗验证 Approximate 后显示“当前仅有大致位置”，再次请求出现专用精度升级对话框并成功切换 Precise；首次拒绝保留请求入口，第二次拒绝产生 `USER_FIXED` 并切换“打开应用设置”。应用设置和系统定位设置返回后状态收敛且不误启动行程；活动行程撤销位置权限后显示“记录受阻”、GPS provider 变为 `OFF`，同时保留结束入口。精确权限成功后可见 Activity 启动 `location` 前台服务，Home 与锁屏短路径保持通知和单定位线程，结束后 Home 清理服务和定位注册。

测试夹具修复：API 27 首次自动化复验时，通用 Compose 测试 Activity 未继承 `MainActivity` 的竖屏声明，导致两个底部权限入口断言在 1024x600 横屏中失败。`TripAccessInstrumentedTest` 现使用 UiAutomator 把通用测试宿主固定为竖屏并在测试后解冻；从横屏 `SurfaceOrientation: 0` 启动后，API 27 的手机版 6/6 通过，API 29/33 也各复验 6/6。生产 UI 和可见性断言均未放宽。

当前维护边界：

- API 27、29、31、33、35 的完整位置权限矩阵已经满足；API 33/35 的通知权限矩阵也已满足。后续修改权限请求、设置返回、Activity/Service 启动或阻断文案时必须整组重跑。
- 前台服务所需位置权限必须在启动前满足；通知不可见时不能让用户误判后台记录状态。后台位置权限不是当前前台服务的默认前置条件。
- 任何失败分支都不得崩溃、循环请求或错误显示“正在记录”，从系统设置返回不得自动开始行程。

### M6 生命周期并发与真实设备长测

当前实现：

- `TripSessionEventQueue` 使用唯一 `Channel.UNLIMITED` actor，启动后固定先执行 Restore，再按入队顺序处理 Start、AppendPoint、Pause、Resume、End、Tick 和 Checkpoint。
- `DashboardRuntime.startTripAndAwait()` 等待协调器确认并同步发布 `DashboardState` 后返回；`DashboardRuntime.checkpointTripWritesAndAwait()` 等待尾批检查点完成后返回；`TripStartOrchestrator` 的直接启动回调只在 Start 确认且权限仍可用时执行，并在清除 `startRequested` 前先处理最终状态。
- `LocationEnginePolicy` 把 Service 状态归一为 `IDLE`、`START_PENDING` 和 `ACTIVE`：客户端不可见时只有 `ACTIVE` 可启动后台定位；客户端可见且定位权限可用时，`IDLE` 与 `START_PENDING` 仍保留仪表定位预览。存储背压优先级最高，无论客户端是否可见都停止定位输入。
- 定位点、时钟、切换、结束和生命周期检查点已改为进入同一队列；Toggle 在消费时读取最新行程模式，避免入队时使用过期模式。
- 队列关闭会取消当前和缓冲区中的等待型命令；actor 未预期异常会关闭入口、失败等待者并拒绝后续事件，不能继续“假接收”。Runtime 随即停止定位输入、发布 `tripRuntimeRecovering` 和可见错误，并在本 Runtime 生命周期内最多重建一次队列；新 actor 必须先 Restore 已确认存储状态，初始 `START_STICKY` 在此期间继续等待，第二次异常进入终态。Start 等用户命令采用 `SKIP_IF_NOT_STARTED`：调用方取消等待时取消完成信号，尚未开始消费的命令由 actor 跳过，避免生命周期结束后产生迟到行程副作用。生命周期 Checkpoint 采用 `KEEP_QUEUED`：等待方取消不移除已经入队的冲刷命令。
- `onTaskRemoved()` 使用 `CoroutineStart.UNDISPATCHED`，在回调返回前同步入队 Checkpoint；真实 Service seam 已验证 Service 随后销毁、等待协程取消后 Runtime 仍完成确认。该路径仍是单进程内尽力执行，不能承诺 Checkpoint 完成前整个进程不被回收，也不提供跨进程 exactly-once 语义。
- `LocationEngineSessionController` 收敛 Service 内的可见性刷新、Start 确认、恢复、权限/Provider 变化和存储故障入口；它只在目标动作变化时调用 `LocationEngine.start/stop`，启动返回失败或抛异常时不更新已执行动作缓存，后续事件可以重试。

验证证据：

- 2026-08-08 最新背压、普通持久化失败输入阻断、恢复首点分段、任务移除 Checkpoint 取消策略和 actor 单次恢复加固后 `gps-core` 58/58、手机版 33/33 JVM 通过；AndroidTest 编译、`lintDebug`、`lintVitalRelease`、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease` 完整通过。随后在 `ro.boot.qemu.avd_name = Pixel_9` 的 `emulator-5554 / API 35`，以及 `ro.kernel.qemu.avd_name = CASKA_1024x600`、Gradle 识别为 `CASKA_1024x600(AVD) - 8.1.0` 的 `emulator-5556 / API 27` 上各复验完整 `gps-core` 14/14、手机版 12/12 instrumentation；Runtime/Room 背压专项各 1/1，Service 生命周期 seam 各 6/6。API 27 最终轮曾出现一次 Runtime/Room 专项 15 秒等待超时，专项立即复跑 1/1，随后完整套件再次 14/14 通过；该记录保留为设备调度抖动，不作为功能失败。serial 不能视为永久映射。
- `TripSessionEventQueueTest` 7/7，覆盖 Restore/FIFO、等待型 Start、关闭取消、actor 异常终止、连续 Toggle、恢复阻塞期间取消等待后跳过尚未消费的 Start，以及取消生命周期等待后仍执行已排队 Checkpoint；协调器测试确认结束前尾点进入统计、End 后点明确拒绝。`DashboardRuntimePersistenceTest` 11/11 进一步确认等待型 Start 返回前已发布记录状态，普通存储失败与背压恢复首点不跨故障窗口，以及 actor 异常立即阻断定位输入、初始 Restore 等待单次自动重建、恢复后从确认边界重启、第二次异常进入终态。
- `TripStartOrchestratorTest` 5/5，覆盖纯编排回调在确认前不调用 `startLocation`、最终状态先于请求标记清理、Start 失败、等待期间权限失效和异常清理请求中标记；`LocationEngineSessionControllerTest` 4/4 覆盖跨入口幂等启动、存储失败停止/检查点恢复重启和启动失败重试；这些都是 JVM seam，不等价于 Service 全路径竞态测试。
- `TripRecordingServiceLifecycleInstrumentedTest` 在 Pixel_9/API 35 与 API 27 各 6/6，使用真实 Service、真实 `DashboardRuntime` 和测试存储/定位边界，验证 Start 存储确认前定位启动次数保持 0、确认后只启动 1 次，Activity 可见性解绑/重绑不会产生重复定位注册，可恢复写失败后通知立即切换、定位停止、结束入口保留并在检查点恢复后只重启一次，任务移除 Checkpoint 阻塞时销毁 Service、释放存储后 Runtime 仍完成确认，End 前尾点进入最终事务、End 后定位被拒绝，以及 actor 异常时通知显示错误、定位停止并从确认状态只恢复一次。
- `LocationEnginePolicyTest` 7/7；除不可见 Start 等待外，覆盖普通持久化失败与背压时停止定位，同时保留可见空闲/等待预览。
- Pixel_9 / API 35：既有运行时短路径和本轮 seam 加固后的 instrumentation 均为完整 `gps-core` 14/14、手机版 12/12，Runtime/Room 背压专项 1/1，Service 生命周期 seam 6/6；开始后为 `location` 前台服务，应用 UID 连续两次 ensure 后定位线程仍为 1，结束后历史增加一段，Home 后 Service 清除，crash buffer 无 CarGPS 记录。随后完整 30 分钟路径也通过：41 个样本、1831 秒内同一 PID、前台服务、通知和单定位线程持续；锁屏睡眠不丢失运行状态，结束后资源与 GPS 注册清理。Android 10 / API 29 与 Android 8.1 / API 27 的 41 个样本、1816 秒同级路径也通过，活动 GPS 注册始终为 1，结束后为 0；API 27 的定位线程由 `/proc` 侧车确认始终为 1。
- API 27/API 29：开始、Home、单定位线程、普通进程 `SIGKILL` 后 sticky 恢复、“已恢复”、结束历史增加和 Home 后定位线程清除均通过。API 31 完成开始、Home、锁屏、单定位线程、活动行程撤权阻断和结束后 Home 清理；API 33 在通知完整授权后完成开始、前台通知、单定位线程和结束清理。

尚未验证的边界：

- 可见页面的定位预览按产品语义可在 Start 确认前运行，不能把“定位引擎已运行”直接等价为“行程已开始”；正式候选仍需在设备上复核预览样本不会在 Runtime `IDLE` 时写入活动行程。
- 现有 Service seam 已覆盖 Start 存储确认、空闲预览的可见性解绑/重绑、注入式可恢复写失败、任务移除 Checkpoint、End 前后定位顺序和 actor 单次恢复；Room/Runtime 专项已覆盖连接级事务失败到 Runtime 背压。仍缺少活动行程 Activity/Service 复杂重建、actor 连续终态失败、物理低存储与真实系统 GPS 背压/恢复，以及 Checkpoint 完成前整个进程被回收的设备集成断言。
- 被背压拒绝的点不会进入协调器累计器，`DashboardRuntimePersistenceTest` 已断言失败/恢复后的首点会清除上一定位样本和速度平滑基线，确认点数只包含实际落库点；仍需在物理低存储和 Service 回调竞态中复核该分段边界，不能只凭 JVM seam 宣称设备级零丢点。
- 冷启动恢复与首点、活动行程 Activity/Service 复杂重建、actor 连续终态失败和 Checkpoint 完成前的进程回收仍需在三档 API 的异常场景中复核；30 分钟常规后台长测已通过，但没有注入这些异常竞态。存储层只读失败已通过 API 35/API 27 各 13/13，Runtime/Room 背压专项各 1/1，现有 Service seam 覆盖确认门禁、空闲预览重绑、单次可恢复写失败、End 前后定位顺序、actor 单次恢复和 Service 销毁取消，但不能替代物理低存储或上述其余异常路径证据。
- API 27 整机重启已验证为“不开机自动拉起，打开应用后恢复”；如果产品决定改成开机自动恢复，必须先完成 receiver、后台位置权限和 Android 14/15/厂商电源管理评审，不能把它作为普通 M6 小修补。
- 在前台服务和存储迁移完成后进行真实道路与静止长测，模拟器 Macrobenchmark 只保留为版本间相对基线。

验收门槛：至少一台 Android 12 以上手机和一台 API 27 环境完成 2 小时记录；收集定位点完整率、数据库增长、CPU、内存、耗电、温升、ANR 和崩溃。真实设备是否使用 Redmi 需由用户单独授权，不能沿用 Pixel_9 授权范围。

### M7 Baseline Profile 热路径刷新

落地状态：

- `generateStartup` 只采集授权后的稳定首屏并设置 `includeInStartupProfile = true`；`generateCriticalUserJourneys` 覆盖开始、Home、返回重绑 Service、暂停、继续和结束确认。
- 上一版热路径在 Pixel_9 / API 35 生成 Baseline Profile 50,591 行、Startup Profile 49,417 行；旧 `DashboardViewModel` 与 Factory 命中为 0，当时的 Runtime、事件队列、Service、定位策略和 Room 路径均已命中。
- 当前 Release APK 仍内含 `assets/dexopt/baseline.prof` 与 `baseline.profm`，但内容来自本轮背压改动前的生成结果，不能视为最终候选 Profile。
- 两轮 5 次冷启动对照中，Baseline Profile 中位数分别从 306.48ms 降至 253.11ms、从 312.96ms 降至 256.06ms，改善约 17.4% 和 18.2%。
- 当前无预编译中位仍慢于 v0.2.0 历史 241.9ms，不能把 Profile 收益解释为架构扩展没有启动成本；模拟器数据也不能替代真机性能结论。

当前结论：上述结果是本轮背压改动前的历史基线。本轮已经修改 `DashboardRuntime`、`TripRecordingService`、`LocationEnginePolicy`、`TripSessionCoordinator` 和 `QueuedTripStorage`，发布候选必须重新采集 Profile，确认新背压路径命中、旧类名仍归零、APK 二进制 Profile 更新，并在同一 Pixel_9 AVD 重跑冷启动对照。

### M8 AGP 9

AGP 9 属于构建链迁移，不是当前最紧迫的运行时风险。应在 M5 至 M7 的行为和测试稳定后使用独立分支升级，避免把 Kotlin 内置支持、插件兼容和 R8 差异与长测、异常恢复回归混在同一提交。

验收门槛：API 27 安装启动、JVM 与 instrumentation、Release R8、资源压缩、Baseline Profile 生成和 GitHub 发布链全部通过，APK 包名与签名身份不变。

## 5. 发布门禁

下一发布候选必须先通过第 3 节剩余异常、性能与身份关卡，并完成取得单独设备授权后的真实设备 2 小时长测。即使通过，发布说明仍必须保留以下边界：API 27/29/35 的 30 分钟结果不代表物理低存储或真机；Room 只读连接与 Runtime/Room 背压专项只证明连接级永久写失败、16 点有界保留和检查点恢复，不证明设备 `ENOSPC` 零丢点；16 点上限不等于设备低存储损失率；API 27 整机重启只证明活动数据保留、用户打开后恢复，不代表开机自动继续；模拟器性能不代表真机；事件队列只保证单进程内顺序；恢复上限是最后确认检查点而非零丢点；`force-stop`、断电、物理低存储和未确认尾批不属于当前已验证恢复结论；当前已验证 API 27/API 29 的公开正式旧包迁移和 API 27/29/31/33/35 完整权限矩阵，但最终提升版本号后的候选、Profile 和物理低存储门禁仍未完成。AGP 9 继续延后，不与异常恢复和最终候选门禁合并。

每完成一个迁移项，应同步更新本文件状态、技术设计、测试基线和发布说明，并把对应自动化与设备证据放到 `artifacts/`，不能只修改计划文字。
