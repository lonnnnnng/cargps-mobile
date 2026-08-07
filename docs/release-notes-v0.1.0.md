# CarGPS v0.1.0

此版本最初在项目拆分前以双端形式发布。本文仅保留当前手机版项目对应的发布记录。

## 手机版

- 针对 `Pixel_9` 验证的竖屏单列仪表和固定底部操作栏。
- 支持定位详情折叠、最近 3 条本地行程摘要和日夜主题。
- 使用独立包名 `com.cargps.mobile`。

## 核心能力

- 最低支持 Android 8.1 / API 27，不依赖 Google Play Services。
- 使用系统 `LocationManager`、GNSS 和 NMEA 数据完成定位与诊断。
- SQLite 本地保存活动行程、暂停状态、历史统计和轨迹点。
- 应用进程被终止后可以恢复活动行程，并断开恢复前后的定位点，避免补算跨进程位移。
- SQLite 写入使用后台单线程有序队列，避免阻塞 GPS 主线程回调。

## 安装包

- `CarGPS-Mobile-v0.1.0.apk`：手机版，包名 `com.cargps.mobile`。
