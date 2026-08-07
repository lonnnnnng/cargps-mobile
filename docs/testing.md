# 手机版测试与性能基线

作者：long

更新日期：2026-08-08 05:23:04（北京时间，UTC+8）

## 测试分层

- `gps-core/src/test`：质量门、速度、NMEA、增量行程统计、行程协调器、单一事件队列、运行时恢复和后台存储队列。
- `gps-core/src/androidTest`：旧 SQLite adapter 契约、Room v1 到 v4 显式迁移、v4 事务契约、损坏数据保留和迁移失败回滚。
- `mobile-app/src/androidTest`：Pixel_9 首屏核心遥测与无滚动、权限阻断 UI 与设置 Intent，以及前台服务 Manifest、显式 Intent、不可变 `PendingIntent` 安全约束。
- `mobile-app/src/test`：启动型 Service 的恢复策略，以及行程启动权限策略；权限测试覆盖精确/近似定位、首次/永久拒绝、系统定位、通知权限、API 27 和设置返回收敛。
- `baselineprofile`：分别生成 Release Baseline/Startup Profile，并执行无预编译与强制 Baseline Profile 的冷启动 Macrobenchmark。

项目没有引入 DI 框架。`CarGpsApplication` 负责创建进程内唯一 `DashboardRuntime` 与存储队列，Activity 和 Service 通过同一实例协作；领域测试直接使用 fake `TripStorage`，现阶段不引入 Hilt。

生产存储为 `RoomTripStorage`；`SqliteTripStorage` 只用于旧 schema fixture 和 adapter 兼容测试。Room v4 schema 导出到 `gps-core/schemas/com.cargps.storage.RoomTripDatabase/4.json`，所有旧版本迁移均显式注册，禁止 destructive fallback。

`TripSessionCoordinatorTest` 通过 fake storage 注入开始、暂停、恢复、结束和异步写入错误，验证元数据命令失败时保持上一个确认模式、重复命令不重复写、多段暂停只扣除真实暂停时长。`QueuedTripStorageTest` 另外验证瞬时批量失败重试成功不误报、连续失败由屏障抛出，以及元数据失败可被确认屏障捕获；轨迹实时统计与最后确认检查点按不同边界断言。

M6 当前开发线的 `TripSessionEventQueueTest` 5/5，覆盖 Restore 固定优先与完整 FIFO、等待型 Start、关闭取消、actor 异常终止和消费时连续 Toggle。协调器测试补充 End 前尾点纳入、End 后点拒绝；Runtime 测试确认等待型 Start 返回前已经同步发布记录状态。手机版 `TripStartOrchestratorTest` 4/4，覆盖纯编排回调在确认前不调用 `startLocation`、Start 失败、权限等待期间失效和异常清理；`LocationEnginePolicyTest` 5/5 覆盖不可见 Start 等待、可见空闲/等待预览、不可见活动行程和定位权限阻断。

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
- 30 分钟后台监测在第 5 个一分钟样本后被外部 `force-stop` 中断。`ApplicationExitInfo` 为 `USER REQUESTED / FORCE STOP`，不是崩溃、ANR 或系统回收；该次结果无效，完整 30 分钟关卡仍未通过。
- 对应截图和 UI 树见 `artifacts/cargps-mobile-m2-*`；这些本地验证文件默认不提交 Git。

## M3 进程异常退出恢复回归

- 本地 JVM：`gps-core` 36/36、手机版恢复策略 4/4 通过；覆盖最后确认检查点恢复、批次确认 Flow、生命周期主动冲刷和初始存储恢复门禁。
- Pixel_9 API 35：SQLite instrumentation 5/5、`mobile-app` instrumentation 3/3；SQLite 重开后检查点准确返回确认点数、最后 sequence 和最后点时间。
- 破坏性场景：活动行程在 Home 后保持 `location` 前台服务，原 PID `9235` 只有一条 `cargps-location` 线程；通过应用自身 UID 发送 `SIGKILL`。
- 系统 `ApplicationExitInfo` 记录 `reason=SIGNALED`、`status=9`，随后自动创建新 PID `9355`，`restartCount=1`；未手工启动 Service。
- 新进程恢复为 `START_STICKY`、`isForeground=true`、类型 `0x00000008`，通知仍为“CarGPS · 正在记录”，定位线程仍为 1；Activity 回前台显示“记录中 / 已恢复”。
- 从 UI 结束行程后通知与 Service 清除，界面回到空闲，crash buffer 为空。证据为 `artifacts/cargps-mobile-m3-after-sigkill.png` 和 `.xml`。
- 该结果证明普通进程信号终止后的确认边界恢复，不证明 `onTaskRemoved()` 异步检查点必然完成，也不证明 `force-stop`、断电或未确认内存尾批零丢失；API 27/API 29 仍待设备验收。

