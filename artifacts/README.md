# 手机版验证产物

本目录只存放 `com.cargps.mobile` 在 `Pixel_9` 上产生的本地验证资料。除本说明外，其余文件默认不进入 Git。

## 当前验证

- `cargps-split-mobile.png`：拆分为独立项目后的 Pixel_9 单屏截图。
- `cargps-split-mobile.xml`：同次验证的 UI Automator 树；`scrollable="true"` 节点数量应为 0。

## 历史资料

- `cargps-mobile-*.png`、`cargps-mobile-*.xml`：手机版持久化、历史行程和定位状态验证。
- `mobile-compact-*`、`mobile-rebalanced-*`：单屏密度与区块间距调整过程。
- `mobile-final-*`、`mobile-one-screen-*`：最终单屏布局和行程状态快照。
- `release-v0.1.0/`、`release-v0.1.1/`：手机版 APK、Pixel_9 截图和 `SHA256SUMS`。

发布目录中的校验文件只列 `CarGPS-Mobile-*.apk`。移动或替换 APK 后必须重新运行 SHA-256 校验，不能把旧摘要当作新构建证据。
