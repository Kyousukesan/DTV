# DTV Android TV — sideload APK

Prebuilt **debug** APK for private testing (Sony / Leanback Android TV, Android 11+).

## File

| | |
| --- | --- |
| APK | [`dtv-tv-debug.apk`](./dtv-tv-debug.apk) (~15.6 MB) |
| Package | `com.dtv.androidtv.debug` |
| versionName | `0.2.0-lt11-debug` |
| versionCode | `1` |
| minSdk | `24` |
| targetSdk | `35` |

Rebuild locally (optional):

```bash
cd android-tv && ./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Install (Sony Android TV)

1. Copy `dtv-tv-debug.apk` to USB, or use ADB.
2. Allow **Install unknown apps** for your file manager (or enable ADB / network debugging).
3. Install and open **DTV** from the TV apps row.

ADB:

```bash
adb connect <TV_IP>:5555
adb install -r dtv-tv-debug.apk
# if signature conflict:
adb uninstall com.dtv.androidtv.debug && adb install dtv-tv-debug.apk
```

More detail: project store doc `docs/sony-tv-apk-install.md` (Cursor agent store). After install, use DPAD — Home **搜索 / 设置**; player OK for chrome (清晰度 / 线路 / 弹幕 / 关注).

## Notes

- Debug keystore only — not Play Store.
- Needs network for live platforms; Douyu FLV may use localhost relay.
- Leanback-required; phone installs are not the goal.
