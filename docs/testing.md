# 手机版测试与性能基线

作者：long

更新日期：2026-08-08 11:41:04（北京时间，UTC+8）

## 测试分层

- `gps-core/src/test`：质量门、速度、NMEA、增量行程统计、行程协调器、单一事件队列、运行时恢复和后台存储队列。
- `gps-core/src/androidTest`：旧 SQLite adapter 契约、Room v1 到 v4 显式迁移、v4 事务契约、损坏数据保留和迁移失败回滚。
- `mobile-app/src/androidTest`：Pixel_9 首屏核心遥测与无滚动、权限阻断 UI 与设置 Intent，以及前台服务 Manifest、显式 Intent、不可变 `PendingIntent` 安全约束。
- `mobile-app/src/test`：启动型 Service 的恢复策略，以及行程启动权限策略；权限测试覆盖精确/近似定位、首次/永久拒绝、系统定位、通知权限、API 27 和设置返回收敛。
- `baselineprofile`：分别生成 Release Baseline/Startup Profile，并执行无预编译与强制 Baseline Profile 的冷启动 Macrobenchmark。

项目没有引入 DI 框架。`CarGpsApplication` 负责创建进程内唯一 `DashboardRuntime` 与存储队列，Activity 和 Service 通过同一实例协作；领域测试直接使用 fake `TripStorage`，现阶段不引入 Hilt。

生产存储为 `RoomTripStorage`；`SqliteTripStorage` 只用于旧 schema fixture 和 adapter 兼容测试。Room v4 schema 导出到 `gps-core/schemas/com.cargps.storage.RoomTripDatabase/4.json`，所有旧版本迁移均显式注册，禁止 destructive fallback。

`TripSessionCoordinatorTest` 通过 fake storage 注入开始、暂停、恢复、结束、定位点同步写入失败和异步写入错误，验证元数据命令失败时保持上一个确认模式、点写失败不终止会话、恢复确认后清除临时错误、重复命令不重复写、多段暂停只扣除真实暂停时长。`QueuedTripStorageTest` 另外验证瞬时批量失败重试成功不误报、连续失败由屏障抛出、晚到订阅者仍可取得最近确认检查点，以及元数据失败可被确认屏障捕获；轨迹实时统计与最后确认检查点按不同边界断言。

M6 当前开发线的 `TripSessionEventQueueTest` 5/5，覆盖 Restore 固定优先与完整 FIFO、等待型 Start、关闭取消、actor 异常终止和消费时连续 Toggle。协调器测试补充 End 前尾点纳入、End 后点拒绝、点写失败统一失败流和确认恢复；Runtime 测试确认等待型 Start 返回前已经同步发布记录状态，并确认任务移除检查点在存储确认后才返回。手机版 `TripStartOrchestratorTest` 5/5，覆盖纯编排回调在确认前不调用 `startLocation`、最终状态先于请求标记清理、Start 失败、权限等待期间失效和异常清理；`LocationEnginePolicyTest` 5/5 覆盖不可见 Start 等待、可见空闲/等待预览、不可见活动行程和定位权限阻断。

## 本地关卡

```zsh
./gradlew :gps-core:testDebugUnitTest \
  :gps-core:assembleDebugAndroidTest \
  :mobile-app:testDebugUnitTest \
  :mobile-app:assembleDebugAndroidTest \
  :mobile-app:lintDebug \
  :mobile-app:lintVitalRelease \
  :mobile-app:assembleDebug \
  :mobile-app:assembleRelease \
  :baselineprofile:assembleBenchmarkRelease \
  --console=plain
```

## Pixel_9 关卡

必须先确认 serial 对应的 AVD 名称，不能让 Gradle 自动选择已连接的 Redmi 真机：

```zsh
adb -s emulator-5554 shell getprop ro.boot.qemu.avd_name
ANDROID_SERIAL=emulator-5554 ./gradlew \
  :gps-core:connectedDebugAndroidTest \
  :mobile-app:connectedDebugAndroidTest \
  --console=plain
```

