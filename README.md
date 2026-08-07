# CarGPS 手机版

CarGPS 手机版是独立的竖屏离线 GPS 仪表项目，包名为 `com.cargps.mobile`。它拥有自己的 `gps-core` 副本，不依赖车机项目的目录、Gradle 构建或发布流程。

公开仓库：[lonnnnnng/cargps-mobile](https://github.com/lonnnnnng/cargps-mobile)

车机版仓库：[lonnnnnng/cargps](https://github.com/lonnnnnng/cargps)

## 工程结构

```text
mobile/
├── baselineprofile/ # Pixel_9 Baseline Profile 与冷启动 Macrobenchmark
├── gps-core/      # 手机版独立的定位、NMEA、速度和行程领域逻辑
├── mobile-app/    # 竖屏手机版，包名 com.cargps.mobile
├── docs/
├── gradle/
├── gradlew
└── settings.gradle.kts
```

手机版当前保留 `minSdk = 27`，独立升级到 `compileSdk/targetSdk = 36`、AGP `8.13.2`、Gradle `8.13` 和 Kotlin/Compose Compiler `2.3.21`。车机版仍按 Android 8.1 / API 27 独立维护，不与本项目同步升级。

## 当前版本

- 最新版本：[`v0.2.0`](https://github.com/lonnnnnng/cargps-mobile/releases/tag/v0.2.0)
- Git 提交：`0995eb2`
- 安装包：`CarGPS-Mobile-v0.2.0.apk`，版本 `0.2.0 (3)`
- SHA-256：`8fc1238c1fdc45db0e49d3d78243abdfe834fe15e87008e53004ae3eea366bc2`
- 当前开发线：`v0.2.0` 之后的 M1-M5 开发工作树；M1-M4 已提交，M5 尚未提交，整体尚未打 tag 或发布。

## 验证设备

- AVD：`Pixel_9`，当前 serial 为 `emulator-5554`；执行设备命令前必须用 `ro.boot.qemu.avd_name` 复核映射。
- 方向：竖屏
- 应用包名：`com.cargps.mobile`
- 安装、UI 和功能验证只使用 `Pixel_9`，不操作已连接的 Redmi 真机。

## 主要能力

- 显示瞬时速度、行程平均速度、移动平均速度和最高速度。
- 显示时间、经纬度、海拔、方向、定位精度、卫星数量和定位更新时间。
- 支持开始、暂停、继续、结束行程，并统计里程、总时长、移动时长和停车时长。
- 使用系统 `LocationManager`、GNSS 和 NMEA，不依赖 Google Play Services。
- 使用 Room 2.8.4 管理本地 SQLite schema v4，保存活动行程、暂停状态、历史统计和轨迹点。
- 使用 O(1) 增量行程累计器，长行程不在仪表运行时中保留完整轨迹列表。
- 定位、NMEA 与卫星回调在专用线程处理，NMEA 以 500ms 窗口聚合后更新界面。
- 轨迹点按最多 16 点或 1 秒批量事务落库，并把存储异常反馈到仪表状态。
- 行程协调器串行处理恢复、开始、暂停、继续、结束和定位点；开始、暂停、继续、结束等元数据命令只在存储确认后切换模式，轨迹点则先更新实时统计、批量落库后再推进确认检查点。
- 活动行程由 `location` 类型前台服务持有定位引擎；Activity 只绑定同一运行时并观察状态，切到后台或锁屏后通过常驻通知继续记录。
- 通知提供返回应用和“结束行程”操作，模式、每 10 米里程或最多每 5 秒刷新一次。
- 每次轨迹批次成功落库后发布已确认检查点，记录行程开始时间、确认点数、最后 sequence 和时间；任务被移除时会尽力请求冲刷尾批次，但系统仍可能在异步请求完成前回收进程。
- `START_STICKY` 新进程先显示恢复通知并等待 Room 结果，再恢复活动行程和定位，不能把 Runtime 初始空闲状态误判为没有行程。
- Room 通过显式 `1 -> 2 -> 3 -> 4` 迁移接管旧数据库，不启用 destructive fallback；活动行程 mode 非法时进入存储损坏状态、保留原始数据并禁止开始新行程。
- M5 使用统一的 `TripAccessState` 阻断行程启动，区分精确/近似定位、首次/永久拒绝、系统定位关闭和 Android 13+ 通知拒绝，并为各分支提供权限请求或设置入口。
- 提供无需滚动的单屏紧凑仪表、底部行程控制和常显定位遥测。
- Release 启用 R8、资源压缩和 Baseline Profile。

## 项目边界

- `gps-core` 已在拆分时复制到本项目，两个项目后续不会自动同步核心代码。
- 共用缺陷修复需要按需移植并在各自设备上独立验证。
- 本项目不包含车机 UI、车机产品规格或车机发布资产。
- 本地签名文件没有从车机项目复制；Release 构建仍通过 `ANDROID_SIGNING_*` 环境变量注入签名。
- M2 已在 `Pixel_9` / API 35 完成前台服务短路径验证，但 API 27/API 29、连续锁屏 30 分钟和真实道路长测仍是发布前风险门禁。
- M3 已在 `Pixel_9` 通过应用进程 `SIGKILL` 恢复验证；恢复上限是最后确认检查点，最多约 1 秒的内存尾批仍不承诺零丢点，`force-stop` 也不会被描述为普通系统恢复。
- M4 已完成数据库损坏与无活动行程的显式区分、Room schema v4 和旧 schema 无损迁移；仍需在 API 27/API 29 随整体验收复核。
- M5 策略实现、11 个 JVM 场景、手机版 instrumentation 6/6 和 `Pixel_9` 系统权限矩阵已通过；仅近似授权会先提供一次应用内精确升级，升级拒绝后才转应用设置。API 27/29/31/33 权限差异仍未验收，因此尚未提交或发版。
- 下一版发布前还必须补齐定位点与结束命令的确定顺序，并重新生成不含已删除类名的 Baseline Profile；跨 API 30 分钟回归、真实设备 2 小时长测和 AGP 9 继续分阶段推进。

## 文档与验证资料

- [领域词汇](./CONTEXT.md)
- [资料索引](./docs/README.md)
- [产品规格](./docs/product-spec.md)
- [Android 技术设计](./docs/technical-design.md)
- [测试与性能基线](./docs/testing.md)
- [剩余高风险迁移项](./docs/migration-risks.md)
- [ADR-0001：采用系统 LocationManager](./docs/adr/0001-use-platform-locationmanager.md)
- [截图、UI 树与发布产物说明](./artifacts/README.md)：`v0.2.0` Pixel_9 验证文件保存在本地 `artifacts/release-v0.2.0/`，默认不提交 Git。

## 常用验证命令

```zsh
./gradlew :gps-core:testDebugUnitTest
./gradlew :mobile-app:lintDebug :mobile-app:assembleDebug :mobile-app:assembleRelease
adb -s emulator-5554 shell getprop ro.boot.qemu.avd_name
ANDROID_SERIAL=emulator-5554 ./gradlew :gps-core:connectedDebugAndroidTest :mobile-app:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :mobile-app:generateReleaseBaselineProfile
```
