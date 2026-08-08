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
- 当前开发线：`v0.2.0` 之后的 M1-M7；M1-M7 已提交并完成 Pixel_9 核心验证，API 27/29/31/33 聚焦回归和跨版本位置权限矩阵均已通过，Pixel_9 / API 35、Android 10 / API 29 与 Android 8.1 / API 27 的 30 分钟后台回归也已通过；API 27 整机重启已验证为“活动数据保留、用户打开应用后恢复”。本轮存储确认、任务移除与 Start 竞态 seam 加固后，本地完整构建和 Pixel_9 的 `gps-core` 12/12、手机版 6/6 instrumentation 已复验。
- API 27/API 29 已完成公开正式 `v0.2.0` 到当前同签名 Release 的覆盖升级，活动行程从 SQLite v3 无损迁移到 Room v4。
- 当前开发构建仍沿用 `0.2.0 (3)`；下一版本号、tag 和 Release 尚未确定，也不能把当前验证表述为新版本已经发布。

## 验证设备

- AVD：`Pixel_9`；serial 由模拟器启动顺序动态分配，执行设备命令前必须用 `ro.boot.qemu.avd_name` 复核映射，不能只记住 `emulator-5554`。
- 方向：竖屏
- 应用包名：`com.cargps.mobile`
- 日常安装、UI 和性能验证以 `Pixel_9` 为准；跨版本兼容性另使用 `CASKA_1024x600 / API 27`、`CarGPS_Pixel_9_API29`、`CarGPS_Pixel_9_API31` 和 `CarGPS_Pixel_9_API33`，不把这些 AVD 当作发布视觉基线。
- 所有设备命令显式指定 serial；不操作已连接的 Redmi 真机。

## 主要能力