生成 Baseline Profile：

```zsh
ANDROID_SERIAL=emulator-5554 ./gradlew :mobile-app:generateReleaseBaselineProfile --console=plain
```

运行冷启动基准：

```zsh
ANDROID_SERIAL=emulator-5554 ./gradlew \
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
- 该结果证明普通进程信号终止后的确认边界恢复，不证明 `onTaskRemoved()` 检查点协程必然在回收前完成，也不证明 `force-stop`、断电或未确认内存尾批零丢失；API 27/API 29 后续已完成同类聚焦恢复，低存储和尾批损失量化仍待验收，整机重启另按下一节验证。

## M3 整机重启边界回归

- API 27 / `emulator-5556` 以活动行程 `RECORDING` 为前置条件执行普通 `adb -s emulator-5556 reboot`，不清数据、不使用 `force-stop`。
- 重启前数据库 `active_trip` 存在，观察到 `active_point` 为 118 个（sequence `2004..2121`），`TripRecordingService` 为 `location` 前台服务，GPS 注册为 1 条。
- `sys.boot_completed=1` 后、未打开应用前，`com.cargps.mobile` 进程、Service、手机版通知和 GPS 注册均不存在；`active_trip` 仍为 `RECORDING`。重启过程中的最终异步冲刷使点数现场观察为 171 个（`2004..2174`），不据此计算尾批损失率。
- 手动打开 `MainActivity` 后 UI 显示“记录中 / 已恢复”，Service 和 GPS 注册恢复；确认结束后活动行程、通知和定位注册清理。
- 结论：当前产品边界是“整机重启后数据保留，用户打开应用后恢复”，不承诺开机自动继续记录。详细摘要见 `artifacts/cargps-mobile-api27-reboot-summary.md`。
- 若未来要做开机自动恢复，必须新增独立验证：`BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`、Android 14 while-in-use location、`ACCESS_BACKGROUND_LOCATION`、Android 15 启动限制、受限应用和厂商电源管理；不能把普通 `START_STICKY` 结果外推为开机恢复。

## M4 Room 与数据损坏回归

- 2026-08-07 本地完整关卡通过：`gps-core` JVM 37/37、`mobile-app` JVM 4/4；AndroidTest 编译、debug/release 构建、`lintDebug`、`lintVitalRelease`、R8 和 `baselineprofile:assembleBenchmarkRelease` 均成功。
- Pixel_9 / API 35：`gps-core` instrumentation 12/12、`mobile-app` instrumentation 3/3 通过；设备由 `emulator-5554` 的 `ro.boot.qemu.avd_name = Pixel_9` 明确确认，未操作 Redmi。
- Room 迁移覆盖 v1 到 v4 活动行程与轨迹、v2 到 v4 已结束行程与历史轨迹、v3 到 v4 暂停行程，并验证 v4 完整 `TripStorage` 事务契约和确认检查点。
- 非法活动行程 mode 返回 `ActiveTripLoadResult.Corrupt`，数据库原始行保持不变；协调器测试验证损坏时 `storageReady = false` 且拒绝开始新行程。
- 缺失 `total_paused` 的畸形 v3 数据库在迁移失败后仍保持版本 3、旧表结构和原始行，证明当前迁移事务不会用半成品覆盖来源库。
- API 27/API 29 已完成 Debug 同签名与公开正式 `v0.2.0` 同证书两组覆盖升级：来源库均为 SQLite v3，覆盖后均为 Room v4，活动状态、开始时间、点数、距离、sequence 和点时间范围保持不变。
- Debug 组：API 27 为 39 点/33.50 米，API 29 为 29 点/33.50 米；正式组：API 27 为 29 点/37.62 米，API 29 为 30 点/28.74 米。升级后界面均显示“记录中 / 已恢复”，前台服务和单条 `cargps-location` 线程正常。
- 当前开发构建仍与公开旧包同为 `0.2.0 (3)`；该结果验证代码、证书和数据库迁移链，不替代最终候选提升版本号后的再次覆盖安装，也不表示任意文件级物理损坏都能自动恢复。

## M5 权限状态机回归

- 2026-08-07 M5 提交前完整本地关卡通过：JVM、AndroidTest 编译、`lintDebug`、`lintVitalRelease`、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease` 均成功。
- `TripAccessPolicyTest` 11/11，覆盖 Ready、仅近似定位、精度升级首次/重试/转设置、定位首次/永久拒绝、系统定位关闭、Android 13+ 通知首次/永久拒绝、API 27 无通知运行时权限和设置返回策略收敛。
- Pixel_9 / API 35：`gps-core` instrumentation 12/12、手机版 6/6；设置 Intent、初始未授权单屏无滚动和活动行程阻断后保留结束入口均通过。
- 真实系统 UI 已验证精确、仅近似、近似升级拒绝、定位首次/永久拒绝、通知首次/永久拒绝、通知设置返回和系统定位关闭/恢复；所有失败路径均未自动开始行程或循环弹窗。
- 设备证据为 `artifacts/cargps-mobile-m5-*` 的 PNG/XML；命令显式锁定 `emulator-5554` 并复核 `ro.boot.qemu.avd_name = Pixel_9`，未操作 Redmi。
- API 27/29/31/33 已完成适用的精确/近似、首次/永久拒绝、应用设置返回和系统定位关闭/恢复；API 33 还保留通知首次/永久拒绝证据。结合 Pixel_9 / API 35，跨版本权限矩阵已经闭环。
- API 27 的通用 Compose 测试宿主原本按 1024x600 横屏运行，未继承 `MainActivity` 的竖屏声明，导致两个底部权限入口可见性断言失败。测试现使用 UiAutomator 固定竖屏并在结束后解冻；从横屏 `SurfaceOrientation: 0` 启动后手机版 6/6 通过，API 29/33 也各复验 6/6，断言没有降级。

