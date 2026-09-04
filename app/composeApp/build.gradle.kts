import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
    id("com.android.application") version "8.10.1"
}

// A released build stamps one version across all three packages; a local
// build passes nothing and keeps the defaults below. jpackage on Windows
// will only accept MAJOR.MINOR.BUILD with MAJOR > 0, so the continuous
// build refuses a `v0.x` tag rather than producing an unversioned MSI —
// see architecture/09-engineering-conventions.md § Continuous builds.
val releaseVersion = project.findProperty("gilune.version") as String?
val releaseVersionCode = (project.findProperty("gilune.versionCode") as String?)?.toInt()

kotlin {
    jvm("desktop")
    androidTarget()

    sourceSets {
        // The app screen and platform-neutral helpers are plain JVM code
        // shared by both targets; UniFFI bindings are generated PER TARGET
        // from that target's own binary (07-compatibility):
        //   cargo run -p gilune-ffi --bin uniffi-bindgen -- generate \
        //     --library <dll|so> --language kotlin \
        //     --out-dir app/composeApp/src/<target>Main/generated
        val desktopMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            kotlin.srcDir("src/desktopMain/generated")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("org.jetbrains:markdown:0.7.3")
                implementation("net.java.dev.jna:jna:5.14.0")
            }
        }
        val desktopTest by getting {
            kotlin.srcDir("src/jvmSharedTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
        val androidMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            kotlin.srcDir("src/androidMain/generated")
            dependencies {
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
                implementation("org.jetbrains:markdown:0.7.3")
                implementation("net.java.dev.jna:jna:5.14.0@aar")
                // Device-local preferences (ADR 0017 § 6). `Prefs` does not
                // port — `java.util.prefs` is unusable on Android — and the
                // ADR names DataStore as the replacement.
                implementation("androidx.datastore:datastore-preferences:1.1.1")
                // -------------------------------------------------------
                // The live caption's engine (ADR 0018 § 4, R1). Promoted
                // from R0's test-only scope to the shipped app: this is
                // the stage that puts the AAR in the APK, and the cost is
                // measured rather than estimated — 16.70 MiB of native
                // libraries per arm64 device, +8.02 MiB of dex and
                // resources IF the transitive dependencies come with it.
                //
                // **The four exclusions are a defect fix, not a saving**
                // (R0 condition 4). `androidx.work` and `appcompat` (via
                // emoji2) each contribute an
                // `androidx.startup.InitializationProvider` to the merged
                // manifest, which AGP then dedupes out of the test APK —
                // crashing every process started for the test package
                // alone. Excluding them removed the crash outright,
                // measured, and takes 8.13 MiB with it. Nothing in the AAR
                // references Material at all; `appcompat` appears only in
                // the signature of `loadFromAssets`, which we never call
                // (we use `loadFromFiles`).
                //
                // **okhttp goes too, and that is a decision the ADR took
                // rather than a size argument**: § 4 requires the model
                // fetch to go through the plugin's `network.request`
                // consent, Wi-Fi-first, so the vendor's `AssetDownloader`
                // could never have been the downloader. `MoonshineDownload.kt`
                // is ours; `AssetDownloader` is consequently unreachable
                // and must never be called.
                //
                // `gilune_ffi.so` does not grow by a byte — Moonshine
                // links into nothing of ours — so ADR 0001's 15 MB
                // per-ABI kernel budget is untouched by this line.
                implementation("ai.moonshine:moonshine-voice:0.1.5") {
                    exclude(group = "androidx.appcompat")
                    exclude(group = "com.google.android.material")
                    exclude(group = "com.squareup.okhttp3")
                    exclude(group = "androidx.work")
                }
                // -------------------------------------------------------
                // Reading a pairing QR (ADR 0019 § 7). CameraX supplies
                // the preview and the analysis frames; `zxing:core`
                // decodes the luminance plane directly, which is why
                // there is no wrapper library here — the decode is
                // fifteen lines in `QrScan.kt` and a wrapper would be a
                // dependency to own for it.
                //
                // ML Kit's code scanner would need no camera permission
                // at all and was declined in the ADR's alternatives: it
                // is closed source and hard-requires Play services, in an
                // app that removed OkHttp from a vendor AAR to keep a
                // network fetch inside its consent gate.
                //
                // **`camera-view` drags `androidx.appcompat` back in, and
                // it is excluded for the reason above** (measured with
                // `:composeApp:dependencies`, 2026-09-03): appcompat is
                // one of the two groups whose `InitializationProvider`
                // AGP dedupes out of the test APK, and letting CameraX
                // reopen that defect would cost the instrumented suite
                // rather than an argument. It is safe to cut: the
                // `camera-view-1.4.2.aar` was unpacked and its 64
                // classes, its resources and its `R.txt` contain **no
                // reference to appcompat at all** — the dependency is
                // vestigial. `androidx.work` is not in the CameraX tree
                // at any depth. What CameraX does add is
                // `androidx.camera:*`, `concurrent-futures`,
                // `androidx.tracing` and `listenablefuture`, none of them
                // a manifest provider.
                implementation("androidx.camera:camera-core:1.4.2")
                implementation("androidx.camera:camera-camera2:1.4.2")
                implementation("androidx.camera:camera-lifecycle:1.4.2")
                implementation("androidx.camera:camera-view:1.4.2") {
                    exclude(group = "androidx.appcompat")
                }
                implementation("com.google.zxing:core:3.5.3")
                // -------------------------------------------------------
                // The phone's background sync (ADR 0019 § 10), which names
                // a `PeriodicWorkRequest` by construct.
                //
                // **This is the group the Moonshine AAR excludes**, and it
                // is worth saying why adding it here is not a reversal of
                // that. The exclusion above is a defect fix: `androidx.work`
                // brings `androidx.startup`, whose `InitializationProvider`
                // AGP dedupes out of the test APK, and every process
                // started for the test package crashed. That was measured
                // when the AAR dragged the dependency in silently. Declared
                // deliberately here, the same merged provider is measured
                // again rather than assumed — see the instrumented suite
                // count in this round's report. The AAR's exclusion stays
                // because nothing in Moonshine uses WorkManager; ours is
                // the only reason the group is on the classpath.
                //
                // `work-runtime` rather than `-ktx`: the worker is a
                // blocking `Worker`, and nothing here wants the coroutine
                // surface.
                implementation("androidx.work:work-runtime:2.10.0")
            }
        }
        val androidInstrumentedTest by getting {
            kotlin.srcDir("src/jvmSharedTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test:core:1.6.1")
                // Compose UI tests on device, the counterpart of the desktop
                // suite's compose.desktop.uiTestJUnit4. Android had no UI-test
                // net before ADR 0017 MB0; MB1's tests need this.
                implementation("androidx.compose.ui:ui-test-junit4-android:1.7.6")
                // Compose's test rule syncs through Espresso's `onIdle`,
                // which reflected on a framework method that Android 16
                // removed: every UI test failed on the API 36 emulator
                // with NoSuchMethodException InputManager.getInstance.
                // 3.7.0 is the release that stopped doing that.
                implementation("androidx.test.espresso:espresso-core:3.7.0")
                // The Moonshine AAR is NOT declared here any more. R1
                // promoted it to `androidMain` (above), so the tested app
                // carries it and the instrumented suite — R0's spike tests
                // included — compiles against the app's own dependency.
                // One consequence is deliberate and recorded in R0's
                // README: with okhttp excluded, the vendor's
                // `AssetDownloader` is unreachable, so R0's `a03` skips
                // itself rather than failing.
            }
        }
    }
}

