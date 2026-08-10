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

以上“已知边界”描述的是 `v0.2.0` 正式包，不代表当前未发布开发线。`v0.2.0` 之后已经完成并提交 M1-M6，包括行程确认状态机、定位前台服务、最后确认检查点与进程恢复、Room schema v4、权限状态机和单一事件队列。M7 已按当前热路径在 Pixel_9/API 35 重新生成 50,670 行 Baseline Profile 与 49,461 行 Startup Profile，并完成两轮冷启动对照；API 27/29/31/33 聚焦回归也已完成。

API 27/API 29 已进一步使用公开 `v0.2.0` 正式 APK 创建 SQLite v3 活动行程，再覆盖当前同证书 Release；两端均无损迁移到 Room v4，并恢复“记录中 / 已恢复”、前台服务和单定位线程。API 27/29/31/33 的完整位置权限矩阵已经闭环，API 33 通知拒绝矩阵也已完成；API 31 还覆盖活动行程撤权及 Home/锁屏前台服务短路径。Pixel_9 / API 35、Android 10 / API 29、Android 8.1 / API 27 已分别完成 41 个样本、1831/1816/1816 秒回归；三轮均覆盖 Home、系统设置、锁屏和解锁返回，结束后历史增加且 Service、通知、定位线程和 GPS 注册正常清理。API 27 另完成整机重启边界验证：活动行程保留，但系统不自动拉起 Service，用户打开应用后显示“已恢复”并继续记录。

后续开发线又完成 16 点有界存储背压：磁盘持续不可写时第 17 个点同步拒绝，前台服务停止定位并显示“等待存储恢复”，同时保留活动行程和结束入口；尾批确认后自动清除背压并恢复定位。普通持久化失败也已并入同一输入阻断策略，错误未被新检查点确认前不再向行程队列投递定位点。Runtime 会在失败/背压/队列拒绝后断开上一定位样本并重置速度平滑器，JVM 测试已证明恢复首点不跨故障窗口补算距离、速度或确认点序列。Service 内新增 `LocationEngineSessionController`，将 Activity 重绑、`START_STICKY` 恢复、权限/Provider 变化、普通存储失败和背压统一为幂等启停动作；`LocationEngine.start()` 失败时回滚部分注册且不缓存错误启动状态。Room/SQLite 存储类新增真实只读连接与受控真实 `SQLiteFullException` 故障测试；Pixel_9/API 35 与 Android 8.1/API 27 的当前存储类各 14/14、Runtime/Room 类各 2/2，`gps-core` instrumentation 合计各 16/16。真实 Service 生命周期 seam 在两档设备各 8/8，已补齐活动行程 Activity 重建后重绑原 Service、actor 第二次异常进入终态且不再循环重建；完整手机版 instrumentation 两端最近均为 14/14。probe-only 真实 Room 阻塞探针还在 Pixel_9/API 35 与 API 27 完成 Checkpoint 提交前整个进程回收验证：两端均以新 PID 恢复 `START_STICKY` 前台 Service，活动行程保持 `RECORDING`，但 16 个未确认点全部丢失、确认点数保持 0。另一条双设备回归在 Activity 前台时终止整个应用进程，验证 Service 先独立恢复，用户返回后 Activity 重绑同一新进程，进程数、ServiceRecord 和定位线程都保持 1。当前 Profile 已命中新 Service/Runtime/队列热路径，旧 ViewModel 与 probe-only 探针类名保持 0；两轮模拟器冷启动中位改善为 17.46% 和 24.37%，但第二轮波动明显且 CPU 未锁定，只能作为同一 AVD 的相对证据。上述结果仍不等价于物理 `ENOSPC`、真实系统 GPS 注册、断电或 `force-stop` 零丢点；这些场景后来被接受为残余风险，不再作为 `v0.3.0` 发布阻断。API 27/API 29 最终版本号覆盖升级与真实设备长测也按未复验边界披露；“开机自动恢复”仍不属于已交付能力。最新状态以 [v0.3.0 发布说明](./release-notes-v0.3.0.md) 和 [剩余高风险迁移项](./migration-risks.md) 为准。