## 2026-08-08 P0/P1 seam 加固

- 本地 JVM 完整测试：`gps-core` 48/48、`mobile-app` 25/25 通过。
- 本地完整构建关卡：AndroidTest 编译、`lintDebug`、`lintVitalRelease`、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease` 全部成功；Gradle 汇总为 `BUILD SUCCESSFUL`，271 项任务中 57 项执行、214 项复用缓存。
- Pixel_9 复验：先确认 `emulator-5554` 的 `ro.boot.qemu.avd_name = Pixel_9`，再显式设置 `ANDROID_SERIAL=emulator-5554`；`gps-core` 12/12、`mobile-app` 6/6 instrumentation 通过。该关卡验证现有设备测试未回退，不替代低存储注入或真实 Service 全路径异常竞态测试。
- 低存储恢复边界：同步定位点写入失败进入 `TripPersistenceState.FAILED`，保持 `RECORDING` 和最后确认统计；新的确认检查点到达后恢复 `CONFIRMED` 并清除过期错误。
- 确认边界重绑：`QueuedTripStorage.confirmedCheckpoints` 保留最近一条成功确认值，晚到的 Service/Activity 订阅者仍可取得最后 `sequence` 和时间。
- 任务移除边界：`DashboardRuntime.checkpointTripWritesAndAwait()` 等待尾批检查点完成后才返回；`TripRecordingService.onTaskRemoved()` 在 Service 协程中调用，不阻塞主线程，但系统仍可能在协程完成前回收进程。
- Start 竞态：最终状态处理移到 `startRequested` 清理之前，避免状态收集器看到旧 `IDLE` 快照而误回收正在确认的 Service。

## M6 单一事件队列回归

- 当前开发线已加入唯一 `Channel.UNLIMITED` 事件队列，固定先 Restore，再串行处理 Start、AppendPoint、Pause、Resume、End、Tick 和 Checkpoint；关闭或 actor 异常后不会留下悬挂等待者或继续假接收事件。
- `DashboardRuntime.startTripAndAwait()` 在存储确认后同步发布并返回 `DashboardState`；`LocationEnginePolicy` 只允许已确认活动行程在客户端不可见时启动后台定位，`START_PENDING` 不再等价于活动行程。客户端可见时仍允许定位预览，Runtime 在 `IDLE` 状态不会写入行程点。
- 本地完整关卡此前为 `gps-core` 44/44、手机版 24/24 JVM；本轮 seam 加固后 `gps-core` 48/48、手机版 25/25 JVM，AndroidTest 编译、`lintDebug`、`lintVitalRelease`、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease` 均成功。
- Pixel_9 / API 35 instrumentation：本轮重新执行后 `gps-core` 12/12、手机版 6/6。设备由 `emulator-5554` 的 `ro.boot.qemu.avd_name = Pixel_9` 明确确认，所有 Gradle 设备任务通过 `ANDROID_SERIAL` 锁定，未操作 Redmi。
- Pixel_9 运行时短路径：开始后 UI 为“记录中”，Service 为 `location` 前台类型；以应用 UID 连续两次 ensure 后 `cargps-location` 线程仍为 1；结束后回到“等待开始”且历史增加一段，Home 后 Service 清除，crash buffer 无 CarGPS 记录。
- API 27/API 29 的聚焦 `START_STICKY` 恢复已通过；Pixel_9 / API 35、Android 10 / API 29 与 Android 8.1 / API 27 的完整 30 分钟 Home/切换应用/锁屏已通过；API 27 整机重启边界也已验证为“不开机自动拉起，打开应用后恢复”。低存储、尾批损失量化和真实设备 2 小时长测仍未完成。
- API 31 已完成可见开始、`location` 前台服务、Home、锁屏、单定位线程、活动行程撤权阻断和结束后 Home 清理短路径；它仍不计作 30 分钟长测。

