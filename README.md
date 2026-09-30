# 新增：php:// 远程 PHP 脚本源支持

## 功能说明

直播源地址以 `php://` 开头时，下载远程 PHP 脚本到本地执行，通过内置 PHP 运行时（embed SAPI）返回播放地址。

地址格式：

```
php://https://example.com/script.php?id=cctv5
```

工作流程：

1. 下载远程 PHP 脚本文本（缓存 1 小时，过期自动重新下载）
2. 写入内部存储 web 根目录 `filesDir/php/`
3. 启动本地 HTTP 服务器（仅监听 `127.0.0.1`，随机端口），返回 `http://127.0.0.1:端口/script_xxx.php?id=cctv5` 给播放器
4. 播放器每次请求该本地地址，服务器执行一次 PHP 脚本，返回 m3u8 / 重定向地址

## 设计要点

- **内部存储**：脚本和 php.ini 都放在 `filesDir/php/`，不依赖任何存储权限，避免未授权外部存储时静默失败
- **仅监听 127.0.0.1**：不对外暴露，安全；ExoPlayer 直接访问本机回环地址
- **单线程串行执行**：PHP embed 运行时是进程级全局单例且非线程安全，并发调用会 SIGSEGV。PhpServer 用 `Executors.newSingleThreadExecutor()`，PhpBridge 内部有全局 `synchronized(LOCK)`，双保险
- **目录可写校验**：`ensureWritable()` 在初始化时探测目录真实可写性，失败直接抛异常而非等到写文件才报错
- **错误透出**：`isReady()` / `getInitError()` 让 Extractor 能拿到初始化失败原因，避免静默切线路

## 新增模块

### `php` 模块（`com.fongmi.php`）

| 文件 | 说明 |
|---|---|
| `php/build.gradle` | Android Library 模块，依赖 `:catvod`，ABI 仅 arm64-v8a / armeabi-v7a，c++_static |
| `php/src/main/cpp/CMakeLists.txt` | NDK 构建配置，链接 libphp / libcurl / libonig / libsqlite3 |
| `php/src/main/cpp/native-lib.cpp` | JNI 桥接，PHP embed SAPI 初始化与 `runPhpFile()` |
| `php/src/main/cpp/includes/` | PHP 头文件（Zend / TSRM / sapi_embed / ext） |
| `php/src/main/jniLibs/arm64-v8a/` | libphp.so、libcurl.so、libonig.so、libsqlite3.so |
| `php/src/main/jniLibs/armeabi-v7a/` | 同上 |
| `php/src/main/assets/php/php.ini` | PHP 配置（upload_tmp_dir / session.save_path / error_log 运行时改写） |
| `php/src/main/java/com/fongmi/php/PhpBridge.java` | native 方法 `runPhpFile()` + 全局锁 `runPhpFileExclusive()` |
| `php/src/main/java/com/fongmi/php/PhpEnv.java` | PHP 环境管理：初始化、web 根目录、php.ini 复制、目录可写校验、错误状态 |
| `php/src/main/java/com/fongmi/php/PhpServer.java` | 本地 HTTP 服务器：127.0.0.1 绑定、单线程 worker、请求解析、PHP 串行执行、响应返回 |

### 目录结构

- web 根目录：`/data/data/com.fongmi.android.tv/files/php/`（内部存储，无需权限）
- 脚本文件名：`script_` + URL.hashCode() 十六进制 + `.php`
- 临时目录：`files/php/tmp/`（upload_tmp_dir / session.save_path / error_log）
- php.ini：`files/php/php.ini`

### app 侧改动

| 文件 | 改动 |
|---|---|
| `settings.gradle` | 新增 `include ':php'` |
| `app/build.gradle` | 新增 `implementation project(':php')` |
| `app/src/main/java/com/fongmi/android/tv/player/extractor/Php.java` | Extractor 实现：下载脚本 → 注册到本地服务器 → 返回 127.0.0.1 URL |
| `app/src/main/java/com/fongmi/android/tv/player/extractor/Source.java` | 构造函数新增 `new Php()` 注册 |

## 权限

无需任何存储权限（全部在内部沙箱）。仅需普通的 `INTERNET` 权限（下载远程脚本）。

