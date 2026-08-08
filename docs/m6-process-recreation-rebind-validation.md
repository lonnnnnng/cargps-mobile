# M6 Activity 与 Service 同进程回收重绑验证

作者：long

更新时间：2026-08-08 21:00:35（北京时间，UTC+8）

## 验证目标

在 `MainActivity` 位于前台、活动行程已经确认、`TripRecordingService` 正以 `location` 类型运行时，通过应用自身 UID 对 `com.cargps.mobile` 整个进程发送 `SIGKILL`。验证以下边界：

1. Activity 与 Service 所在旧进程同时退出，而不是只执行 `ActivityScenario.recreate()`。
2. 没有 Activity 绑定时，系统先以新 PID 独立恢复 `START_STICKY` Service、前台通知和定位注册。
3. 用户重新打开应用后，新的 Activity 绑定恢复进程中的同一个 Service，不创建第二个应用进程、第二个 `ServiceRecord` 或第二条 `cargps-location` 线程。
4. 页面从真实 Room 活动行程显示“记录中 / 已恢复”，恢复后的行程可以正常结束。

该场景补充 [M6 Activity 重建与 actor 终态回归](./m6-lifecycle-validation.md)：后者验证同一进程内替换 Activity 实例，本场景验证 Activity 与 Service 所在进程一起被回收后的系统级恢复和用户返回重绑。

## 验证实现

- 脚本：[verify-m6-process-recreation-rebind.zsh](../scripts/verify-m6-process-recreation-rebind.zsh)。
- 只使用 `probe` APK 临时导出的真实 `TripRecordingService`，普通 Debug 和正式 Release 的 Service 仍为 `exported=false`。
- 不启动 `M3CheckpointProbeService`，因此 Service 使用生产装配的 `CarGpsApplication + DashboardRuntime + QueuedTripStorage + RoomTripStorage + LocationEngine`，没有替换存储或系统 GPS 边界。
- 开始行程前要求 `MainActivity` 确实出现在 `mCurrentFocus` / `mFocusedApp` / `topResumedActivity`，避免仅凭历史 Window 记录形成假阳性。
- 旧进程通过 `run-as com.cargps.mobile kill -9 <oldPid>` 终止；恢复期间不调用 `force-stop`，也不手工重启 Service。
- Service 独立恢复后才显式启动 `MainActivity`，模拟用户返回应用。验证完成后的 `force-stop` 只用于清理 CarGPS 测试现场，不参与恢复结论。
- 脚本在任何 ADB 调用前拒绝 `emulator-5554`、`emulator-5556` 以外的 serial；非法 Redmi serial 返回码为 2。

## 执行命令

```zsh
./scripts/verify-m6-process-recreation-rebind.zsh emulator-5554
./scripts/verify-m6-process-recreation-rebind.zsh emulator-5556
```

本地完整关卡：

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

## 当前结果

| 设备 | 旧 PID | 新 PID | Service 独立恢复 | 用户返回重绑 | 进程数 | ServiceRecord | 定位线程 | UI | 正常结束 |
| --- | ---: | ---: | --- | --- | ---: | ---: | ---: | --- | --- |
| Android 8.1 / API 27 / `emulator-5556` | 24946 | 25055 | `restartCount=1` | 绑定同一新 PID | 1 | 1 | `1 -> 1` | `记录中 / 已恢复` | 通过 |
| Pixel_9 / API 35 / `emulator-5554` | 8481 | 8587 | `restartCount=1` | 绑定同一新 PID | 1 | 1 | `1 -> 1` | `记录中 / 已恢复` | 通过 |

两端完整脚本输出均为 `M6_PROCESS_RECREATION_REBIND_RESULT=PASS`。Pixel_9 的 `ApplicationExitInfo` 记录旧 PID `8481` 为 `reason=2 (SIGNALED)`、`status=9`；API 27 没有该命令，因此该端使用旧/新 PID、`restartCount=1`、前台通知、唯一进程、唯一 `ServiceRecord`、定位线程计数和恢复 UI 组成证据链。

本地完整关卡为 308 tasks，5 项执行、303 项保持最新，`BUILD SUCCESSFUL in 5s`。

## 无效尝试与修正

首次脚本错误地把“系统自动重建前台 Activity”当作预期。API 27 现场证明：进程被杀后系统把前台切回 Launcher，只自动恢复 sticky Service；Activity 需要用户重新打开应用。这符合当前产品“Service 后台恢复、用户返回后绑定”的边界，不应宣称 Activity 自动弹回前台。

首次 Pixel_9 尝试在旧进程退出后回到此前位于下层任务栈的 `com.example.videoviewer`。当时 CarGPS Service 已从 PID `3638` 恢复为 `3749`、`restartCount=1`；这进一步证明 Android 不会自动把被杀 Activity 弹回前台，但旧脚本尚未执行“用户返回重绑”，因此整轮保持无效。

修正两阶段语义后的第二次 Pixel_9 尝试已走通 Service 恢复和 Activity 重绑（PID `8083 -> 8185`），但脚本只接受 `reason=SIGNALED`，没有兼容系统实际输出的 `reason=2 (SIGNALED)`，因证据解析失败仍未计入。脚本随后改为按旧 PID 提取单条 `ApplicationExitInfo` 记录并兼容两种格式，最终从清数据重新执行得到表中 `8481 -> 8587` 的完整通过结果。

## 当前结论与边界

- **已验证（Pixel_9/API 35 与 API 27）**：Activity 前台与 Service 同进程被回收后，Service 可以先独立恢复；用户重新打开应用时，Activity 绑定同一恢复进程，仍只有一个 Service 和一条定位线程，活动行程可见且可正常结束。
- 本场景不证明 Activity 会自动恢复到前台；当前系统行为是回到 Launcher，用户打开应用后再重绑。
- 本场景没有注入物理 `ENOSPC` 或真实 GPS Provider 注册失败，不替代低存储和系统 GPS 恢复门禁。
- 本场景仍是模拟器证据，不替代取得单独授权后的真机 2 小时长测。
- 未操作 Redmi `wsvwypiz7xwslvl7`，未操作 Biu。
