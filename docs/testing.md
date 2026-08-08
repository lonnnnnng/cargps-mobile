# 手机版测试与性能基线

作者：long

更新日期：2026-08-08 21:00:35（北京时间，UTC+8）

## 测试分层

- `gps-core/src/test`：质量门、速度、NMEA、增量行程统计、行程协调器、单一事件队列、运行时恢复和后台存储队列。
- `gps-core/src/androidTest`：旧 SQLite adapter 契约、Room v1 到 v4 显式迁移、v4 事务契约、损坏数据保留和迁移失败回滚。
- `mobile-app/src/androidTest`：Pixel_9 首屏核心遥测与无滚动、权限阻断 UI 与设置 Intent、前台服务安全约束，以及真实 Service 的 Start 存储确认、Activity 可见性重绑、活动行程 Activity 重建后重绑原 Service、可恢复写失败通知/定位恢复、任务移除 Checkpoint 跨 Service 销毁继续完成、End 前后定位顺序、actor 单次恢复和第二次异常终态 seam。
- `mobile-app/src/test`：启动型 Service 的恢复策略、行程启动权限策略，以及 `LocationEngineSessionController` 的生命周期 seam；权限测试覆盖精确/近似定位、首次/永久拒绝、系统定位、通知权限、API 27 和设置返回收敛。
- `baselineprofile`：分别生成 Release Baseline/Startup Profile，并执行无预编译与强制 Baseline Profile 的冷启动 Macrobenchmark。
- `scripts/verify-m3-*` 与 `scripts/verify-m6-*`：在 instrumentation 无法跨进程存活的场景中，由外部 zsh 脚本锁定允许的模拟器 serial，使用 probe-only 组件执行真实 Room/Service 进程回收验证；普通 Debug/Release 不包含探针入口。

项目没有引入 DI 框架。`CarGpsApplication` 负责创建进程内唯一 `DashboardRuntime` 与存储队列，Activity 和 Service 通过同一实例协作；领域测试直接使用 fake `TripStorage`，现阶段不引入 Hilt。

生产存储为 `RoomTripStorage`；`SqliteTripStorage` 只用于旧 schema fixture 和 adapter 兼容测试。Room v4 schema 导出到 `gps-core/schemas/com.cargps.storage.RoomTripDatabase/4.json`，所有旧版本迁移均显式注册，禁止 destructive fallback。

`TripSessionCoordinatorTest` 通过 fake storage 注入开始、暂停、恢复、结束、定位点同步写入失败、存储背压和异步写入错误，验证元数据命令失败时保持上一个确认模式、点写失败不终止会话、背压恢复后清除临时错误、重复命令不重复写、多段暂停只扣除真实暂停时长。`QueuedTripStorageTest` 另外验证瞬时批量失败重试成功不误报、连续失败由屏障抛出、晚到订阅者仍可取得最近确认检查点、未确认点最多 16 个且第 17 个被拒绝，以及存储恢复后配额释放并可继续写入。`TripStorageFailureIntegrationTest` 组合真实 `TripSessionCoordinator + QueuedTripStorage`，断言永久批次失败期间保持最后确认检查点，恢复后再确认原尾批；这些是 JVM 领域/队列故障注入，不等价于物理 `ENOSPC`。`SqliteTripStorageInstrumentedTest.roomStorageKeepsActiveTripWhenSqliteConnectionIsReadOnly` 在 Room 实际打开的 SQLite 连接上注入只读状态，验证存储层永久写失败的事务原子性；`RoomRuntimeBackpressureInstrumentedTest` 再把同一故障推进到 `QueuedTripStorage -> DashboardRuntime`，验证 16 点上限、第 17 点拒绝和恢复后检查点确认。

M6 当前开发线的 `TripSessionEventQueueTest` 7/7，覆盖 Restore 固定优先与完整 FIFO、等待型 Start、关闭取消、actor 异常终止、消费时连续 Toggle、恢复阻塞期间取消等待后跳过尚未消费的 Start，以及取消生命周期等待后仍执行已排队 Checkpoint。协调器测试补充 End 前尾点纳入、End 后点拒绝、点写失败统一失败流、16 点背压和确认恢复；`DashboardRuntimePersistenceTest` 11/11 进一步验证 actor 异常立即阻断定位输入、从确认边界单次自动重建、初始 `START_STICKY` 等待重建结果、第二次异常进入终态，以及恢复首点不跨故障窗口补算距离、速度和确认序列。手机版 `TripStartOrchestratorTest` 5/5，覆盖纯编排回调在确认前不调用 `startLocation`、最终状态先于请求标记清理、Start 失败、权限等待期间失效和异常清理；`LocationEnginePolicyTest` 7/7 覆盖普通存储失败与背压时停止定位，`LocationEngineSessionControllerTest` 4/4 覆盖启停幂等和启动失败重试，`StartedServiceRecoveryPolicyTest` 6/6 覆盖 actor 恢复中继续等待、终态失败停止恢复。控制器和策略 JVM seam 已由两档设备上的真实 Service 8/8 instrumentation 补充，新增真实 Activity 重建重绑和 actor 第二次终态失败，但仍不等价于物理低存储或真实系统 GPS 故障。

## 本地关卡