## M7 Baseline Profile 与冷启动对照

- `BaselineProfileGenerator` 拆成两个独立采集场景：`generateStartup` 只采集授权后稳定首屏并写入 Startup Profile；`generateCriticalUserJourneys` 覆盖开始行程、Home、返回重绑 Service、暂停、继续和结束确认，不把完整行程规则全部标成启动布局。
- `Pixel_9 / emulator-5554 / API 35` 的 `generateReleaseBaselineProfile` 成功：Baseline Profile 50,591 行，Startup Profile 49,417 行；`DashboardViewModel`、`DashboardViewModelFactory` 命中均为 0。
- 新 Profile 命中 `DashboardRuntime`、`TripSessionEventQueue`、`TripRecordingService`、`LocationEnginePolicy`、`TripStartOrchestrator` 和 `RoomTripStorage`；Release APK 内含 `assets/dexopt/baseline.prof`（4,224 字节）与 `baseline.profm`（199 字节）。
- 冷启动第一轮：无预编译最小/中位/最大为 278.02/306.48/333.31ms；强制 Baseline Profile 为 244.77/253.11/445.35ms，中位改善约 17.4%，但存在一次明显离群点。
- 冷启动第二轮：无预编译最小/中位/最大为 270.18/312.96/345.67ms；强制 Baseline Profile 为 228.74/256.06/338.91ms，中位改善约 18.2%。原始 JSON 保存在本地 `artifacts/cargps-mobile-m7-startup-benchmark-run1.json` 和 `run2.json`。
- `v0.2.0` 历史无预编译中位为 241.9ms；当前 M1-M6 无预编译中位约 306-313ms，说明运行时架构扩展带来启动成本。Profile 能显著回收当前构建的启动开销，但不能据此宣称整体启动无回退。
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

Pixel_9 / API 35、Android 10 / API 29 与 Android 8.1 / API 27 的 30 分钟后台记录已经完成，API 27 整机重启边界也已完成验证；尚未完成的低存储、尾批损失量化、最终候选版本号与签名升级复验和真实设备长测见 [剩余高风险迁移项](./migration-risks.md)，不能用常规长测替代异常环境门槛。当前不支持的“开机自动恢复”另需产品决策和权限迁移评审。
