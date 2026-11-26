# DJI 相册 · Android

把 `jowhok/dji-album`（Electron + React 的 Windows 版）移植成原生 Android 应用：Kotlin + Jetpack Compose + Media3，按触屏习惯重做交互。

**当前版本**：`1.0.0-android` · 安装包 16.9MB · minSdk 26 / targetSdk 35
**下载**：[dji-album.apk](https://github.com/jowhok/dji-album-android/releases/download/v1.0.0-android/dji-album.apk) · [Release 说明](https://github.com/jowhok/dji-album-android/releases/tag/v1.0.0-android)

- 数据源：局域网 NAS（SMB 2/3），沿用桌面版的索引与收藏/回收站语义
- 播放：DJI `.LRF` 低码率代理优先（「流畅」档），可一键切到 `.MP4` 原片（「高清」档）
- 无本地 HTTP 代理：ExoPlayer 通过自定义 `DataSource` 直接从 SMB 随机读，减少一次中转
- 照片与缩略图全部按需降采样（最长边 2560 / 360），避免 8K 原片在手机上 OOM

## 本版要点（1.0.0-android）

- **等大正方形网格**：横竖构图同尺寸裁切，不再是瀑布流；吸顶日期分组
- **一屏三页**：`HorizontalPager` 承载「全部 / 视频 / 照片」，左右滑动整页跟手动效；顶部一行放三个胶囊（标签 + 数量）、排序与功能按钮
- **播放器**：LRF/MP4 双档，SMB 4MB 预读 + 4 句柄并行，实测 26~30MB/s、4K60 drop 0；照片 1:1 拖动、双指捏合中心锚点、下滑退出
- **动画**：进入/返回、底部页切换、设置页滑入、播放器缩放均有过渡
- **图标**：与桌面版一致的深色圆角底 + 白色 dji 字标（自适应图标，5 档密度）
- **缩略图镜像**：见下文，411 张重建从 283 秒降到 11 秒

## 与桌面版的功能对齐

| 能力 | 桌面版 | Android |
|---|---|---|
| NAS 连接配置（host/share/root/user/password） | ✅ | ✅，密码存 Android Keystore（AES/GCM） |
| 按 8 位日期目录扫描、mp4+lrf 合并成一条视频记录 | ✅ | ✅ |
| 日期分区瀑布流、按时间/大小排序、视频/照片筛选 | ✅ | ✅（等大正方形网格 + 吸顶日期分组） |
| 日期浮层筛选（按月份分组、带数量） | ✅ | ✅（底部弹层） |
| `.LRF` / `.MP4` 双档播放 | ✅ | ✅ ExoPlayer + 自建 SMB DataSource |
| 照片缩放 / 拖动 | 滚轮 + 左键拖动 | 双指捏合 + 平移 + 双击复位 |
| 收藏 / 收藏夹 | ✅ | ✅ |
| 回收站（软删除） / 恢复 / 彻底删除（含同名 LRF） | ✅ | ✅，删除前展示文件与体积预览 |
| 元数据（时长/分辨率/码率/大小/LRF） | ffmpeg | MediaMetadataRetriever |
| 缩略图生成 + 512MB LRU 缓存 + 清理 | ✅ | ✅，另可把 40KB 小图镜像回 NAS，重建 411 张从 283s 降到 11s |
| 下载原片 | 另存为 | 写入系统媒体库（`Pictures|Movies/DJI Album`） |
| 深/浅/系统主题 | ✅ | ✅ |
| 全屏 | F11 | 播放页沉浸式全屏 |

## 触屏优化要点

1. **顶部一行**：`全部 / 视频 / 照片` 三个小胶囊（34dp 高、标签与数量同排）+ 排序胶囊 + 日历/多选/刷新/设置四个图标按钮，不再占一整行标题。
2. **底部三个入口**：时光轴 / 收藏 / 回收站，设置移到右上角，单手可达；文件操作在播放器/长按弹层里，不依赖右键。
2. **长按 = 操作入口**：弹出底部动作面板（收藏、元数据、下载、删除、多选），长按不再模拟右键。
3. **多选模式**：顶栏「多选」或长按后进入，选中态用勾选圈 + 主色淡罩；底部悬浮操作条（收藏/取消收藏/全选/移入回收站）。
4. **下拉刷新**触发重新扫描；扫描/缩略图/元数据进度用细进度条贴在顶部。
5. **播放器分页手势**：左右滑动切换上一张/下一张；照片放大后自动禁用翻页，避免捏合与翻页冲突。
6. **视频控制条自动隐藏**：点击画面切换显示，播放中 3 秒后淡出；进度条为 48dp 触控高度。
7. 网格为等大正方形格子（横竖片居中裁切，视觉一致）、操作按钮均 ≥48dp；适配刘海屏与手势导航 insets。

## 缩略图镜像（可选，默认开）

Osmo Pocket 的 8K 照片单张 12~22MB，且没有内嵌 EXIF 缩略图，所以「读小图」这条路不存在——只能整张下载才能出预览。299 张照片 + 112 个视频共约 4.7GB，实测单次全量重建 283 秒。

为此增加一层 NAS 侧镜像：生成好的小图（约 40KB）顺手写回 `<root>/.dji-album/thumbs/<指纹>.jpg`，指纹为 `sha1(相对路径 + 文件大小 + 拍摄时间)`。

- 启动时只列一次该目录（实测 64ms），命中就只下几十 KB，不再碰原片；`readMB=0` 即代表零原片流量
- 原片被替换/重拍 → 指纹变化，自动重新生成；原片删除 → 本地缓存与 NAS 孤儿由对账差集清理
- 首次探测目录可写性，只读共享自动降级为纯本地缓存，设置页显示当前状态
- 实测：清空本地缓存后重建 411 张 11 秒完成（此前 283 秒）

## 工程结构

```
app/src/main/java/com/wei/djialbum/
├── DjiAlbumApp.kt            # Application 容器（Prefs / DB / ThumbStore / Repository）
├── MainActivity.kt           # edge-to-edge + Compose 入口
├── core/                     # 格式化、Keystore 加密
├── data/
│   ├── Prefs.kt              # 配置（密码 AES/GCM）
│   ├── AlbumDb.kt            # SQLite：files / favorites / trash（沿用桌面 schema）
│   ├── SmbFs.kt              # smbj 连接复用、列目录、随机读、删除
│   ├── ThumbStore.kt         # 缩略图缓存（sha1 命名，512MB LRU）
│   └── MediaRepository.kt    # 扫描/缩略图/元数据/回收站/下载编排
├── player/SmbDataSource.kt   # Media3 DataSource：直接读 SMB，带 Range 语义
├── model/Model.kt
└── ui/
    ├── AlbumViewModel.kt
    ├── AppRoot.kt            # 底部导航 + 播放器覆盖层 + Snackbar
    ├── setup/                # 首次连接 NAS
    ├── timeline/             # 等大网格 + 缩略图卡片
    ├── player/               # 分页播放器 + 手势缩放
    ├── trash/                # 回收站 + 彻底删除二次确认
    ├── settings/             # NAS/缓存/主题/触屏说明
    └── components/           # 底部动作面板、元数据面板、日期筛选
```

## 构建

环境：JDK 17、Android SDK Platform 35、Build-Tools 35.0.0。

```bash
# local.properties
sdk.dir=/path/to/android-sdk

./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/dji-album.apk`（debug 签名，可直接侧载；上架前请替换为自己的签名）。

## 配置与隐私

本仓库与安装包内**不内置任何真实 NAS 地址 / 共享名 / 账号名 / 密码**：`NasConfig` 默认值全为空，首次启动在「连接 NAS」页填写后保存到应用私有 `SharedPreferences`，密码经 Android Keystore（AES/GCM）加密后落盘，不存明文。输入框占位符只给通用示例（`192.168.1.10` / `Photos` / `DCIM/100MEDIA`）。上游桌面版（`jowhok/dji-album`）已做同样的隐私纠正，两边规则一致。

## 首次使用

1. 打开应用 → 填写 NAS 主机、共享名、照片根目录（相对共享，可留空）、用户名、密码。
2. 点「保存并扫描」，会列出根目录下所有 8 位日期目录（如 `20261124`）。
3. 进入时光轴，缩略图会在后台自动补齐；播放视频默认走 `.LRF`。

> 需要 NAS 开放 SMB（445 端口）且手机在同一局域网。DJI Pocket 素材目录约定与桌面版一致：`<root>/<YYYYMMDD>/*.mp4 + *.LRF + *.JPG`。

## 已知限制

- HEVC 4K 是否可解取决于设备硬解能力；失败时切「流畅 LRF」即可。
- 元数据中的帧率无法通过系统 API 获取，故未展示（桌面版用 ffmpeg 探测）。
- 下载走系统媒体库，Android 10 以下回退到 `Movies|Pictures/DJI Album` 目录（需存储权限）。
- 桌面版的全局快捷键、窗口模式在手机上无对应概念，已替换为手势。

## 许可与商标

个人非商业用途。DJI 为深圳市大疆创新科技有限公司商标，本项目与其无隶属关系。