## 验证

直播源列表中添加频道，地址填 `php://https://example.com/nujiang.php?id=cctv5`，切换到该频道后 ExoPlayer 应正常播放。logcat 过滤 `PhpServer|PhpEnv|Php` 可看到请求和 PHP 执行日志。

---

# 修改：修复播放中系统分辨率切换导致播放器重建

## 问题现象

直播/点播播放一段时间后，画面突然重新加载（mediaPos 归零），日志显示：

```
initScaledDensity = 1.3312501 on ConfigurationChanged
PLAYLIST_CHANGED, pos=44060 -> pos=0
MediaController Release
```

## 根因

Rockchip TV 盒子播放过程中系统自动切换 HDMI 输出模式，触发 ConfigurationChanged（density 变化）。原 manifest 中 configChanges 未包含 density 等配置类型，导致 Activity 被销毁重建，SurfaceView surface 销毁，ExoPlayer 被迫 Release 并重新加载播放列表。

## 改动

`app/src/leanback/AndroidManifest.xml` 中 LiveActivity、VideoActivity 等播放页面的 `configChanges` 补全为：

```
mcc|mnc|locale|layoutDirection|screenSize|smallestScreenSize|screenLayout|uiMode|orientation|keyboard|keyboardHidden|navigation|density|fontScale
```

任何系统配置变化都由 Activity 自行处理，不重建，播放器不中断。

## 验证

播放直播约 1 小时以上，不应再出现画面重新加载。

---

# 修改：ku9 动态 m3u8 过期保护

## 问题现象

ku9 解析的直播源播放一段时间后，画面卡在"正在加载"但声音正常，不自动恢复。

## 根因

ku9 脚本返回 `#EXTM3U` 时启动本地 `Ku9PlaylistServer`，ExoPlayer 播的是 `http://127.0.0.1:port/live.m3u8`，分片列表由脚本每 2-5 秒刷新。当分片 URL 的 token 过期（约 58 分钟）后，脚本虽然每 2-5 秒在执行，但返回的 m3u8 内容不再变化（分片 URL 停滞），ExoPlayer 一直请求过期分片进入 BUFFERING，音频已缓冲够了继续播放，无超时重连。

## 改动

只改 `app/src/main/java/com/fongmi/android/tv/player/ku9/Ku9PlaylistServer.java`：

- `update()` 时计算 m3u8 内容 MD5，与上次比较
- 正常直播流每次刷新 m3u8 都有新分片出现，内容必然变化
- 如果连续 **6 次**（约 15-30 秒）update 内容完全相同，判定分片列表停滞，ExoPlayer 请求 m3u8 时返回 **HTTP 503**
- ExoPlayer 报错后走 `LivePlaybackController.playbackError` -> 自动切下一条线路重连

不动 LiveActivity、WebViewPlayer 等原有代码。

## 验证

ku9 直播播放约 1 小时分片过期后，应在 30 秒内报错并自动切源，不再卡在加载画面。

---

# 修改：修复 LiveActivity 进程重建崩溃（ClassCastException）

## 问题现象

直播播放一段时间后，切后台被系统回收进程，再回到 LiveActivity 时崩溃：

```
java.lang.ClassCastException: android.view.AbsSavedState$1 cannot be cast
    to android.widget.HorizontalScrollView$SavedState
  at HorizontalScrollView.onRestoreInstanceState(HorizontalScrollView.java:1827)
  ...
  at ActivityThread.handleStartActivity
```

## 根因

View 状态保存/恢复按 `id` 匹配。`R.id.action` 在 leanback 版直播页布局中被同时用在两个**父子关系**的 View 上：

1. `view_control_live.xml` 通过 `<include android:id="@+id/action" layout="@layout/view_control_live_action"/>`，把 include 的 id 覆盖到被 include 布局根元素 `HorizontalScrollView` 上；
2. `view_control_live_action.xml` 内部那个播放/暂停按钮 `MaterialTextView` 也声明了 `android:id="@+id/action"`。

