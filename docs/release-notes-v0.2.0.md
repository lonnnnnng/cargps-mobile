# CarGPS 手机版 v0.2.0

这是手机版拆分为独立项目后的首个架构与性能升级版本，应用包名保持 `com.cargps.mobile`，版本号升级为 `0.2.0 (3)`，最低系统仍为 Android 8.1 / API 27。

## 依赖与构建

- 升级至 AGP `8.13.2`、Gradle `8.13`、Kotlin/Compose Compiler Plugin `2.3.21`。
- `compileSdk`、`targetSdk` 升级至 36，`minSdk` 保持 27。
- Compose BOM 升级至 `2026.06.00`，并统一使用 Version Catalog 管理版本。
- Release 启用 R8、资源压缩和 Baseline Profile。

## 架构与性能

- 新增 O(1) 增量行程累计器，ViewModel 不再持有完整轨迹点列表；十万点累计由 JVM 单测覆盖。
- 定位、NMEA 和 GNSS 回调迁移至专用线程，NMEA 按 500ms 窗口聚合后更新界面。
- 轨迹点按最多 16 点或 1 秒合并为 SQLite 事务，查询、暂停、结束和关闭前冲刷尾批次。
- SQLite schema 升级至 v3，增加历史结束时间索引，并覆盖 v1 到 v3 的无损迁移。
- 存储读写异常反馈到仪表状态；批量事务失败时保留未落库轨迹点供后续重试。
- Activity 时钟更新绑定 `STARTED` 生命周期，停止定位后丢弃排队的旧回调。

## Pixel_9 验证

- 设备：`Pixel_9` AVD，Android 15 / API 35，1080x2424。
- SQLite instrumentation：4/4 通过。
- Compose instrumentation：1/1 通过；首屏无滚动节点，日期时间、中文定位指标、历史区和操作按钮完整可见。
- 无预编译冷启动 TTID 共 5 次：最小 229.9ms、中位 241.9ms、最大 333.0ms。
- Macrobenchmark 显式允许模拟器，仅作为同一 `Pixel_9` AVD 的相对性能基线，不代表真机绝对性能。

## 已知边界

- 行程记录仍随 Activity 前台生命周期启停，尚未引入前台服务和后台持续定位。
- 数据库继续使用轻量 `SQLiteOpenHelper`，本版本未迁移 Room。
- 本版本不升级 AGP 9，不改变 `minSdk = 27`。
- 未覆盖真实道路 GNSS/NMEA、长时真机功耗和所有权限异常分支；未在 Redmi 真机执行安装或测试。

## 安装包

- `CarGPS-Mobile-v0.2.0.apk`：正式签名手机版 APK。
- `SHA256SUMS`：发布 APK 的 SHA-256 校验文件。
- 公开 Release：https://github.com/lonnnnnng/cargps-mobile/releases/tag/v0.2.0
- APK SHA-256：`8fc1238c1fdc45db0e49d3d78243abdfe834fe15e87008e53004ae3eea366bc2`。
- 远端资产已重新下载并通过版本、v2 签名、证书、16KB 对齐和摘要复核。

后续不在本版本内补做的运行时迁移及验收标准见 [剩余高风险迁移项](./migration-risks.md)。

## 发布后开发状态

以上“已知边界”描述的是 `v0.2.0` 正式包，不代表当前未发布开发线。`v0.2.0` 之后已经完成并提交 M1-M6，包括行程确认状态机、定位前台服务、最后确认检查点与进程恢复、Room schema v4、权限状态机和单一事件队列。M7 Baseline/Startup Profile 曾在 Pixel_9 重新生成并验证；本轮背压改动触及启动与运行时热路径，最终候选前仍需重采集。API 27/29/31/33 聚焦回归也已完成。

API 27/API 29 已进一步使用公开 `v0.2.0` 正式 APK 创建 SQLite v3 活动行程，再覆盖当前同证书 Release；两端均无损迁移到 Room v4，并恢复“记录中 / 已恢复”、前台服务和单定位线程。API 27/29/31/33 的完整位置权限矩阵已经闭环，API 33 通知拒绝矩阵也已完成；API 31 还覆盖活动行程撤权及 Home/锁屏前台服务短路径。Pixel_9 / API 35、Android 10 / API 29、Android 8.1 / API 27 已分别完成 41 个样本、1831/1816/1816 秒回归；三轮均覆盖 Home、系统设置、锁屏和解锁返回，结束后历史增加且 Service、通知、定位线程和 GPS 注册正常清理。API 27 另完成整机重启边界验证：活动行程保留，但系统不自动拉起 Service，用户打开应用后显示“已恢复”并继续记录。

当前未发布开发线又完成 16 点有界存储背压：磁盘持续不可写时第 17 个点同步拒绝，前台服务停止定位并显示“等待存储恢复”，同时保留活动行程和结束入口；尾批确认后自动清除背压并恢复定位。普通持久化失败也已并入同一输入阻断策略，错误未被新检查点确认前不再向行程队列投递定位点。Runtime 会在失败/背压/队列拒绝后断开上一定位样本并重置速度平滑器，JVM 测试已证明恢复首点不跨故障窗口补算距离、速度或确认点序列。Service 内新增 `LocationEngineSessionController`，将 Activity 重绑、`START_STICKY` 恢复、权限/Provider 变化、普通存储失败和背压统一为幂等启停动作；`LocationEngine.start()` 失败时回滚部分注册且不缓存错误启动状态。`LocationEngineSessionControllerTest` 4/4、`LocationEnginePolicyTest` 7/7 仅是 JVM seam，尚不等价于完整 Service 故障注入。Room/SQLite 存储类新增真实只读连接故障测试，在 Pixel_9/API 35 与 Android 8.1/API 27 各 13/13 通过；`RoomRuntimeBackpressureInstrumentedTest` 又把连接级故障推进到 Runtime，两个设备各 1/1，完整 `gps-core` instrumentation 各 14/14，确认批量事务失败不会清除活动行程、前 16 点保留、第 17 点拒绝并可在恢复后重新确认。上述证据仍不等价于物理 `ENOSPC`，也没有覆盖完整 Service 背压通知/GPS 停止恢复。当前已通过 `gps-core` 53/53、手机版 31/31 JVM、完整构建关卡，以及两档设备手机版 6/6 instrumentation。下一版本仍需完成物理低存储与尾批损失量化、Service 全路径异常竞态、最终候选覆盖升级、Profile 重采集和真实设备长测。当前开发构建仍沿用 `0.2.0 (3)`；“开机自动恢复”也不属于当前已交付能力。最新状态以 [剩余高风险迁移项](./migration-risks.md) 为准。
