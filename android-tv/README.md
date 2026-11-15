# DTV Android TV（多平台 · 斗鱼 / 虎牙 / 抖音 / B 站）

本目录是 LIVE-TV 在本 fork 下的 **Android TV 原生客户端**（Compose for TV + Media3），
Kotlin 契约移植桌面端 `DtvApi` / `common` 与 Go `internal/platforms/{douyu,huya,douyin,bilibili}`。
**不**嵌入 Wails/React。

## 范围（已实现）

- Android TV / Leanback launcher、DPAD 友好浏览 → 选房间 → 全屏播放
- **LT-7 多平台**：浏览页平台切换（斗鱼 / 虎牙 / 抖音 / B 站）；关注 / 最近按 `platform+roomId` 持久化
- **M3 首页行**：关注 / 最近观看（本地 SharedPreferences）+ 分区直播行；行内 `focusRestorer` 焦点记忆；overscan safe padding
- 播放页 chrome：OK 显控 / 播放暂停、关注切换、Back 退出；未开播 / 错误可读 + 重试
- Media3：斗鱼 / 虎牙 / 抖音 **FLV**；B 站 **HLS**；测试 HLS
- **LT-5 FLV 续流**：本地 `127.0.0.1` splice 中继（**仅斗鱼**）；虎牙 / 抖音直连 progressive + quiet re-sign
- **LT-6 / LT-9 弹幕**：四平台弹幕会话 + Compose 叠加；chrome **弹幕：开/关**（默认开，可在设置改默认）。
- **LT-8 扫码登录**：斗鱼 / B 站 QR（大屏二维码 + 轮询）；Cookie 存 SharedPreferences；登录后取流走同一 Client 会话（解锁更高清晰度）。虎牙/抖音桌面无账号 QR。
- **LT-10 搜索 / 清晰度**：DPAD 搜索；播放页切换清晰度 / 线路（有变体时）。
- **LT-11 设置与 UI**：专用设置页（账号、弹幕默认、清空关注/最近、关于版本）；10-foot 间距与焦点对比度；播放页文案更清晰。

## 明确延后

局域网同步、桌面端导入、虎牙 FLV 本地中继。

## 平台播放路径

| 平台 | 列表 | 取流 | Media3 |
| --- | --- | --- | --- |
| 斗鱼 | cate/list + directory | getH5PlayV1 → **FLV** | 本地 splice 中继（~300s） |
| 虎牙 | getLiveList（静态分区） | profileRoom + WUP token → **FLV** | 直连 progressive + re-sign |
| 抖音 | partition/detail/room/v2（a_bogus） | reflow/info → 优先 H.264 **FLV**（可 HLS） | 直连；HEVC 原画尽量避开 |
| B 站 | Area/getList + second/getList（w_webid） | getRoomPlayInfo → **HLS only** | `HlsMediaSource` |

斗鱼弹幕：明文 TCP `danmuproxy.douyu.com:8601+` STT（见 LT-6）。

## 续流验证

```bash
cd android-tv
./gradlew :app:testDebugUnitTest --tests com.dtv.androidtv.player.FlvRelayTest
# 可选：真实斗鱼跑过一个 CDN 周期（约 320s）
./gradlew :app:testDebugUnitTest --tests com.dtv.androidtv.player.LiveFlvContinuitySmokeTest \
  -Ddtv.liveFlvSmoke=true -Ddtv.liveFlvSmokeSeconds=320
# 日志：app/build/reports/live-flv-continuity.log
# 可选：虎牙 / 抖音 / B 站列表+取流冒烟
./gradlew :app:testDebugUnitTest --tests com.dtv.androidtv.data.LiveMultiPlatformSmokeTest \
  -Ddtv.liveMultiPlatformSmoke=true
```

## 环境要求

- JDK 17+
- Android SDK（`compileSdk`/`targetSdk` 35，`minSdk` 24）
- 建议：Android TV 模拟器（API 30+）或带 Leanback 的真机

在 `android-tv/local.properties` 写入（勿提交）：

```properties
sdk.dir=/path/to/Android/Sdk
```

## 构建

```bash
cd android-tv
./gradlew :app:assembleDebug
```

Gradle 输出：`app/build/outputs/apk/debug/app-debug.apk`（`applicationId` 带 `.debug` 后缀）。

侧载用版本化文件名（`assembleDebug` 结束后由 `copyDebugApkToDist` 复制）：

`dist/dtv-tv-debug-<versionName>.apk`

当前 debug：`dist/dtv-tv-debug-0.2.0-lt18-debug.apk`（`versionName` = `0.2.0-lt18` + `-debug`）。

Release：

```bash
./gradlew :app:assembleRelease
```

## 安装到 TV 模拟器 / 设备

```bash
# 启动 TV AVD 后：
adb install -r dist/dtv-tv-debug-0.2.0-lt18-debug.apk
adb shell am start -n com.dtv.androidtv.debug/com.dtv.androidtv.MainActivity
```

创建 AVD（示例）：

```bash
sdkmanager "system-images;android-34;android-tv;x86"
avdmanager create avd -ndtv_tv_api34 -k "system-images;android-34;android-tv;x86" -d "tv_1080p"
emulator -avd dtv_tv_api34
```

遥控器：方向键浏览分区/房间，OK 进入播放，Back 退出播放回到列表。首页 **搜索 / 设置**；测试 HLS 在 **设置 → 关于**。

## 包结构

```
app/src/main/java/com/dtv/androidtv/
  data/model/          # common / DtvApi 契约
  data/douyu/          # Douyu Kotlin 移植
  data/DtvApi.kt
  data/douyu/          # Douyu HTTP + danmaku STT
  player/              # Media3 + FLV relay
  ui/browse|player/    # Compose for TV (+ DanmakuOverlay)
```

## 弹幕开关

播放页 OK 调出 chrome → **弹幕：开 / 弹幕：关**。默认开启（设置页可改默认）；偏好写入 `dtv_tv_library` SharedPreferences。关闭时断开会话并清空叠加层。

## 账号扫码

首页 **设置** → **账号** → 斗鱼 / B 站行 → **扫码登录**。手机 App 扫大屏 QR；成功后 Cookie 写入 `dtv_tv_auth`，冷启动校验。退出会清本地会话（B 站顺带调 passport exit）。登录后重新进播放页可拿到更高清晰度档位（斗鱼原画 / B 站更高 qn）。