## M4 Room 与数据损坏回归

- 2026-08-07 本地完整关卡通过：`gps-core` JVM 37/37、`mobile-app` JVM 4/4；AndroidTest 编译、debug/release 构建、`lintDebug`、`lintVitalRelease`、R8 和 `baselineprofile:assembleBenchmarkRelease` 均成功。
- Pixel_9 / API 35：`gps-core` instrumentation 12/12、`mobile-app` instrumentation 3/3 通过；设备由 `emulator-5554` 的 `ro.boot.qemu.avd_name = Pixel_9` 明确确认，未操作 Redmi。
- Room 迁移覆盖 v1 到 v4 活动行程与轨迹、v2 到 v4 已结束行程与历史轨迹、v3 到 v4 暂停行程，并验证 v4 完整 `TripStorage` 事务契约和确认检查点。
- 非法活动行程 mode 返回 `ActiveTripLoadResult.Corrupt`，数据库原始行保持不变；协调器测试验证损坏时 `storageReady = false` 且拒绝开始新行程。
- 缺失 `total_paused` 的畸形 v3 数据库在迁移失败后仍保持版本 3、旧表结构和原始行，证明当前迁移事务不会用半成品覆盖来源库。
- 尚未执行 API 27/API 29 升级安装回归，也不把上述 fixture 结果解释为任意文件级物理损坏都能自动恢复。

## M5 权限状态机回归

