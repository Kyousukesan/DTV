plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dtv.androidtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dtv.androidtv"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        // LT-18: Canvas danmaku inside PlayerView.exo_overlay (past LT-17).
        versionName = "0.2.0-lt18"
        // TV leanback launcher + DPAD; phone install not a Slice-1 goal.
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
        unitTests.all {
            // Forward -Ddtv.liveFlvSmoke=true into the test JVM for optional live smoke.
            it.systemProperty(
                "dtv.liveFlvSmoke",
                System.getProperty("dtv.liveFlvSmoke") ?: "",
            )
            it.systemProperty(
                "dtv.liveFlvSmokeSeconds",
                System.getProperty("dtv.liveFlvSmokeSeconds") ?: "320",
            )
            it.systemProperty(
                "dtv.liveDanmakuSmoke",
                System.getProperty("dtv.liveDanmakuSmoke") ?: "",
            )
            it.systemProperty(
                "dtv.liveDanmakuRoom",
                System.getProperty("dtv.liveDanmakuRoom") ?: "8682569",
            )
            it.systemProperty(
                "dtv.liveMultiPlatformSmoke",
                System.getProperty("dtv.liveMultiPlatformSmoke") ?: "",
            )
            it.systemProperty(
                "dtv.liveMultiDanmakuSmoke",
                System.getProperty("dtv.liveMultiDanmakuSmoke") ?: "",
            )
            it.systemProperty(
                "dtv.liveMultiDanmakuRooms",
                System.getProperty("dtv.liveMultiDanmakuRooms") ?: "",
            )
            it.systemProperty(
                "dtv.huyaWsProbe",
                System.getProperty("dtv.huyaWsProbe") ?: "",
            )
            it.systemProperty(
                "dtv.liveSearchQualitySmoke",
                System.getProperty("dtv.liveSearchQualitySmoke") ?: "",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")
    implementation("androidx.tv:tv-material:1.0.0")

    // Media3: progressive FLV via built-in FlvExtractor; HLS kept for test URL path.
    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Encode large on-screen QR for Douyu / Bilibili TV login.
    implementation("com.google.zxing:core:3.5.3")
    // Douyin WS signature: execute assets/douyin/sign.js get_sign (Go uses goja).
    implementation("org.mozilla:rhino:1.7.15")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Real org.json + Robolectric so DouyuClient / OkHttp live smoke can run on JVM.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

/**
 * Sideload artifact name: `dtv-tv-debug-<versionName>.apk` under `android-tv/dist/`.
 * Uses the effective debug versionName (base + versionNameSuffix), with filesystem-unsafe chars replaced.
 */
fun sanitizeApkVersionForFilename(versionName: String): String =
    versionName.replace(Regex("""[\\/:*?"<>|\s]+"""), "-")

tasks.register("copyDebugApkToDist") {
    group = "distribution"
    description = "Copy debug APK to android-tv/dist/ with versioned filename"
    dependsOn("assembleDebug")
    doLast {
        val base = android.defaultConfig.versionName ?: error("versionName missing")
        val suffix = android.buildTypes.getByName("debug").versionNameSuffix.orEmpty()
        val versioned = sanitizeApkVersionForFilename(base + suffix)
        val destName = "dtv-tv-debug-$versioned.apk"
        val src = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val distDir = rootProject.layout.projectDirectory.dir("dist").asFile
        require(src.isFile) { "Missing debug APK at ${src.absolutePath}" }
        distDir.mkdirs()
        // Drop legacy unversioned name so dist only ships the versioned artifact.
        distDir.resolve("dtv-tv-debug.apk").takeIf { it.exists() }?.delete()
        val dest = distDir.resolve(destName)
        src.copyTo(dest, overwrite = true)
        logger.lifecycle("Copied debug APK → ${dest.absolutePath} (${dest.length()} bytes)")
    }
}

// assembleDebug is created by the Android plugin after this script is evaluated.
afterEvaluate {
    tasks.named("assembleDebug") {
        finalizedBy("copyDebugApkToDist")
    }
}
