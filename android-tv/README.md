# DTV Android TV（Slice-1 + M3 · 斗鱼）

本目录是 LIVE-TV 在本 fork 下的 **Android TV 原生客户端**（Compose for TV + Media3），
Kotlin 契约移植桌面端 `DtvApi` / `common` 与 Go `internal/platforms/douyu`。
**不**嵌入 Wails/React。

## 范围（已实现）

- Android TV / Leanback launcher、DPAD 友好浏览 → 选房间 → 全屏播放
- **M3 首页行**：关注 / 最近观看（本地 SharedPreferences）+ 分区直播行；行内 `focusRestorer` 焦点记忆；overscan safe padding
- 播放页 chrome：OK 显控 / 播放暂停、关注切换、Back 退出；未开播 / 错误可读 + 重试
- Media3：斗鱼 FLV + 测试 HLS
- **LT-5 FLV 续流**：本地 `127.0.0.1` splice 中继（移植桌面 `StreamRelayService` / `flv_relay`）：到期前预取新签名直链，在 FLV tag/关键帧边界拼接；Media3 只播一条本地 URL。失败时仍保留 quiet re-sign + 错误退避兜底。

## 明确延后

弹幕、扫码登录、虎牙/抖音/B 站、局域网同步。

## Douyu 播放路径说明

| 步骤 | 行为 |
| --- | --- |
| 列表 | `m.douyu.com/api/cate/list` + `gapi/rkc/directory/2_{cate2}/{page}` |
| 房间 | `www.douyu.com/betard/{roomId}` |
| 取流 | `websec/getEncryption` → POST `lapi/live/getH5PlayV1/{roomId}` |
| 格式 | **几乎总是 FLV**（`format=flv`）。若 URL 含 `.m3u8` 则走 HLS |
| ~300s 掐线 | **本地 FLV splice 中继**（`DouyuFlvRelayServer` + `relayFlv`）：~295s 预取新直链；CDN EOF 时 `eof_renew`，或边沿 TCP 不掐时在 TTL 主动 `ttl_rotate`；丢新段文件头/seq header，从越过时间轴的关键帧接上；`expire=0` 仍保留。中继失败时回退 MediaItem re-sign。 |

## 续流验证

```bash
cd android-tv
./gradlew :app:testDebugUnitTest --tests com.dtv.androidtv.player.FlvRelayTest
# 可选：真实斗鱼跑过一个 CDN 周期（约 320s）
./gradlew :app:testDebugUnitTest --tests com.dtv.androidtv.player.LiveFlvContinuitySmokeTest \
  -Ddtv.liveFlvSmoke=true -Ddtv.liveFlvSmokeSeconds=320
# 日志：app/build/reports/live-flv-continuity.log
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

APK：`app/build/outputs/apk/debug/app-debug.apk`（`applicationId` 带 `.debug` 后缀）。

Release：

```bash
./gradlew :app:assembleRelease
```

## 安装到 TV 模拟器 / 设备

```bash
# 启动 TV AVD 后：
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.dtv.androidtv.debug/com.dtv.androidtv.MainActivity
```

创建 AVD（示例）：

```bash
sdkmanager "system-images;android-34;android-tv;x86"
avdmanager create avd -ndtv_tv_api34 -k "system-images;android-34;android-tv;x86" -d "tv_1080p"
emulator -avd dtv_tv_api34
```

遥控器：方向键浏览分区/房间，OK 进入播放，Back 退出播放回到列表。浏览页「播放测试 HLS」可在无斗鱼网络时验证 Media3。

## 包结构

```
app/src/main/java/com/dtv/androidtv/
  data/model/          # common / DtvApi 契约
  data/douyu/          # Douyu Kotlin 移植
  data/DtvApi.kt
  player/              # Media3 + FLV 重签
  ui/browse|player/    # Compose for TV
```