```zsh
./gradlew :gps-core:testDebugUnitTest \
  :gps-core:assembleDebugAndroidTest \
  :mobile-app:testDebugUnitTest \
  :mobile-app:assembleDebugAndroidTest \
  :mobile-app:lintDebug \
  :mobile-app:lintVitalRelease \
  :mobile-app:assembleDebug \
  :mobile-app:assembleProbe \
  :mobile-app:assembleRelease \
  :baselineprofile:assembleBenchmarkRelease \
  --console=plain
```

## Pixel_9 关卡

必须先确认 serial 对应的 AVD 名称，不能让 Gradle 自动选择已连接的 Redmi 真机。serial 会随模拟器启动顺序变化，下面的占位符必须替换为当次确认值：

```zsh
PIXEL9_SERIAL="<已确认的 Pixel_9 serial>"
adb -s "$PIXEL9_SERIAL" shell getprop ro.boot.qemu.avd_name
ANDROID_SERIAL="$PIXEL9_SERIAL" ./gradlew \
  :gps-core:connectedDebugAndroidTest \
  :mobile-app:connectedDebugAndroidTest \
  --console=plain
```

运行连接级 Runtime/Room 背压专项（API 27 也必须串行执行，不能与 Pixel_9 并行共享 UTP 结果目录）：

```zsh
ANDROID_SERIAL="$PIXEL9_SERIAL" ./gradlew :gps-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cargps.RoomRuntimeBackpressureInstrumentedTest \
  --console=plain

API27_SERIAL="<已确认的 API 27 serial>"
ANDROID_SERIAL="$API27_SERIAL" ./gradlew :gps-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cargps.RoomRuntimeBackpressureInstrumentedTest \
  --console=plain
```

生成 Baseline Profile：

```zsh
ANDROID_SERIAL="$PIXEL9_SERIAL" ./gradlew :mobile-app:generateReleaseBaselineProfile --console=plain
```

运行冷启动基准：

```zsh
ANDROID_SERIAL="$PIXEL9_SERIAL" ./gradlew \
  :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cargps.mobile.baselineprofile.StartupBenchmark \
  --console=plain
```

## 2026-08-07 基线

- 设备：`Pixel_9` AVD，Android 15 / API 35，1080x2424。
- SQLite instrumentation：4/4 通过。
- Compose instrumentation：1/1 通过，滚动节点 0。
- Baseline Profile：生成 11,805 条规则，保存于 `mobile-app/src/release/generated/baselineProfiles/baseline-prof.txt`。
- 无预编译冷启动 TTID，5 次：最小 229.9ms，中位 241.9ms，最大 333.0ms。
- Debug APK：约 18MB；R8、资源压缩和签名后的 `v0.2.0` Release APK 为 972,452 字节，约 950KB。

Macrobenchmark 已显式允许 `EMULATOR`，这些数值只用于同一 Pixel_9 AVD 的版本间相对比较，不能解释为真机绝对性能。

## M1 行程协调器回归

- 本地 JVM：`gps-core` 32/32 通过；`mobile-app` lint 和 debug 构建通过。
- Pixel_9 API 35：SQLite instrumentation 4/4、Compose instrumentation 1/1 通过。
- 冷启动前台 Activity 为 `com.cargps.mobile/.MainActivity`，UI 树滚动节点为 0，crash buffer 为空。
- 截图：`artifacts/cargps-mobile-m1-pixel9.png`；UI 树：`artifacts/cargps-mobile-m1-pixel9.xml`。
- 为安装 debug 签名构建，已从 Pixel_9 模拟器卸载旧正式包并清除该模拟器内的应用测试数据；未操作 Redmi。

## M2 定位前台服务回归

- 本地 JVM：`gps-core` 32/32 通过；`mobile-app` lint 和 debug 构建通过。
- Pixel_9 API 35：SQLite instrumentation 4/4；`mobile-app` instrumentation 3/3，其中 1 项为 Compose 单屏、2 项为前台服务和 Intent 安全约束。
- 运行时确认 `TripRecordingService` 为前台服务，类型掩码 `0x00000008`（`location`）；常驻通知显示记录状态并包含“结束行程”。
- Home、锁屏、Activity 重建和重复打开的短路径中服务保持运行，应用进程内 `cargps-location` 定位线程始终为 1；覆盖安装触发进程重建后，已落库活动行程恢复并重新进入定位前台状态。
- 从通知结束行程后，界面回到空闲状态，前台标志与活动通知清除，crash buffer 为空。
- 2026-08-07 的旧监测在第 5 个一分钟样本后被外部 `force-stop` 中断。`ApplicationExitInfo` 为 `USER REQUESTED / FORCE STOP`，不是崩溃、ANR 或系统回收；该次结果保持无效，不计入后续成功长测。
- 2026-08-08 Pixel_9 / API 35 重新完成 41 个样本、1831 秒回归：样本 `0..13` 为 Home，`14..26` 为系统设置前台，`27..33` 为锁屏 `Dozing/Asleep`，`34..40` 为解锁后 Home。PID 始终为 `4395`，前台服务和通知持续，`cargps-location` 线程始终为 1，crash buffer 始终为 0。
- 长测后回到应用仍显示“记录中”；正常结束后历史从 0 段增加为 1 段。Home 后 Service、活动通知和定位线程均为 0，GPS provider 为 `OFF`、`mStarted=false`，事件历史包含应用 `-registration`。摘要见 `artifacts/cargps-mobile-api35-30min-summary.md`。
- 2026-08-08 Android 10 / API 29 进一步完成 41 个样本、1816 秒同级回归：PID 始终为 `3365`，前台服务和活动通知持续，`cargps-location` 线程与活动 GPS 注册始终为 1，锁屏 `Asleep` 后继续记录，crash buffer 始终为 0。
- API 29 长测后回到应用仍显示“记录中”；正常结束后历史从 0 段增加为 1 段，Home 后 Service、活动通知、定位线程和活动 GPS 注册均为 0，`mStarted=false`。摘要见 `artifacts/cargps-mobile-api29-30min-summary.md`。
- 对应截图和 UI 树见 `artifacts/cargps-mobile-m2-*`；这些本地验证文件默认不提交 Git。