android {
    namespace = "org.gilune.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.gilune.app"
        minSdk = 29
        targetSdk = 36
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersion ?: "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The two ABIs this app ships, stated so that a dependency's
            // extra ones cannot ride along (R0 condition 4). The Moonshine
            // AAR carries **armeabi-v7a**, which Gilune does not support;
            // without this filter a universal build would package 12.72 MiB
            // of native libraries it can never load. Our own jniLibs have
            // only these two directories, so nothing of ours is affected.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
            jniLibs.srcDir("src/androidMain/jniLibs")
            // The window theme lives here (ADR 0017 § 2): the hard-coded
            // light theme went, so dark mode follows the system from the
            // very first frame rather than from Compose's first paint.
            res.srcDir("src/androidMain/res")
        }
    }

    // Release signing exists only when a keystore is handed to the build
    // through the environment, which is how the continuous build supplies
    // one from repository secrets. Absent it there is no release signing
    // config at all — `assembleDebug` is unaffected, and `assembleRelease`
    // is simply not asked for rather than producing an unsigned APK that
    // looks installable and is not.
    val keystore = System.getenv("GILUNE_KEYSTORE_PATH")
        ?.let(::file)
        ?.takeIf { it.isFile }
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("GILUNE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GILUNE_KEY_ALIAS")
                keyPassword = System.getenv("GILUNE_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    // Supplies the empty activity createComposeRule launches into. It must be
    // a debug dependency: it merges an activity into the test APK's manifest.
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
}

