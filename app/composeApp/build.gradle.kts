plugins {
    kotlin("multiplatform") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
    id("com.android.application") version "8.10.1"
}

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
                // ---------------------------------------------------------
                // SPIKE R0 SCAFFOLDING ONLY — ADR 0018 § 6. Not product
                // wiring: the Moonshine AAR is scoped to the instrumented
                // TEST source set, so it lands in the test APK and the
                // shipped app APK is byte-for-byte unaffected. R1 is the
                // stage that would promote this to `androidMain`, and the
                // per-ABI cost of doing so is what R0 measures. Delete this
                // line and `R0*.kt` together if the spike is discarded.
                //
                // Resolves from mavenCentral(), already declared in
                // settings.gradle.kts — no repository addition needed.
                // Pulls appcompat, material, okhttp and work-runtime
                // transitively (recorded in the spike README).
                //
                // The excludes are NOT cosmetic. `androidx.work` and
                // `appcompat` (via emoji2) each contribute an
                // `androidx.startup.InitializationProvider` to the MERGED
                // manifest; AGP then dedupes the `androidx.startup` classes
                // out of the test APK because the app APK already carries
                // them, so any process started for the test package alone
                // dies with ClassNotFoundException before a test runs.
                // Measured — see the spike README § "The transitive
                // dependencies". `okhttp` is deliberately KEPT: a03 measures
                // the vendor's own downloader and genuinely needs it. R1
                // should exclude that one too and fetch models itself, which
                // ADR 0018 § 4's consent rule requires anyway.
                implementation("ai.moonshine:moonshine-voice:0.1.5") {
                    exclude(group = "androidx.appcompat")
                    exclude(group = "com.google.android.material")
                    exclude(group = "androidx.work")
                }
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
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            packageName = "Gilune"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                iconFile.set(project.file("src/desktopMain/resources/gilune-v2.ico"))
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