## M3 进程异常退出恢复回归

- 本地 JVM：`gps-core` 36/36、手机版恢复策略 4/4 通过；覆盖最后确认检查点恢复、批次确认 Flow、生命周期主动冲刷和初始存储恢复门禁。
- Pixel_9 API 35：SQLite instrumentation 5/5、`mobile-app` instrumentation 3/3；SQLite 重开后检查点准确返回确认点数、最后 sequence 和最后点时间。
- 破坏性场景：活动行程在 Home 后保持 `location` 前台服务，原 PID `9235` 只有一条 `cargps-location` 线程；通过应用自身 UID 发送 `SIGKILL`。
- 系统 `ApplicationExitInfo` 记录 `reason=SIGNALED`、`status=9`，随后自动创建新 PID `9355`，`restartCount=1`；未手工启动 Service。
- 新进程恢复为 `START_STICKY`、`isForeground=true`、类型 `0x00000008`，通知仍为“CarGPS · 正在记录”，定位线程仍为 1；Activity 回前台显示“记录中 / 已恢复”。
- 从 UI 结束行程后通知与 Service 清除，界面回到空闲，crash buffer 为空。证据为 `artifacts/cargps-mobile-m3-after-sigkill.png` 和 `.xml`。
- 该结果证明普通进程信号终止后的确认边界恢复；后续 Service 生命周期 seam 已证明 `onTaskRemoved()` 同步入队的 Checkpoint 在 Service 销毁和等待协程取消后仍可继续完成。新增的 probe-only Room 阻塞探针又在 API 35/API 27 验证了 Checkpoint 提交前整个进程被 `SIGKILL` 时的新 PID 恢复，并把未确认损失窗口精确量化为 16 点（确认点数保持 0）。这不证明 `force-stop`、断电或物理低存储零丢失；整机重启另按下一节验证。

## M3 Checkpoint 提交前进程回收回归

- 探针只存在于 `mobile-app/src/probe`，使用真实 `RoomTripStorage + QueuedTripStorage + DashboardRuntime`，在 16 点批次委托 Room 事务前阻塞；普通 Debug 与 Release Manifest 都不应包含该组件或 probe-only `dataSync` 权限。
- Pixel_9 / API 35 / `emulator-5554`：旧 PID `2786`、新 PID `2842`，`ApplicationExitInfo = SIGNALED / status=9`，`restartCount=1`，前台类型 `0x00000008`；Room 保持 `RECORDING`、确认点数 0、未确认损失 16 点。
- Android 8.1 / API 27 / `emulator-5556`：旧 PID `22226`、新 PID `22285`，`restartCount=1`；Room 保持 `RECORDING`、确认点数 0、未确认损失 16 点。API 27 不支持 `dumpsys activity exit-info`，因此以 PID、Service、通知和 Room 查询构成证据链。
- 成功命令为 `KEEP_RECOVERED_STATE=1 ./scripts/verify-m3-checkpoint-process-kill.zsh <serial>`；脚本显式拒绝两个目标模拟器以外的 serial。两端证据采集后已停止 `com.cargps.mobile`，未清除恢复数据库。
- 结论：系统可恢复活动行程元数据与 `START_STICKY` Service，但 Checkpoint 真正提交前的 16 个未确认点可以全部丢失。详细实现与边界见 [M3 Checkpoint 提交前进程回收验证](./m3-checkpoint-process-kill-validation.md)。

## M3 整机重启边界回归

- API 27 的历史整机重启记录使用 `emulator-5556` 作为当次 serial，以活动行程 `RECORDING` 为前置条件执行普通 `adb -s <当次 API27 serial> reboot`，不清数据、不使用 `force-stop`；serial 不能作为 AVD 身份。
- 重启前数据库 `active_trip` 存在，观察到 `active_point` 为 118 个（sequence `2004..2121`），`TripRecordingService` 为 `location` 前台服务，GPS 注册为 1 条。
- `sys.boot_completed=1` 后、未打开应用前，`com.cargps.mobile` 进程、Service、手机版通知和 GPS 注册均不存在；`active_trip` 仍为 `RECORDING`。重启过程中的最终异步冲刷使点数现场观察为 171 个（`2004..2174`），不据此计算尾批损失率。
- 手动打开 `MainActivity` 后 UI 显示“记录中 / 已恢复”，Service 和 GPS 注册恢复；确认结束后活动行程、通知和定位注册清理。
- 结论：当前产品边界是“整机重启后数据保留，用户打开应用后恢复”，不承诺开机自动继续记录。详细摘要见 `artifacts/cargps-mobile-api27-reboot-summary.md`。
- 若未来要做开机自动恢复，必须新增独立验证：`BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`、Android 14 while-in-use location、`ACCESS_BACKGROUND_LOCATION`、Android 15 启动限制、受限应用和厂商电源管理；不能把普通 `START_STICKY` 结果外推为开机恢复。