compose.desktop {
    application {
        mainClass = "org.gilune.app.MainKt"
        // Dev runs load the kernel dylib from the cargo target dir; the
        // packaged distributable carries it as an app resource instead.
        // Debug by default; override with -Pgilune.kernel.profile=release.
        val profile = (project.findProperty("gilune.kernel.profile") as String?) ?: "debug"
        val kernelDir = rootProject.projectDir.resolve("../kernel/target/$profile").normalize()
        // Forward slashes: jpackage's launcher cfg treats backslashes as
        // escapes and mangles Windows paths (observed 2026-08-30).
        jvmArgs += "-Djna.library.path=${kernelDir.invariantSeparatorsPath}"
        // Dev-run affordances only: -Pgilune.vault points a `run` at a
        // scratch vault, -Pgilune.probe opens a screen for UI probes.
        // Neither is ever passed while packaging, so distributables are
        // unaffected.
        listOf("gilune.vault", "gilune.probe").forEach { key ->
            (project.findProperty(key) as String?)?.let { jvmArgs += "-D$key=$it" }
        }

        nativeDistributions {
            // The two installer formats the project ships. Formats the
            // current OS cannot build are ignored by the packaging tasks,
            // so this list is the same on both build machines.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Gilune"
            packageVersion = releaseVersion ?: "1.0.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                iconFile.set(project.file("src/desktopMain/resources/gilune-v2.ico"))
                // jpackage adds a Start-menu entry only when asked. The
                // 1.0.0 installer asked for nothing, installed cleanly, and
                // left the owner unable to find the app anywhere but
                // Program Files.
                menu = true
                menuGroup = "Gilune"
                // The MSI's UpgradeCode. Without a fixed one jpackage mints
                // a fresh UUID per build, so a newer installer sits beside
                // the older install instead of replacing it. Fixed here for
                // the life of the product; 1.0.0 predates it and must be
                // uninstalled by hand.
                upgradeUuid = "c077b799-5193-4ce3-b93a-411c67e29e37"
            }
            linux {
                // Debian package names are lower-case by policy, and
                // jpackage rejects the capitalised one rather than
                // folding it. The icon must be a PNG here; the .ico
                // above is Windows-only.
                packageName = "gilune"
                iconFile.set(project.file("src/desktopMain/resources/gilune-logo-v2.png"))
                debMaintainer = "gilune@users.noreply.github.com"
                menuGroup = "Office"
                appCategory = "utils"
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

tasks.withType<Test>().configureEach {
    val profile = (project.findProperty("gilune.kernel.profile") as String?) ?: "debug"
    systemProperty(
        "jna.library.path",
        rootProject.projectDir.resolve("../kernel/target/$profile").normalize().toString(),
    )
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}
