# 手机版测试与性能基线

作者：long

更新日期：2026-08-07 18:08:00（北京时间）

## 测试分层

- `gps-core/src/test`：质量门、速度、NMEA、增量行程统计、ViewModel 恢复和后台存储队列。
- `gps-core/src/androidTest`：SQLite v1 到 v3 迁移、事务完成、批量点顺序和重开恢复。
- `mobile-app/src/androidTest`：Pixel_9 首屏核心遥测可见、滚动节点为 0。
- `baselineprofile`：生成 Release Baseline Profile，并执行无预编译冷启动 Macrobenchmark。

项目没有引入 DI 框架。当前依赖构造仅有 `DashboardViewModelFactory` 一个入口，测试直接使用 fake `TripStorage`，引入 Hilt 的构建和代码生成成本高于现阶段收益。

## 本地关卡

```zsh
./gradlew :gps-core:testDebugUnitTest \
  :gps-core:assembleDebugAndroidTest \
  :mobile-app:assembleDebugAndroidTest \
  :mobile-app:lintDebug \
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
- Debug APK：约 18MB；R8/资源压缩后的未签名 Release APK：约 940KB。

Macrobenchmark 已显式允许 `EMULATOR`，这些数值只用于同一 Pixel_9 AVD 的版本间相对比较，不能解释为真机绝对性能。