## M4 Room 与数据损坏回归

- 2026-08-07 本地完整关卡通过：`gps-core` JVM 37/37、`mobile-app` JVM 4/4；AndroidTest 编译、debug/release 构建、`lintDebug`、`lintVitalRelease`、R8 和 `baselineprofile:assembleBenchmarkRelease` 均成功。
- Pixel_9 / API 35：`gps-core` instrumentation 12/12、`mobile-app` instrumentation 3/3 通过；该次设备由 `emulator-5554` 的 `ro.boot.qemu.avd_name = Pixel_9` 明确确认，未操作 Redmi。后续命令必须重新核对 serial。
- Room 迁移覆盖 v1 到 v4 活动行程与轨迹、v2 到 v4 已结束行程与历史轨迹、v3 到 v4 暂停行程，并验证 v4 完整 `TripStorage` 事务契约和确认检查点。
- 非法活动行程 mode 返回 `ActiveTripLoadResult.Corrupt`，数据库原始行保持不变；协调器测试验证损坏时 `storageReady = false` 且拒绝开始新行程。
- 缺失 `total_paused` 的畸形 v3 数据库在迁移失败后仍保持版本 3、旧表结构和原始行，证明当前迁移事务不会用半成品覆盖来源库。
- API 27/API 29 已完成 Debug 同签名与公开正式 `v0.2.0` 同证书两组覆盖升级：来源库均为 SQLite v3，覆盖后均为 Room v4，活动状态、开始时间、点数、距离、sequence 和点时间范围保持不变。
- Debug 组：API 27 为 39 点/33.50 米，API 29 为 29 点/33.50 米；正式组：API 27 为 29 点/37.62 米，API 29 为 30 点/28.74 米。升级后界面均显示“记录中 / 已恢复”，前台服务和单条 `cargps-location` 线程正常。
- 当前开发构建仍与公开旧包同为 `0.2.0 (3)`；该结果验证代码、证书和数据库迁移链，不替代最终候选提升版本号后的再次覆盖安装，也不表示任意文件级物理损坏都能自动恢复。
- 2026-08-08 存储故障层验证：`roomStorageKeepsActiveTripWhenSqliteConnectionIsReadOnly` 在 Room 实际打开的 `SupportSQLiteDatabase` 上执行 `PRAGMA query_only = ON`，批量写入抛出真实 `SQLiteException`；随后重开 Room，已确认轨迹点和 `ActiveTripCheckpoint` 仍保留，失败批次没有部分写入。Pixel_9 / API 35 与 Android 8.1 / API 27 的存储类均为 13/13 通过，摘要见 `artifacts/cargps-mobile-storage-readonly-summary.md`。
- 2026-08-08 Runtime/Room 背压链路验证：`RoomRuntimeBackpressureInstrumentedTest` 在上述实际连接故障上验证前 16 点保留、第 17 点同步背压拒绝、活动行程与最后确认边界保留；切换连接可写后 `checkpointTripWritesAndAwait()` 确认 16 点并清除背压。Pixel_9/API 35 与 Android 8.1/API 27 专项均为 1/1，完整 `gps-core` instrumentation 均为 14/14；摘要见 `artifacts/cargps-mobile-runtime-backpressure-summary.md`。
- 这些连接级用例不证明物理磁盘 `ENOSPC`；另一个真实 Service seam 已覆盖注入式可恢复写失败下的“等待存储恢复”通知、定位边界停止和检查点恢复重启。M3 probe-only Room 阻塞探针已单独量化提交前进程回收的 16 点损失窗口，但仍未覆盖真实低存储、系统 GPS 注册和恢复耗时。计数应区分存储类 13/13、Runtime/Room 专项 1/1、Service seam 8/8 和当前完整 `gps-core` 套件 14/14，不能把不同测试层混写。

## M5 权限状态机回归

