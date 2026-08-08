# 手机版资料索引

本目录只保存 CarGPS 手机版的产品、技术和发布文档。视觉与功能验收设备为 `Pixel_9` 竖屏模拟器；serial 由启动顺序动态分配，每次命令都先复核 AVD 名称并显式指定，不使用 Redmi 真机。

## 产品与技术

- [产品规格](./product-spec.md)：单屏无滚动信息层级、交互、布局和安装边界。
- [技术设计](./technical-design.md)：定位前台服务、共享运行时、行程确认、持久化和 Pixel_9 测试重点。
- [测试与性能基线](./testing.md)：本地、Pixel_9、Baseline Profile 与 Macrobenchmark 命令及当前基线。
- [剩余高风险迁移项](./migration-risks.md)：M1-M7 落地证据、16 点有界存储背压、API 27/29 正式旧包升级、跨 API 完整权限矩阵、API 27/29/35 30 分钟回归和 API 27 整机重启边界结论，以及真实低存储、尾批、Service 异常竞态、Profile 重采集、最终候选、真机长测、开机自动恢复评审和 AGP 9 的剩余顺序与验收门槛。
- [ADR-0001](./adr/0001-use-platform-locationmanager.md)：拆分基线继续采用系统 `LocationManager`，后续架构升级由手机版独立验证。

## 发布记录

- [v0.1.0](./release-notes-v0.1.0.md)：首个手机版正式版本。
- [v0.1.1](./release-notes-v0.1.1.md)：Pixel_9 单屏布局与中文遥测优化版本。
- [v0.2.0](./release-notes-v0.2.0.md)：手机版依赖、架构、存储和性能体系独立升级版本。

截图、UI Automator XML、APK 和 `SHA256SUMS` 不放在文档目录，统一归档于本地 [artifacts](../artifacts/README.md)，并按仓库规则默认忽略。当前正式包验证文件名为 `release-v0.2.0/Pixel_9-v0.2.0.png` 和 `Pixel_9-v0.2.0.xml`；API 35、API 29、API 27 长测摘要分别为 `cargps-mobile-api35-30min-summary.md`、`cargps-mobile-api29-30min-summary.md`、`cargps-mobile-api27-30min-summary.md`，API 27 整机重启边界摘要为 `cargps-mobile-api27-reboot-summary.md`。
