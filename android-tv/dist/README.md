# Sideload debug APK

Versioned Leanback debug build (`com.dtv.androidtv.debug`):

`dtv-tv-debug-<versionName>.apk`

Example after LT-18: `dtv-tv-debug-0.2.0-lt18-debug.apk`

Rebuild (also copies into this folder via `copyDebugApkToDist`):

```bash
cd android-tv
./gradlew :app:assembleDebug
```

Gradle output (before copy): `app/build/outputs/apk/debug/app-debug.apk`
