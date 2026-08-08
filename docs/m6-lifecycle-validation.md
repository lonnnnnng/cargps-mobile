# M6 Activity 重建与 actor 终态回归摘要

作者：long

更新时间：2026-08-08 21:00:35（北京时间，UTC+8）

## 验证范围

- `active_trip_activity_recreation_rebinds_existing_service_without_duplicate_location`：先建立活动行程和前台 Service，再通过真实 `ActivityScenario.recreate()` 替换 `MainActivity`。重建后的页面重新显示“暂停行程”，Service 依赖工厂仍只创建一次，定位 `start/stop` 计数相对重建前均不增加，证明 Activity 重建只发生 Binder 重绑，没有创建第二个 Service 或第二套定位注册。
- `second_actor_failure_enters_terminal_state_without_rebuild_loop`：活动行程第一次 actor 异常后允许唯一一次 Restore 和定位重启；第二次 actor 异常进入终态，通知显示“行程处理异常”，后续定位只更新仪表、不再进入已关闭队列，存储 Restore 次数保持 2、定位启动次数保持 2。

## 设备边界

- `emulator-5554`：`Pixel_9`，API 35。
- `emulator-5556`：`CASKA_1024x600(AVD) - 8.1.0`，API 27，1024x600。
- 所有 Gradle 设备任务都通过 `ANDROID_SERIAL` 显式锁定；未操作 Redmi `wsvwypiz7xwslvl7`，未操作 Biu。

## 执行命令

```zsh
./gradlew :mobile-app:compileDebugAndroidTestKotlin --console=plain

ANDROID_SERIAL=emulator-5554 ./gradlew \
  :mobile-app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cargps.mobile.TripRecordingServiceLifecycleInstrumentedTest \
  --console=plain

ANDROID_SERIAL=emulator-5556 ./gradlew \
  :mobile-app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cargps.mobile.TripRecordingServiceLifecycleInstrumentedTest \
  --console=plain

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

ANDROID_SERIAL=emulator-5554 ./gradlew \
  :gps-core:connectedDebugAndroidTest \
  :mobile-app:connectedDebugAndroidTest \
  --console=plain

ANDROID_SERIAL=emulator-5556 ./gradlew \
  :gps-core:connectedDebugAndroidTest \
  :mobile-app:connectedDebugAndroidTest \
  --console=plain
```

## 结果

- AndroidTest Kotlin 编译通过。
- Pixel_9 Service 生命周期类：8/8。
- Android 8.1 / API 27 Service 生命周期类：8/8。
- 本地 JVM：`gps-core` 58/58、`mobile-app` 33/33。
- 完整本地关卡：271 tasks，`BUILD SUCCESSFUL in 12s`。
- Pixel_9 完整 instrumentation：`gps-core` 14/14、`mobile-app` 14/14。
- Android 8.1 / API 27 完整 instrumentation：`gps-core` 14/14、`mobile-app` 14/14。

首次 Pixel_9 Service 类回归中，Activity 重建用例曾因断言“历史 stop 次数必须为 0”失败。读取测试报告后确认，Service 创建时会先对初始空闲态执行一次 `STOP` 决策，随后活动行程再执行 `START`；这不是重建导致的停止。测试已改为比较重建前后的 `start/stop` 计数不变，专项 1/1、完整 Service 类 8/8 和双设备完整套件随后均通过。

## 尚未关闭的风险

- 物理磁盘 `ENOSPC`/低存储下，真实 Room、系统 GPS 注册停止与恢复耗时仍未验证。
- 后续 [M3 Checkpoint 提交前进程回收验证](./m3-checkpoint-process-kill-validation.md) 已在 Pixel_9/API 35 与 API 27 使用 probe-only 真实 Room 阻塞探针补齐：系统以新 PID 恢复 `START_STICKY` Service，但 16 个未确认点全部丢失、确认点数保持 0。该结论明确恢复上限，不代表断电、`force-stop` 或物理低存储零丢点。
- 本轮 instrumentation 只覆盖“活动行程中的 Activity 重建并重绑现有 Service”；后续 [M6 Activity 与 Service 同进程回收重绑验证](./m6-process-recreation-rebind-validation.md) 已在 Pixel_9/API 35 与 API 27 补齐前台 Activity 与 Service 同进程 `SIGKILL`、Service 独立恢复和用户返回重绑，且两端均保持唯一进程、唯一 Service 与唯一定位线程。该证据仍不代表所有厂商任务栈或物理低存储/GPS 故障。
- 最终候选仍需提升版本号、重跑同签名覆盖升级、重采集 Profile，并在取得单独设备授权后完成真实设备 2 小时长测。