- 显示瞬时速度、行程平均速度、移动平均速度和最高速度。
- 显示时间、经纬度、海拔、方向、定位精度、卫星数量和定位更新时间。
- 支持开始、暂停、继续、结束行程，并统计里程、总时长、移动时长和停车时长。
- 使用系统 `LocationManager`、GNSS 和 NMEA，不依赖 Google Play Services。
- 使用 Room 2.8.4 管理本地 SQLite schema v4，保存活动行程、暂停状态、历史统计和轨迹点。
- 使用 O(1) 增量行程累计器，长行程不在仪表运行时中保留完整轨迹列表。
- 定位、NMEA 与卫星回调在专用线程处理，NMEA 以 500ms 窗口聚合后更新界面。
- 轨迹点按最多 16 点或 1 秒批量事务落库，并把存储异常反馈到仪表状态。
- 行程运行时通过唯一事件队列固定先恢复，再按入队顺序处理开始、定位点、暂停、继续、结束、时钟和检查点；行程协调器只在存储确认后切换元数据模式，轨迹点先更新实时统计、批量落库后再推进确认检查点。
- 活动行程由 `location` 类型前台服务持有定位引擎；Activity 只绑定同一运行时并观察状态，切到后台或锁屏后通过常驻通知继续记录。
- 通知提供返回应用和“结束行程”操作，模式、每 10 米里程或最多每 5 秒刷新一次。
- 每次轨迹批次成功落库后发布并保留最近的已确认检查点，记录行程开始时间、确认点数、最后 sequence 和时间；任务被移除时由 Service 协程等待尾批次确认，但系统仍可能在等待完成前回收进程。
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
- M2 已在 API 27/API 29/API 31 完成开始、Home、单定位线程、结束和资源清理短路径；API 31 还通过了锁屏保持前台记录。Pixel_9 / API 35、Android 10 / API 29、Android 8.1 / API 27 分别完成 41 个样本、1831/1816/1816 秒回归；三轮均覆盖 Home、系统设置、锁屏和解锁返回，结束后资源完整清理。API 27 已补充整机重启边界验证：不自动拉起 Service，打开应用后可恢复；低存储、尾批损失和真实道路长测仍是发布前风险门禁。
- M3 已在 Pixel_9、API 27 和 API 29 通过普通进程 `SIGKILL` 恢复验证；API 27 整机重启后活动行程数据保留但不会自动继续，用户打开应用后显示“已恢复”并重新建立服务。恢复上限是最后确认检查点，最多约 1 秒的内存尾批仍不承诺零丢点，`force-stop` 也不会被描述为普通系统恢复。
- M4 的 Room v1-v4、事务契约、损坏护栏和失败回滚已在 API 27/29/33 各通过 12/12 instrumentation；API 27/API 29 上公开正式 `v0.2.0` 覆盖当前同证书 Release 后，活动状态、开始时间、点数、距离和点序列均保持不变。
- M5 策略实现、11 个 JVM 场景和 Pixel_9 完整权限矩阵已通过；API 27/29/31/33 已完成精确/近似适用分支、首次/永久拒绝、应用设置返回和系统定位关闭/恢复，API 33 通知首次/永久拒绝也已实机化验证。权限状态机不再是当前发布阻断项。
- M6 已提交为 `19fa99c`：单一 `Channel.UNLIMITED` 事件队列固定先恢复，Service 通过统一定位策略区分可见预览、Start 等待和已确认活动行程；当前开发线又补充了点写失败统一失败流、确认边界重绑 replay、任务移除等待和 Start 状态清理竞态。本地 seam 加固后 `gps-core` 48/48、手机版 25/25 JVM，AndroidTest 编译、lint、lintVital、Debug/Release、R8、资源压缩和 benchmark 构建完整通过，Pixel_9 复验 `gps-core` 12/12、手机版 6/6；真实 Service 全路径异常竞态仍需设备集成验证。
- M7 已在 Pixel_9 重新生成 50,591 行 Baseline Profile 和 49,417 行 Startup Profile，旧 `DashboardViewModel` 类名命中为 0；Release APK 内含新的 `baseline.prof` 与 `baseline.profm`。两轮冷启动对照中 Profile 中位数分别比无预编译快约 17.4% 和 18.2%。API 27/29/35 的 30 分钟回归和 API 27 整机重启边界已通过，下一步转向低存储、尾批损失量化、最终候选和真实设备 2 小时长测；AGP 9 保持延后。若未来要支持开机自动恢复，需另立权限与系统行为迁移，不把它混入当前发布候选。
- 正式候选生成后必须先提升 `versionCode`/`versionName`，再用最终签名 APK 重跑旧包覆盖升级；当前同版本号构建的验证只证明代码、签名和数据迁移链可行。

## 文档与验证资料

- [领域词汇](./CONTEXT.md)
- [资料索引](./docs/README.md)
- [产品规格](./docs/product-spec.md)
- [Android 技术设计](./docs/technical-design.md)
- [测试与性能基线](./docs/testing.md)
- [剩余高风险迁移项](./docs/migration-risks.md)
- [ADR-0001：采用系统 LocationManager](./docs/adr/0001-use-platform-locationmanager.md)
- [截图、UI 树与发布产物说明](./artifacts/README.md)：`v0.2.0` Pixel_9 验证文件保存在本地 `artifacts/release-v0.2.0/`；API 35、API 29、API 27 的长测摘要保存在 `artifacts/cargps-mobile-api35-30min-summary.md`、`artifacts/cargps-mobile-api29-30min-summary.md`、`artifacts/cargps-mobile-api27-30min-summary.md`，API 27 整机重启边界摘要为 `artifacts/cargps-mobile-api27-reboot-summary.md`，这些产物默认不提交 Git。

## 常用验证命令

```zsh
./gradlew :gps-core:testDebugUnitTest
./gradlew :mobile-app:lintDebug :mobile-app:assembleDebug :mobile-app:assembleRelease
adb -s emulator-5554 shell getprop ro.boot.qemu.avd_name
ANDROID_SERIAL=emulator-5554 ./gradlew :gps-core:connectedDebugAndroidTest :mobile-app:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :mobile-app:generateReleaseBaselineProfile
```