- 2026-08-07 M5 提交前完整本地关卡通过：JVM、AndroidTest 编译、`lintDebug`、`lintVitalRelease`、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease` 均成功。
- `TripAccessPolicyTest` 11/11，覆盖 Ready、仅近似定位、精度升级首次/重试/转设置、定位首次/永久拒绝、系统定位关闭、Android 13+ 通知首次/永久拒绝、API 27 无通知运行时权限和设置返回策略收敛。
- Pixel_9 / API 35：`gps-core` instrumentation 12/12、手机版 6/6；设置 Intent、初始未授权单屏无滚动和活动行程阻断后保留结束入口均通过。
- 真实系统 UI 已验证精确、仅近似、近似升级拒绝、定位首次/永久拒绝、通知首次/永久拒绝、通知设置返回和系统定位关闭/恢复；所有失败路径均未自动开始行程或循环弹窗。
- 设备证据为 `artifacts/cargps-mobile-m5-*` 的 PNG/XML；命令显式锁定 `emulator-5554` 并复核 `ro.boot.qemu.avd_name = Pixel_9`，未操作 Redmi。
- API 27/29/31/33 权限矩阵仍未验收，不能用 API 35 结果外推。

## M6 单一事件队列回归

- 当前开发线已加入唯一 `Channel.UNLIMITED` 事件队列，固定先 Restore，再串行处理 Start、AppendPoint、Pause、Resume、End、Tick 和 Checkpoint；关闭或 actor 异常后不会留下悬挂等待者或继续假接收事件。
- `DashboardRuntime.startTripAndAwait()` 在存储确认后同步发布并返回 `DashboardState`；`LocationEnginePolicy` 只允许已确认活动行程在客户端不可见时启动后台定位，`START_PENDING` 不再等价于活动行程。客户端可见时仍允许定位预览，Runtime 在 `IDLE` 状态不会写入行程点。
- 本地完整关卡通过：`gps-core` 44/44、手机版 24/24 JVM；AndroidTest 编译、`lintDebug`、`lintVitalRelease`、Debug/Release、R8、资源压缩和 `baselineprofile:assembleBenchmarkRelease` 均成功。
- Pixel_9 / API 35 instrumentation：`gps-core` 12/12、手机版 6/6。设备由 `emulator-5554` 的 `ro.boot.qemu.avd_name = Pixel_9` 明确确认。
- Pixel_9 运行时短路径：开始后 UI 为“记录中”，Service 为 `location` 前台类型；以应用 UID 连续两次 ensure 后 `cargps-location` 线程仍为 1；结束后回到“等待开始”且历史增加一段，Home 后 Service 清除，crash buffer 无 CarGPS 记录。
- 尚未完成 API 27/API 29 的 `START_STICKY` 恢复、完整 30 分钟 Home/切换应用/锁屏、设备重启/低存储和真实设备 2 小时长测；Pixel_9 短路径不能替代这些门槛。

## M7 Baseline Profile 与冷启动对照

- `BaselineProfileGenerator` 拆成两个独立采集场景：`generateStartup` 只采集授权后稳定首屏并写入 Startup Profile；`generateCriticalUserJourneys` 覆盖开始行程、Home、返回重绑 Service、暂停、继续和结束确认，不把完整行程规则全部标成启动布局。
- `Pixel_9 / emulator-5554 / API 35` 的 `generateReleaseBaselineProfile` 成功：Baseline Profile 50,591 行，Startup Profile 49,417 行；`DashboardViewModel`、`DashboardViewModelFactory` 命中均为 0。
- 新 Profile 命中 `DashboardRuntime`、`TripSessionEventQueue`、`TripRecordingService`、`LocationEnginePolicy`、`TripStartOrchestrator` 和 `RoomTripStorage`；Release APK 内含 `assets/dexopt/baseline.prof`（4,224 字节）与 `baseline.profm`（199 字节）。
- 冷启动第一轮：无预编译最小/中位/最大为 278.02/306.48/333.31ms；强制 Baseline Profile 为 244.77/253.11/445.35ms，中位改善约 17.4%，但存在一次明显离群点。
- 冷启动第二轮：无预编译最小/中位/最大为 270.18/312.96/345.67ms；强制 Baseline Profile 为 228.74/256.06/338.91ms，中位改善约 18.2%。原始 JSON 保存在本地 `artifacts/cargps-mobile-m7-startup-benchmark-run1.json` 和 `run2.json`。
- `v0.2.0` 历史无预编译中位为 241.9ms；当前 M1-M6 无预编译中位约 306-313ms，说明运行时架构扩展带来启动成本。Profile 能显著回收当前构建的启动开销，但不能据此宣称整体启动无回退。
- 上述数据来自未锁 CPU 的模拟器，只能作为同一 Pixel_9 AVD 的相对证据，不能外推真机绝对性能。

## v0.2.0 发布验收

- 提交与 tag：`0995eb2` / `v0.2.0`；发布时工作区与 `origin/main` 同步。
- GitHub Release：[CarGPS 手机版 v0.2.0](https://github.com/lonnnnnng/cargps-mobile/releases/tag/v0.2.0)，状态为 latest、非草稿、非预发布。
- APK：`com.cargps.mobile`，`versionCode = 3`，`versionName = 0.2.0`，`minSdk = 27`，`targetSdk = 36`。
- SHA-256：`8fc1238c1fdc45db0e49d3d78243abdfe834fe15e87008e53004ae3eea366bc2`；本地 `SHA256SUMS`、GitHub asset digest 和重新下载后的文件一致。
- 签名：APK Signature Scheme v2 通过，证书 SHA-256 为 `7807a35ea864ad038b6f3851b79333e8aedd90bb7f9521fd5ffb0d7c0375d521`，与 `v0.1.1` 一致。
- 对齐与优化：Build Tools 36 的 `zipalign -c -P 16 4` 通过；APK 内含 `assets/dexopt/baseline.prof` 和 `baseline.profm`。
- 安装：正式包在 `Pixel_9` / API 35 冷启动成功；UI 树滚动节点为 0，crash buffer 为空。切换 debug 到 release 签名时仅清除了该模拟器内的测试数据。

尚未完成的 M2-M6 跨 API 运行时、权限、恢复、生命周期并发与长测，以及设备重启、低存储和真实设备场景见 [剩余高风险迁移项](./migration-risks.md)，不能用上述 Pixel_9 / API 35 短路径结果替代。