保存状态时，ViewGroup 先存父 `HorizontalScrollView` 的 `SavedState`，再遍历子 `MaterialTextView`，子 View 把自己的空状态 `AbsSavedState$1` 以同一个 id 覆盖写入；恢复时 `HorizontalScrollView.onRestoreInstanceState` 拿到的是子 View 的空状态，强转 `HorizontalScrollView$SavedState` 失败，崩溃。

类似地，`view_widget_*.xml` 里中央播放按钮 `ImageView` 也叫 `@+id/action`，与控制栏 include 的 id 跨分支重复，属同类隐患，一并清理。

## 改动清单

### 1. 消除父子 View id 冲突（直接修复崩溃）

| 文件 | 改动 |
|---|---|
| `app/src/leanback/res/layout/view_control_live_action.xml` | 内部播放/暂停按钮 id `@+id/action` -> `@+id/toggle` |
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java` | 4 处 `mBinding.control.action.action` -> `mBinding.control.action.toggle` |

### 2. 清理 widget 中央图标与控制栏跨分支同 id 隐患

| 文件 | 改动 |
|---|---|
| `app/src/leanback/res/layout/view_widget_live.xml` | ImageView id `action` -> `centerIcon` |
| `app/src/leanback/res/layout/view_widget_vod.xml` | ImageView id `action` -> `centerIcon` |
| `app/src/leanback/res/layout/view_widget_cast.xml` | ImageView id `action` -> `centerIcon` |
| `app/src/mobile/res/layout/view_widget_live.xml` | ImageView id `action` -> `centerIcon` |
| `app/src/mobile/res/layout/view_widget_vod.xml` | ImageView id `action` -> `centerIcon` |
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java` | 2 处 `mBinding.widget.action` -> `mBinding.widget.centerIcon` |
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/CastActivity.java` | 2 处 `mBinding.widget.action` -> `mBinding.widget.centerIcon` |
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java` | 2 处 `mBinding.widget.action` -> `mBinding.widget.centerIcon` |
| `app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java` | 1 处 `mBinding.widget.action` -> `mBinding.widget.centerIcon` |
| `app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java` | 1 处 `mBinding.widget.action` -> `mBinding.widget.centerIcon` |

## 验证方式

重新编译安装后，复现路径：进入直播页播放一段时间 -> 按 Home 切后台 -> 用 `adb shell am kill <包名>` 或等待系统回收进程 -> 重新打开直播页，应不再崩溃。

---

# 開發者文件

