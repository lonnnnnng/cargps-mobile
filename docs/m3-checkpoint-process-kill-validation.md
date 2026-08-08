# M3 Checkpoint 提交前进程回收验证

作者：long

验证时间：2026-08-08 20:25:41（北京时间，UTC+8）

## 目标

验证活动行程已经接受 16 个定位点、但 `QueuedTripStorage` 尚未把该批次真正提交到 Room 时，整个应用进程被 `SIGKILL` 后的恢复边界。该场景量化的是当前最坏未确认尾批，不用于证明断电、`force-stop` 或物理低存储零丢点。

## 验证实现

- `M3CheckpointProbeService` 只存在于 `mobile-app/src/probe`，普通 Debug 与正式 Release 都不包含该组件。
- 探针使用真实 `RoomTripStorage + QueuedTripStorage + DashboardRuntime`，在 16 点批次调用委托 Room 事务前阻塞并输出 `CARGPS_M3_CHECKPOINT_BLOCKED`。
- zsh 脚本先复核 serial、AVD 名称和 API，再由 shell 启动独立 Probe 探针与 `location` 类型的真实 `TripRecordingService`。
- 观察 sentinel 后，通过 `run-as com.cargps.mobile kill -9 <oldPid>` 以应用 UID 终止整个进程。
- 脚本不启动第二次 instrumentation，也不在检查前调用 `force-stop`；它等待新的 PID、`START_STICKY` 前台 Service 和 CarGPS 通知恢复。
- 恢复后的 Room 边界由 probe-only 只读 query action 查询，避免设备缺少 `sqlite3` 时拉取 WAL 得到不一致快照。

最初尝试使用 instrumentation 建立阻塞窗口，但 Android 测试框架会在 instrumentation 完成或目标进程崩溃时自动 force-stop 被测包，系统因而不会执行普通 `START_STICKY` 恢复。最终探针改为独立可调试的 probe-only Service，保留了真实系统恢复语义，也不改变普通 Debug 的 Service 私有性测试。

## 执行命令

```zsh
./scripts/verify-m3-checkpoint-process-kill.zsh emulator-5554
./scripts/verify-m3-checkpoint-process-kill.zsh emulator-5556
```

脚本默认在证据采集完成后停止 CarGPS 测试进程；需要现场复核 Service 时可临时设置 `KEEP_RECOVERED_STATE=1`，采集完成后再显式停止应用。

## 结果

| 设备 | 旧 PID | 新 PID | Service | Room 活动状态 | 确认点数 | 未确认损失窗口 |
| --- | ---: | ---: | --- | --- | ---: | ---: |
| Pixel_9 / API 35 / `emulator-5554` | 2786 | 2842 | `isForeground=true`、`types=0x00000008`、`restartCount=1` | `RECORDING` | 0 | 16 点 |
| CASKA_1024x600 / API 27 / `emulator-5556` | 22226 | 22285 | `isForeground=true`、`restartCount=1` | `RECORDING` | 0 | 16 点 |

Pixel_9 的 `ApplicationExitInfo` 记录旧 PID `2786` 为 `reason=SIGNALED`、`status=9`；脚本完成后对新 PID `2842` 的清理另记为 `USER REQUESTED / FORCE STOP`，不与恢复结论混淆。API 27 的 `dumpsys activity exit-info` 命令不可用，因此该端以旧/新 PID、Service `restartCount=1`、前台通知和 Room 查询结果构成证据链。

## 结论与边界

- 已验证：整个进程在 16 点批次提交前被杀时，系统能以新 PID 重建 `START_STICKY` Service，活动行程仍为 `RECORDING`，恢复上限是最近确认检查点。
- 已量化：本场景中数据库确认点数为 0，16 个已接受但未提交的点全部丢失；这与当前 `MAX_PENDING_POINT_COUNT = 16` 的内存上限一致。
- 未改变产品语义：正常批次仍约 1 秒确认；异常写失败只保证未确认点数量有界，不保证零丢点或时间上限。
- 仍未验证：物理 `ENOSPC`、真实系统 GPS 注册停止/恢复、Activity 与 Service 进程同时复杂重建、断电、`force-stop`、最终候选签名升级、Profile 重采集和真实设备 2 小时长测。
