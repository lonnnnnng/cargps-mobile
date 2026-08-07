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

## 验证设备

- AVD：`Pixel_9`
- 方向：竖屏
- 应用包名：`com.cargps.mobile`
- 安装、UI 和功能验证只使用 `Pixel_9`，不操作已连接的 Redmi 真机。

## 主要能力

- 显示瞬时速度、行程平均速度、移动平均速度和最高速度。
- 显示时间、经纬度、海拔、方向、定位精度、卫星数量和定位更新时间。
- 支持开始、暂停、继续、结束行程，并统计里程、总时长、移动时长和停车时长。
- 使用系统 `LocationManager`、GNSS 和 NMEA，不依赖 Google Play Services。
- 使用本地 SQLite 保存活动行程、暂停状态、历史统计和轨迹点。
- 使用 O(1) 增量行程累计器，长行程不在仪表 ViewModel 中保留完整轨迹列表。
- 定位、NMEA 与卫星回调在专用线程处理，NMEA 以 500ms 窗口聚合后更新界面。
- 轨迹点按最多 16 点或 1 秒批量事务落库，并把存储异常反馈到仪表状态。
- 提供无需滚动的单屏紧凑仪表、底部行程控制和常显定位遥测。
- Release 启用 R8、资源压缩和 Baseline Profile。

## 项目边界

- `gps-core` 已在拆分时复制到本项目，两个项目后续不会自动同步核心代码。
- 共用缺陷修复需要按需移植并在各自设备上独立验证。
- 本项目不包含车机 UI、车机产品规格或车机发布资产。
- 本地签名文件没有从车机项目复制；Release 构建仍通过 `ANDROID_SIGNING_*` 环境变量注入签名。

## 文档与验证资料

- [领域词汇](./CONTEXT.md)
- [资料索引](./docs/README.md)
- [产品规格](./docs/product-spec.md)
- [Android 技术设计](./docs/technical-design.md)
- [测试与性能基线](./docs/testing.md)
- [ADR-0001：采用系统 LocationManager](./docs/adr/0001-use-platform-locationmanager.md)
- [当前 Pixel_9 升级验证截图](./artifacts/cargps-mobile-upgrade-pixel9.png)
- [截图、UI 树与发布产物说明](./artifacts/README.md)

## 常用验证命令

```zsh
./gradlew :gps-core:testDebugUnitTest
./gradlew :mobile-app:lintDebug :mobile-app:assembleDebug :mobile-app:assembleRelease
ANDROID_SERIAL=emulator-5554 ./gradlew :gps-core:connectedDebugAndroidTest :mobile-app:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :mobile-app:generateReleaseBaselineProfile
```