- 2026-08-07 M5 提交前完整本地关卡通过：JVM、AndroidTest 编译、`lintDebug`、`lintVitalRelease`、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease` 均成功。
- `TripAccessPolicyTest` 11/11，覆盖 Ready、仅近似定位、精度升级首次/重试/转设置、定位首次/永久拒绝、系统定位关闭、Android 13+ 通知首次/永久拒绝、API 27 无通知运行时权限和设置返回策略收敛。
- Pixel_9 / API 35：`gps-core` instrumentation 12/12、手机版 6/6；设置 Intent、初始未授权单屏无滚动和活动行程阻断后保留结束入口均通过。
- 真实系统 UI 已验证精确、仅近似、近似升级拒绝、定位首次/永久拒绝、通知首次/永久拒绝、通知设置返回和系统定位关闭/恢复；所有失败路径均未自动开始行程或循环弹窗。
- 设备证据为 `artifacts/cargps-mobile-m5-*` 的 PNG/XML；当次命令显式锁定 `emulator-5554` 并复核 `ro.boot.qemu.avd_name = Pixel_9`，未操作 Redmi。
- API 27/29/31/33 已完成适用的精确/近似、首次/永久拒绝、应用设置返回和系统定位关闭/恢复；API 33 还保留通知首次/永久拒绝证据。结合 Pixel_9 / API 35，跨版本权限矩阵已经闭环。
- API 27 的通用 Compose 测试宿主原本按 1024x600 横屏运行，未继承 `MainActivity` 的竖屏声明，导致两个底部权限入口可见性断言失败。测试现使用 UiAutomator 固定竖屏并在结束后解冻；从横屏 `SurfaceOrientation: 0` 启动后手机版 6/6 通过，API 29/33 也各复验 6/6，断言没有降级。

## 2026-08-08 P0/P1 seam 与存储背压加固

- 本地 JVM 完整测试：`gps-core` 58/58、`mobile-app` 33/33 通过，失败与错误均为 0；其中 Runtime 持久化 11/11、事件队列 7/7、控制器 seam 4/4、定位策略 7/7、启动恢复策略 6/6。
- 本地完整构建关卡：AndroidTest 编译、`lintDebug`、`lintVitalRelease`、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease` 全部成功；本轮 Activity 重建与 actor 终态测试加入后复验为 `BUILD SUCCESSFUL in 12s`，271 项任务中 7 项执行、264 项保持最新。
- Pixel_9 复验：确认 `emulator-5554` 的 `ro.boot.qemu.avd_name = Pixel_9`、SDK 35 后显式设置 `ANDROID_SERIAL=emulator-5554`；完整 `gps-core` instrumentation 14/14、`mobile-app` 14/14 通过，Service 生命周期类 8/8，专项背压用例此前已通过 1/1。
- Android 8.1 / API 27 复验：确认 `emulator-5556` 为 API 27、Android 8.1、1024x600，Gradle 识别为 `CASKA_1024x600(AVD) - 8.1.0`；显式锁定该 serial 后完整 `gps-core` instrumentation 14/14、`mobile-app` 14/14 通过，Service 生命周期类 8/8。此前 `RoomRuntimeBackpressureInstrumentedTest` 曾在等待 `storageBackpressure` 时发生一次 15 秒超时，专项立即复跑 1/1、随后完整套件再次 14/14 通过；该次记录保留为 API 27 调度抖动证据，不计作应用断言回归。设备关卡已覆盖注入式 Service 故障编排，但不替代物理低存储和真实系统 GPS 注册验证。
- 有界尾批：永久批次写失败时前 16 个点保留在内存，第 17 个点同步抛出 `TripStorageBackpressureException`，不会继续增加内存占用或实时行程统计；存储恢复后原尾批确认、配额释放并可继续接收新点。
- 背压状态：协调器进入 `TripPersistenceState.FAILED` 并保留 `RECORDING` 和最后确认检查点；Service 策略停止定位，保留前台状态和结束入口，新的确认检查点到达后恢复 `CONFIRMED`、清除旧错误和背压标志。
- 确认边界重绑：`QueuedTripStorage.confirmedCheckpoints` 保留最近一条成功确认值，晚到的 Service/Activity 订阅者仍可取得最后 `sequence` 和时间。
- 任务移除边界：`TripRecordingService.onTaskRemoved()` 使用 `CoroutineStart.UNDISPATCHED`，确保回调返回前同步把 Checkpoint 交给应用级 Runtime；该命令采用 `KEEP_QUEUED` 取消策略，因此 Service 销毁、等待协程取消后仍继续冲刷。整个进程若在检查点完成前被系统回收，仍只能按最近确认边界恢复。
- Start 竞态：最终状态处理移到 `startRequested` 清理之前，避免状态收集器看到旧 `IDLE` 快照而误回收正在确认的 Service。
- 未完成证据：已在 Android 设备的真实 Room/SQLite 连接上验证永久只读写失败，并由真实 Service seam 断言注入故障后通知立即切换、定位边界停止、结束入口保留和检查点恢复后只重启一次；但尚未制造物理磁盘 `ENOSPC`，也未覆盖真实系统 GPS 注册与磁盘恢复耗时，当前不能宣称真实低存储零丢点。
- 恢复首点分段证据：`DashboardRuntimePersistenceTest.存储失败后恢复首点不跨故障窗口补算距离速度和确认序列` 验证背压拒绝点不进入累计器；新增 `一般存储失败期间不再接收定位点且恢复首点重新断开`，验证普通错误未被新检查点确认前不再投递定位点，恢复首点以零距离重新开始。真实 Service seam 已覆盖一次可恢复写失败与检查点恢复编排，物理 Room/SQLite 空间耗尽、真实 GPS 注册和更复杂回调竞态仍未覆盖。
- Runtime/Room 连接级专项已通过，但它仍在测试连接上切换 `PRAGMA query_only`；Service seam 使用可恢复 fake storage 和定位计数边界验证通知与启停编排；M3 probe-only 探针则使用真实 Room 在提交前杀进程并量化了 16 点尾批损失。三者仍未覆盖物理磁盘空间耗尽、系统存储广播和真实 GPS 注册生命周期，因此不能关闭 M2/M6 的设备级发布阻断。

## M6 单一事件队列回归

