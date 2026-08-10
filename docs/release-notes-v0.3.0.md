# CarGPS 手机版 v0.3.0

作者：long

`v0.3.0` 将手机版拆分后的 M1-M7 架构迁移正式交付。应用包名保持 `com.cargps.mobile`，版本号为 `0.3.0 (4)`，最低系统仍为 Android 8.1 / API 27；车机版项目与本次发布互不改动。

## 行程、定位与恢复

- `TripSessionEventQueue` 作为进程内唯一事件入口，固定先恢复，再按 FIFO 处理开始、定位点、暂停、继续、结束、时钟和检查点。
- `TripSessionCoordinator` 只在存储确认后切换行程元数据状态；开始、暂停、继续和结束在确认期间显示处理中，失败时保留上一已确认模式。
- `TripRecordingService` 成为唯一 `LocationEngine` 所有者，使用 `location` 类型前台服务；Activity 只绑定共享 Runtime、观察状态并发送用户命令。
- `LocationEngineSessionController` 统一可见性重绑、`START_STICKY` 恢复、权限/Provider 变化和存储故障的启停决策，重复入口不会注册第二条定位线程。
- 普通进程被 `SIGKILL` 后，系统可用新 PID 恢复前台 Service；Activity 返回时重绑同一进程、同一 Service 和同一定位线程。
- actor 首次异常最多自动重建一次，并从已确认存储状态 Restore；第二次异常进入终态，不循环重建或继续接收行程点。

## 存储与有界背压

- 生产存储迁移到 Room 2.8.4 / schema v4，显式注册 `1 -> 2 -> 3 -> 4` 迁移，不启用 destructive fallback。
- 活动行程损坏与“没有活动行程”分开建模；损坏时保留原始数据并阻止新行程，不能静默清库。
- 轨迹点按最多 16 点或 1 秒批量事务落库；持久化持续失败时只保留 16 个未确认点，第 17 个点同步拒绝，防止内存无界增长。
- 存储失败或背压期间停止新的定位输入，保留活动行程、前台通知和结束入口；尾批成功确认后自动恢复定位。
- Runtime 在失败窗口断开上一定位样本并清空速度平滑基线，恢复首点不会跨故障窗口补算距离或速度。
- Room 真实连接只读故障与受控真实 `SQLiteFullException` 已验证批次原子回滚、16 点有界背压和恢复检查点。

## 权限、界面与性能

- 权限状态机区分精确/近似定位、首次/永久拒绝、系统定位关闭和 Android 13+ 通知拒绝；API 27 不要求通知运行时权限。
- Pixel_9 继续作为竖屏视觉基线，日期时间、中文经纬度与定位指标、行程统计、最近记录和操作区保持单屏无滚动。
- Release 启用 R8、资源压缩和 Baseline Profile。
- 当前热路径 Profile 命中 `LocationEngineSessionController`、`DashboardRuntime`、`TripSessionEventQueue` 和 `TripRecordingService`，旧 ViewModel 与 probe-only 探针不进入正式热路径。

## 验证基线

- JVM：`gps-core` 58/58、`mobile-app` 33/33。
- Pixel_9 / API 35 与 Android 8.1 / API 27：存储类各 14/14、Runtime/Room 类各 2/2，当前 `gps-core` instrumentation 合计各 16/16；手机版最近完整 instrumentation 各 14/14，真实 Service 生命周期 seam 各 8/8。
- Pixel_9 / API 35、Android 10 / API 29、Android 8.1 / API 27 已完成 30 分钟 Home、系统设置、锁屏和解锁返回回归；API 27 另验证整机重启后数据保留、用户打开应用后恢复。
- Checkpoint 提交前杀掉整个进程时，Pixel_9/API 35 与 API 27 均能恢复活动行程和前台 Service，但 16 个未确认点可以全部丢失；恢复上限是最后确认检查点，不是零丢点。
- 发布关卡已通过：本地完整构建共 309 项任务成功，签名 Release、R8/资源压缩、Profile 资产、证书一致性和 Pixel_9 安装启动均已核验。

## 已接受残余风险与未复验边界

- 受控 `SQLITE_FULL`、连接只读与 `SIGKILL` 探针不等价于物理磁盘 `ENOSPC`、断电、`force-stop` 或真实系统 GPS 故障。
- 物理低存储下的通知、GPS 注册停止/恢复、恢复耗时和设备级恢复首点未验证；这些场景作为已接受残余风险保留，不构成本次发布阻断，也不能宣称零丢点。
- API 27/API 29 提升到 `0.3.0 (4)` 后的最终覆盖升级本次未复验；此前同证书、同代码链的公开 `v0.2.0` 到 Room v4 覆盖升级证据继续保留，但不能外推为 `v0.3.0` 最终版本结果。
- 真实设备 2 小时道路、功耗、温升和厂商后台策略长测本次未执行；模拟器数据不能写成真机结论。
- 当前不支持开机自动继续记录；API 27 的已验证边界是活动数据保留、用户打开应用后恢复。
- AGP 9 继续延后，后续作为独立构建链迁移处理。

## 发布资产

- `CarGPS-Mobile-v0.3.0.apk`：正式签名 APK。
- `SHA256SUMS`：只包含该 APK 的 SHA-256 校验文件。
- 公开 Release：https://github.com/lonnnnnng/cargps-mobile/releases/tag/v0.3.0
- APK SHA-256：`0f82aec8ebf7414146a53d958c9dc2feb0006cc849772d5535a5aaee33058c09`；大小 `1,117,952` 字节。
- 签名：APK Signature Scheme v2 通过；证书 SHA-256 为 `7807a35ea864ad038b6f3851b79333e8aedd90bb7f9521fd5ffb0d7c0375d521`。
- Pixel_9 / API 35：正式包冷启动成功，权限流程、`location` 前台 Service、通知结束入口、模拟位置 34 米记录和结束归档均通过；初始、记录中与结束后的 UI 树滚动节点均为 0，最终 Service、通知、定位线程和 crash 记录均为 0。