基於 [CatVod](https://github.com/CatVodTVOfficial/CatVodTVJarLoader) 的開源 Android 影音應用程式，同時支援 **Android TV 大螢幕**與**手機**兩種使用情境，並且透過外部配置靈活擴展內容。

---

## 目錄

- [專案架構](#專案架構)
- [播放器](#播放器)
- [點播功能](#點播功能)
- [直播功能](#直播功能)
- [爬蟲引擎](#爬蟲引擎)
- [網路功能](#網路功能)
- [DLNA 投放](#dlna-投放)
- [Android Auto](#android-auto)
- [遠端控制](#遠端控制)
- [配置說明](#配置說明)
- [延伸閱讀](#延伸閱讀)

---

## 專案架構

| 項目      | 值                             |
|---------|-------------------------------|
| package | `com.fongmi.android.tv`       |
| minSdk  | 24（Android 7.0 Nougat）        |
| abi     | `arm64-v8a`、`armeabi-v7a`     |
| flavor  | `leanback`（電視版）、`mobile`（手機版） |

```
TV/
├── app/            主應用程式（含兩套 UI Flavor）
├── catvod/         爬蟲抽象層（Spider 介面、OkHttp 網路棧）
├── quickjs/        QuickJS JavaScript 引擎
├── chaquo/         Chaquopy Python 引擎
```

`app/src/main/` 為兩個版本共用的業務邏輯，`app/src/leanback/` 與 `app/src/mobile/` 各自實作對應 UI。

---

## 播放器

- **核心**：ExoPlayer（Media3）+ FFmpeg 軟解，硬解 / 軟解自動降級切換
- **渲染**：SurfaceView / TextureView
- **DRM**：Widevine、PlayReady、ClearKey，支援 `#KODIPROP` 宣告
- **彈幕**：DanmakuFlameMaster，與播放時間軸精確同步，支援遠端推送
- **字幕**：SRT / SSA / ASS 外掛字幕、系統 CaptioningManager、遠端即時注入
- **其他**：倍速、多縮放比例、畫中畫（PiP）、背景音訊、片頭 / 片尾自動跳過

---

## 點播功能

- 多站點分類瀏覽，Filter 篩選（年份 / 地區 / 類型等）
- 多站點**並行搜尋**，關鍵字自動繁轉簡提升相容性
- 播放失敗自動換源：解析器 -> 線路 -> 搜尋其他站 -> 下一站點
- 觀看記錄（保留 60 天）、收藏、無痕模式
- 電視版使用遙控器操作；手機版支援手勢（亮度 / 音量 / 進度）、上下滑切集、螢幕旋轉與鎖定

---

## 直播功能

- 支援 M3U、TXT（`#genre#` 分組）、JSON 三種直播源格式
- **EPG**：XMLTV 格式（支援 `.gz`），每 6 小時自動刷新
- **追看 / 時移**：`append`、`pltv` 等多種類型
- 頻道收藏、隱藏分組密碼保護
- 特殊引擎：TVBus、ForceTech

---

## 爬蟲引擎

支援三種語言撰寫爬蟲：

- Java JAR（DexClassLoader）
- JavaScript（QuickJS）
- Python（Chaquopy）

透過 `api` 欄位指定爬蟲，`ext` 欄位傳入初始化參數。完整 API 規格見 [SPIDER.md](docs/SPIDER.md)。

---

## 網路功能

- **DoH**：DNS over HTTPS，支援 Bootstrap IP
- **代理**：HTTP / HTTPS / SOCKS4 / SOCKS5，依 host 正則規則動態選擇
- **Hosts**：DNS 解析覆蓋，支援萬用字元 `*`
- **CORS 注入**：依 host 規則在回應中注入自訂標頭
- **廣告攔截**：`ads` 黑名單，符合域名直接攔截
- **WebView 嗅探**：Sniffer 以 regex 攔截媒體 URL；支援 UA 偽裝

---

## DLNA 投放

- **DMC（投放端）**：手機版，掃描區域網路 DLNA 設備並投放媒體
- **DMR（被投放端）**：電視版，作為 DLNA Renderer 接收其他設備投放

使用 JUPnP 3.0.4（UPnP），支援 play / pause / stop / seek / next / repeat 控制，可傳遞自訂 HTTP 標頭（User-Agent、Referer 等）至目標串流。

---

## Android Auto

電視版支援 Android Auto，PlaybackService 實作 MediaLibraryService，可在車機上瀏覽播放記錄與直播頻道：

- **點播**：歷史記錄條目可直接續播，恢復上次進度
- **直播**：依分組瀏覽頻道，可直接選台
- **播放控制**：支援車機端 play / pause / prev / next / stop
- **懶加載**：App 退出後 Auto 仍保持連線，配置自動重新載入

---

## 遠端控制

應用啟動後綁定本地 HTTP 伺服器（NanoHTTPD），埠號從 **9978** 起自動偵測至 **9998**，可用於播放控制、推送字幕 / 彈幕、多裝置同步等。完整端點說明見 [LOCAL.md](docs/LOCAL.md)。

---

## 配置說明

Vod 配置為應用主要入口，透過 URL 或本地路徑載入，頂層欄位定義：

- 點播站點（`sites`）、解析規則（`parses`）
- 直播來源（`lives`）
- 網路設定（`doh`、`proxy`、`hosts`、`ads`）

Live 配置可內嵌或獨立存放。完整欄位說明見 [CONFIG.md](docs/CONFIG.md)。

---

## 延伸閱讀

| 文件                          | 說明                   |
|-----------------------------|----------------------|
| [CONFIG.md](docs/CONFIG.md) | Vod / Live 完整配置欄位說明  |
| [SPIDER.md](docs/SPIDER.md) | Spider 所有方法規格與回傳格式   |
| [LOCAL.md](docs/LOCAL.md)   | 本地 HTTP API 所有端點完整說明 |
| [LIVE.md](docs/LIVE.md)     | 直播來源格式完整說明           |