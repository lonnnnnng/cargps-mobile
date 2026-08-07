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

以上“已知边界”描述的是 `v0.2.0` 正式包，不代表当前未发布开发线。`v0.2.0` 之后已经完成 M1 行程确认状态机、M2 定位前台服务、M3 最后确认检查点与进程恢复、M4 Room 2.8.4 / schema v4 无损迁移和数据损坏护栏，以及 M5 权限状态机；M1-M5 已提交。M6 单一事件队列和 Service 定位策略已通过本地完整关卡和 Pixel_9 核心短路径。M1-M6 仍待跨 API、长测和新版本发布。最新状态以 [剩余高风险迁移项](./migration-risks.md) 为准。