- 当前开发线已加入唯一 `Channel.UNLIMITED` 事件队列，固定先 Restore，再串行处理 Start、AppendPoint、Pause、Resume、End、Tick 和 Checkpoint；关闭或 actor 异常后不会留下悬挂等待者或继续假接收事件。actor 未预期终止时 Runtime 立即发布可见错误、停止定位输入，并在同一 Runtime 生命周期内最多重建一次队列；新队列先 Restore 已确认存储状态。初始 `START_STICKY` 在自动重建期间继续等待，第二次 actor 异常进入终态而不循环重建。Service 的定位启停由 `LocationEngineSessionController` 统一 reconcile，缓存只记录已成功执行的动作；底层 `LocationEngine.start()` 返回失败或抛出异常时不缓存启动状态，后续重绑/恢复事件仍可重试。
- `DashboardRuntime.startTripAndAwait()` 在存储确认后同步发布并返回 `DashboardState`；`LocationEnginePolicy` 只允许已确认活动行程在客户端不可见时启动后台定位，`START_PENDING` 不再等价于活动行程。客户端可见时仍允许定位预览，Runtime 在 `IDLE` 状态不会写入行程点。
- 本地完整关卡此前为 `gps-core` 44/44、手机版 24/24 JVM；当前背压、普通存储失败输入阻断、恢复首点分段、队列取消语义和 actor 单次恢复加固后为 `gps-core` 58/58、手机版 33/33 JVM，AndroidTest 编译、`lintDebug`、`lintVitalRelease`、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease` 均成功。
- Pixel_9 / API 35 与 Android 8.1 / API 27 instrumentation：本轮两端均重新执行当前完整 `gps-core` 14/14、手机版 14/14；此前 Runtime/Room 背压专项各 1/1。设备通过 serial 与 AVD/API/分辨率明确确认，所有 Gradle 设备任务通过 `ANDROID_SERIAL` 锁定，未操作 Redmi。
- Pixel_9 运行时短路径：开始后 UI 为“记录中”，Service 为 `location` 前台类型；以应用 UID 连续两次 ensure 后 `cargps-location` 线程仍为 1；结束后回到“等待开始”且历史增加一段，Home 后 Service 清除，crash buffer 无 CarGPS 记录。
- API 27/API 29 的聚焦 `START_STICKY` 恢复已通过；Pixel_9 / API 35、Android 10 / API 29 与 Android 8.1 / API 27 的完整 30 分钟 Home/切换应用/锁屏已通过；API 27 整机重启边界也已验证为“不开机自动拉起，打开应用后恢复”。Checkpoint 提交前进程回收已由 probe-only 探针量化为 16 点未确认损失窗口；物理低存储、Service 全路径异常竞态和真实设备 2 小时长测仍未完成。
- API 31 已完成可见开始、`location` 前台服务、Home、锁屏、单定位线程、活动行程撤权阻断和结束后 Home 清理短路径；它仍不计作 30 分钟长测。

## 2026-08-08 Service 真实生命周期 seam

- `TripRecordingServiceLifecycleInstrumentedTest` 通过仅测试使用的依赖工厂把真实 Service 接到真实 `DashboardRuntime`（仍包含 `TripSessionEventQueue` 和真实存储确认流程）以及可计数的定位启停边界；生产构建不注入 fake。当前八个场景覆盖 Start 门禁、可见性重绑、活动行程 Activity 重建后重绑原 Service、可恢复写失败、任务移除 Checkpoint、End 前后尾点顺序、actor 单次恢复和第二次异常终态。
- Pixel_9 / API 35：8/8 通过；除原六个场景外，新增验证真实 `ActivityScenario.recreate()` 只替换 Activity 实例，Service 仍只创建一次且定位启停计数不增加，以及 actor 第二次异常后通知保持“行程处理异常”、不再 Restore 或重启定位、后续定位不进入已关闭队列。
- Android 8.1 / API 27：8/8 通过；同样覆盖上述八个场景。测试授权命令使用 API 27 兼容的 `UiAutomation.executeShellCommand("pm grant ...")`，不调用 API 29 才加入的 shell identity API。
- 该 seam 已覆盖真实 Service 的 Start 等待、前台服务升起、空闲可见性重绑、活动行程 Activity 重建重绑、注入式可恢复写失败通知、定位启停幂等、已排队 Checkpoint 跨 Service 协程取消继续完成、End 前后队列顺序、actor 单次恢复和第二次终态失败；存储与定位系统边界仍由测试依赖隔离。Checkpoint 完成前进程回收另有 probe-only 真实 Room 探针覆盖并量化 16 点损失窗口，Activity 与 Service 同进程回收后的两阶段重绑由下一节外部脚本覆盖。上述层次仍不等价于物理低存储或真实系统 GPS 注册故障，这些设备边界继续阻断发布。详细摘要见 [M6 Activity 重建与 actor 终态回归](./m6-lifecycle-validation.md)、[M3 Checkpoint 提交前进程回收验证](./m3-checkpoint-process-kill-validation.md) 和 [M6 Activity 与 Service 同进程回收重绑验证](./m6-process-recreation-rebind-validation.md)。

## M6 Activity 与 Service 同进程回收重绑

- `verify-m6-process-recreation-rebind.zsh` 只接受 `emulator-5554` 或 `emulator-5556`，并在 ADB 前复核 AVD 名称/API；非法 Redmi serial 返回码为 2。
- 场景在 `MainActivity` 前台、活动行程已确认、真实 location Service 和 Room 运行时发送应用 UID `SIGKILL`。系统不会自动把被杀 Activity 弹回前台；脚本先等待 `START_STICKY` Service 在无 Activity 时独立恢复，再模拟用户重新打开应用。
- Pixel_9/API 35：PID `8481 -> 8587`，`ApplicationExitInfo = SIGNALED / status=9`；API 27：PID `24946 -> 25055`。两端 `restartCount=1`、进程数 1、ServiceRecord 1、定位线程 `1 -> 1`，用户返回后 UI 显示“记录中 / 已恢复”并正常结束。
- 首版脚本错误要求 Activity 自动恢复；第二次 Pixel_9 又因只接受 `reason=SIGNALED`、未兼容 `reason=2 (SIGNALED)` 而拒绝已出现的证据。最终脚本按旧 PID 提取单条 `ApplicationExitInfo` 记录，双设备从清数据完整重跑后通过。
- 该路径补齐 Activity 与 Service 同进程回收后的主要重绑竞态，但不等价于物理 `ENOSPC`、真实 GPS 注册失败、厂商任务栈差异或真机长测。详细摘要见 [M6 Activity 与 Service 同进程回收重绑验证](./m6-process-recreation-rebind-validation.md)。

## M7 Baseline Profile 与冷启动对照

- `BaselineProfileGenerator` 拆成两个独立采集场景：`generateStartup` 只采集授权后稳定首屏并写入 Startup Profile；`generateCriticalUserJourneys` 覆盖开始行程、Home、返回重绑 Service、暂停、继续和结束确认，不把完整行程规则全部标成启动布局。
- `Pixel_9 / emulator-5554 / API 35` 的 `generateReleaseBaselineProfile` 成功：Baseline Profile 50,591 行，Startup Profile 49,417 行；`DashboardViewModel`、`DashboardViewModelFactory` 命中均为 0。
- 上一版 Profile 命中 `DashboardRuntime`、`TripSessionEventQueue`、`TripRecordingService`、`LocationEnginePolicy`、`TripStartOrchestrator` 和 `RoomTripStorage`；当前 Release APK 仍内含 `assets/dexopt/baseline.prof`（4,224 字节）与 `baseline.profm`（199 字节），但背压改动触及这些热路径，不能把这组文件当作最终候选证据。
- 冷启动第一轮：无预编译最小/中位/最大为 278.02/306.48/333.31ms；强制 Baseline Profile 为 244.77/253.11/445.35ms，中位改善约 17.4%，但存在一次明显离群点。
- 冷启动第二轮：无预编译最小/中位/最大为 270.18/312.96/345.67ms；强制 Baseline Profile 为 228.74/256.06/338.91ms，中位改善约 18.2%。原始 JSON 保存在本地 `artifacts/cargps-mobile-m7-startup-benchmark-run1.json` 和 `run2.json`。
- `v0.2.0` 历史无预编译中位为 241.9ms；上一版 M1-M6 无预编译中位约 306-313ms，说明运行时架构扩展带来启动成本。旧 Profile 能显著回收当时构建的启动开销，但本轮背压改动后必须重新采集，不能据此宣称当前候选整体启动无回退。
- 上述数据来自未锁 CPU 的模拟器，只能作为同一 Pixel_9 AVD 的相对证据，不能外推真机绝对性能。

## 2026-08-08 跨 API 27/29/31/33 聚焦回归

- API 27：`CASKA_1024x600` / Android 8.1，`gps-core` 12/12、手机版 6/6 instrumentation 通过。首次拒绝保留重试入口，第二次弹窗勾选“不再询问”后转应用设置；应用设置授权、系统定位关闭/恢复和设置返回均收敛且不误启动。测试还修复了通用 Compose 宿主未继承竖屏声明的问题。更早一轮曾因测试直接调用 API 29 才加入的 `ServiceInfo.getForegroundServiceType()` 触发 `NoSuchMethodError`；测试现仅在 API 29+ 查询类型，API 27/28 继续验证私有 Service 和权限声明。
- API 27 运行时：未授权阻断、精确定位后开始、Home、前台通知、单条 `cargps-location` 线程通过；活动行程从 PID `3424` 经 `SIGKILL` 恢复为 `3618`，`restartCount = 1`，Activity 显示“记录中 / 已恢复”；结束后本地历史为 1 段，Home 后 Service 和定位线程清除。
- API 29：`CarGPS_Pixel_9_API29` / Android 10，`gps-core` 12/12、手机版 6/6 instrumentation 通过。仅授予粗略定位时显示“当前仅有大致位置”，授予精确定位后可开始；首次拒绝可重试，`Deny & don’t ask again` 后精确与粗略权限均产生 `USER_FIXED`，应用设置授权和系统定位关闭/恢复均通过。可 root 的 `google_apis` AVD 发送真实 `SIGKILL`，PID `3587` 恢复为 `4159`，前台服务、单定位线程和“已恢复”状态通过；结束和 Home 后清理通过。
- API 31：安装 `platforms/android-31` 与 `system-images/android-31/google_apis/arm64-v8a`，创建 `CarGPS_Pixel_9_API31`，`gps-core` 12/12、手机版 6/6 instrumentation 通过。真实系统弹窗覆盖 Approximate、Precise 升级、首次拒绝、第二次永久拒绝；应用设置和系统定位设置返回均收敛，活动行程撤权后保留结束入口并停止 GPS provider。可见启动、Home、锁屏、前台通知、单定位线程和结束后 Home 清理短路径通过。
- API 33：`CarGPS_Pixel_9_API33` / Android 13，`gps-core` 12/12、手机版 6/6 instrumentation 通过。位置矩阵覆盖 Approximate -> Precise、首次/永久拒绝、应用设置授权和系统定位关闭/恢复；通知首次拒绝后保持可重试，第二次拒绝的系统按钮为 `deny_and_dont_ask_again`，权限标志包含 `USER_FIXED`，重新授权后可启动前台行程。
- 设备命令始终显式指定模拟器 serial；未向 Redmi 安装、授权、清数据或执行测试。完成矩阵后已恢复可见的 `Pixel_9 / API 35`。
- 本轮仍是聚焦短路径，不计作 30 分钟后台长测；API 27/29/31/33 的完整位置矩阵已经完成，权限状态机不再是当前发布阻断项。

跨 API UI 证据为本地 `artifacts/cargps-mobile-api27-location-*.xml`、`cargps-mobile-api29-location-*.xml`、`cargps-mobile-api31-*.xml` 和 `cargps-mobile-api33-location-*.xml`。命令始终显式指定模拟器 serial 并先复核 AVD 名称，未操作 Redmi。

## 2026-08-08 正式旧包跨 API 覆盖升级

- 公开 `v0.2.0` APK 与当前 Release APK 的证书 SHA-256 均为 `7807a35ea864ad038b6f3851b79333e8aedd90bb7f9521fd5ffb0d7c0375d521`。
- API 27：旧正式包创建 SQLite v3 活动行程，29 点、37.62 米；覆盖当前 Release 后数据库升级为 Room v4，`RECORDING`、开始时间、点数、距离、sequence 与点时间范围全部保持，界面显示“记录中 / 已恢复”。
- API 29：旧正式包创建 SQLite v3 活动行程，30 点、28.74 米；覆盖当前 Release 后数据库升级为 Room v4，同一组活动行程字段完整保留，界面、前台服务、通知和单定位线程恢复正常。
- Room v4 的 identity hash 为 `f87ebb25691d962beb3c76e9a6f9a505`。数据库证据为 `artifacts/cargps-mobile-api27-release-v020-before.db`、`cargps-mobile-api27-release-upgraded-after.db`、`cargps-mobile-api29-release-v020-before.db` 和 `cargps-mobile-api29-release-upgraded-after.db`。
- 两个 APK 当前都报告 `versionName = 0.2.0`、`versionCode = 3`。正式发布前必须提升版本号，用最终候选 APK 再跑一次 API 27/API 29 覆盖升级，并复核证书、版本、数据和前台恢复。
- 文档同步后的完整本地关卡再次通过，包含 JVM、AndroidTest 编译、lint、lintVital、Debug/Release、R8、资源压缩和 benchmark 构建；随后在显式确认的 `Pixel_9 / API 35 / emulator-5554` 上通过 `gps-core` 12/12 与手机版 6/6 instrumentation。
- Pixel_9 首次普通可见启动后模拟器进程退出，Gradle 报 `Connected device with serial 'emulator-5554' not found!`；使用受管可见模式重启同一 AVD 后全部通过。该失败属于模拟器可用性，不是应用断言失败。

## v0.2.0 发布验收

- 提交与 tag：`0995eb2` / `v0.2.0`；发布时工作区与 `origin/main` 同步。
- GitHub Release：[CarGPS 手机版 v0.2.0](https://github.com/lonnnnnng/cargps-mobile/releases/tag/v0.2.0)，状态为 latest、非草稿、非预发布。
- APK：`com.cargps.mobile`，`versionCode = 3`，`versionName = 0.2.0`，`minSdk = 27`，`targetSdk = 36`。
- SHA-256：`8fc1238c1fdc45db0e49d3d78243abdfe834fe15e87008e53004ae3eea366bc2`；本地 `SHA256SUMS`、GitHub asset digest 和重新下载后的文件一致。
- 签名：APK Signature Scheme v2 通过，证书 SHA-256 为 `7807a35ea864ad038b6f3851b79333e8aedd90bb7f9521fd5ffb0d7c0375d521`，与 `v0.1.1` 一致。
- 对齐与优化：Build Tools 36 的 `zipalign -c -P 16 4` 通过；APK 内含 `assets/dexopt/baseline.prof` 和 `baseline.profm`。
- 安装：正式包在 `Pixel_9` / API 35 冷启动成功；UI 树滚动节点为 0，crash buffer 为空。切换 debug 到 release 签名时仅清除了该模拟器内的测试数据。

Pixel_9 / API 35、Android 10 / API 29 与 Android 8.1 / API 27 的 30 分钟后台记录已经完成，API 27 整机重启边界也已完成验证；最新普通存储失败输入阻断、恢复首点分段、Room/SQLite 只读连接故障、Runtime/Room 背压链路、两类队列取消语义、actor 单次恢复与第二次终态失败均已通过，当前完整 JVM 为 `gps-core` 58/58、手机版 33/33，存储类为 13/13，Runtime/Room 专项在 API 35/API 27 各 1/1，完整 `gps-core` 各 14/14、手机版各 14/14。Service 生命周期 seam 在两档设备各 8/8 通过，新增覆盖活动行程 Activity 重建后重绑原 Service 不重复启停定位，以及 actor 第二次异常进入终态、不再循环重建。Checkpoint 完成前进程回收已由 probe-only 真实 Room 探针在两端完成，新 PID 恢复成功且 16 点未确认损失窗口已精确量化；前台 Activity/Service 同进程回收后的 Service 独立恢复和用户返回重绑也已在两端通过，进程、ServiceRecord 和定位线程均保持唯一。尚未完成的物理低存储、真实 GPS 注册、Profile 重采集、最终候选版本号与签名升级复验和真实设备长测见 [剩余高风险迁移项](./migration-risks.md)，不能用常规长测、注入式故障或本轮进程回收结果替代物理异常环境门槛。当前不支持的“开机自动恢复”另需产品决策和权限迁移评审。
