# 手机版资料索引

本目录只保存 CarGPS 手机版的产品、技术和发布文档。视觉与功能验收设备为 `Pixel_9` 竖屏模拟器，不使用 Redmi 真机。

## 产品与技术

- [产品规格](./product-spec.md)：单屏无滚动信息层级、交互、布局和安装边界。
- [技术设计](./technical-design.md)：定位数据流、独立模块 seam、持久化和 Pixel_9 测试重点。
- [测试与性能基线](./testing.md)：本地、Pixel_9、Baseline Profile 与 Macrobenchmark 命令及当前基线。
- [ADR-0001](./adr/0001-use-platform-locationmanager.md)：拆分基线继续采用系统 `LocationManager`，后续架构升级由手机版独立验证。

## 发布记录

- [v0.1.0](./release-notes-v0.1.0.md)：首个手机版正式版本。
- [v0.1.1](./release-notes-v0.1.1.md)：Pixel_9 单屏布局与中文遥测优化版本。
- [v0.2.0](./release-notes-v0.2.0.md)：手机版依赖、架构、存储和性能体系独立升级版本。

截图、UI Automator XML、APK 和 `SHA256SUMS` 不放在文档目录，统一归档于 [artifacts](../artifacts/README.md)。当前升级验证截图为 [cargps-mobile-upgrade-pixel9.png](../artifacts/cargps-mobile-upgrade-pixel9.png)。
