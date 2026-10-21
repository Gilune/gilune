import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

// A released build stamps one version across all three packages; a local
// build passes nothing and keeps the defaults below. jpackage on Windows
// will only accept MAJOR.MINOR.BUILD with MAJOR > 0, so the continuous
// build refuses a `v0.x` tag rather than producing an unversioned MSI —
// see architecture/09-engineering-conventions.md § Continuous builds.
val releaseVersion = project.findProperty("gilune.version") as String?
val releaseVersionCode = (project.findProperty("gilune.versionCode") as String?)?.toInt()
val appVersionName = releaseVersion ?: "1.0.0-dev"
// Artifact export may target Windows while Gradle runs on Linux. Runtime
// dependencies must follow the target, not the build host.
val desktopTarget = providers.gradleProperty("gilune.desktop.target").orNull
require(desktopTarget == null || desktopTarget == "windows-x64") {
    "Unsupported gilune.desktop.target: $desktopTarget"
}
// sherpa-onnx (ADR 0027 S3): the JVM API and one native library, the one for
// the desktop target. They come from the project's GitHub releases (see
// settings.gradle.kts), so each is checked against the SHA-256 the release
// publishes before anything compiles or packages against it.
val sherpaVersion = "1.13.8"
val sherpaNative =
    if (desktopTarget == "windows-x64" || System.getProperty("os.name").startsWith("Windows")) "win-x64" else "linux-x64"
val sherpaDigests = mapOf(
    "sherpa-onnx-jvm-$sherpaVersion.jar" to "77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b",
    "sherpa-onnx-native-lib-win-x64-$sherpaVersion.jar" to "33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039",
    "sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar" to "30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba",
)
val generateAppVersion by tasks.registering {
    val destination = layout.buildDirectory.dir("generated/appVersion")
    inputs.property("version", appVersionName)
    outputs.dir(destination)
    doLast {
        val quoted = appVersionName.replace("\\", "\\\\").replace("\"", "\\\"")
        destination.get().file("org/gilune/app/AppVersion.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package org.gilune.app\n\nconst val APP_VERSION = \"$quoted\"\n")
        }
    }
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
        //     --out-dir app/kernelBindings/src/<target>Main/generated
        val desktopMain by getting {
            kotlin.srcDir(generateAppVersion)
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(if (desktopTarget == "windows-x64")
                    compose.desktop.windows_x64 else compose.desktop.currentOs)
                implementation(project(":pluginSdk"))
                implementation(project(":plugins:tasks"))
                implementation(project(":plugins:people"))
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("org.jetbrains:markdown:0.7.3")
                implementation("net.java.dev.jna:jna:5.14.0")
                // Transcription (NVIDIA NeMo) — ADR 0027 S3. Its models ship
                // as .tar.bz2 archives, hence compress; org.json is the same
                // JSON the Android provider reads with, so the provider's
                // decisions can live in shared code.
                implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:$sherpaVersion")
                runtimeOnly("com.k2fsa.sherpa.onnx:sherpa-onnx-native-lib-$sherpaNative:$sherpaVersion")
                implementation("org.apache.commons:commons-compress:1.27.1")
                implementation("org.json:json:20240303")
            }
        }
        val desktopTest by getting {
            kotlin.srcDir("src/jvmSharedTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":plugins:contractFixture"))
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
        val androidMain by getting {
            kotlin.srcDir(generateAppVersion)
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(project(":pluginSdk"))
                implementation(project(":plugins:tasks"))
                implementation(project(":plugins:people"))
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
                implementation(project(":plugins:contractFixture"))
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test:core:1.6.1")
                // Compose UI tests on device, the counterpart of the desktop
                // suite's compose.desktop.uiTestJUnit4. Android had no UI-test
                // net before ADR 0017 MB0; MB1's tests need this.
                implementation("androidx.compose.ui:ui-test-junit4-android:1.8.2")
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
        versionName = appVersionName
        testInstrumentationRunner = "org.gilune.app.GiluneTestRunner"
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
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.8.2")
}

compose.desktop {
    application {
        mainClass = "org.gilune.app.MainKt"
        // These reach BOTH `run` and the jpackage launcher cfg, so only
        // arguments true of an installed app belong here. A dev-machine
        // path put here ships to every user (the 1.0.1 installer carried
        // the CI runner's `D:/a/gilune/gilune/kernel/target/debug`); dev
        // arguments live on the `run` task below.
        //
        // The footprint flags, each measured on this machine against an
        // idle app with one small note (2026-09-04):
        //  - SerialGC: G1's bookkeeping had committed 75 MB of native
        //    memory to collect a heap using 13 MB. A single-user desktop
        //    app has no pause budget that needs a concurrent collector.
        //  - Xms32m/Xmx768m: the default maximum is a quarter of RAM
        //    (16 GB on a 64 GB machine), and the collector sizes its
        //    tables from that, not from what is used.
        //  - CICompilerCount=2: the minimum for tiered compilation; the
        //    JIT threads it saves are threads and stacks in a process
        //    that measured 101 of them.
        // Together: 504 MB private / 360 MB working set / 101 threads →
        // 376 / 310 / 63, with native-memory tracking's committed total
        // 216 → 91 MB.
        jvmArgs += listOf(
            "-XX:+UseSerialGC",
            "-Xms32m",
            "-Xmx768m",
            "-XX:CICompilerCount=2",
        )

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

// Dev runs only. A dev run loads the kernel dylib from the cargo target
// dir; the packaged app carries it as an app resource and Main.kt puts
// `compose.application.resources.dir` on `jna.library.path` itself, so
// the packaged launcher needs no path of its own — and must not inherit
// this one, which is a path on whichever machine built it.
//
// -Pgilune.vault points a run at a scratch vault and -Pgilune.probe opens
// a screen for UI probes; both are dev affordances and neither can now
// reach a distributable.
//
// Matched rather than named: the Compose plugin registers `run` after this
// file is evaluated, so `tasks.named("run")` would not find it yet.
tasks.matching { it.name == "run" }.configureEach {
    val run = this as JavaExec
    // Forward slashes in a Windows path in a JVM argument. It was a
    // jpackage rule first — its launcher cfg reads a backslash as an
    // escape (observed 2026-08-30) — and stays the habit here.
    val profile = (project.findProperty("gilune.kernel.profile") as String?) ?: "debug"
    val kernelDir = rootProject.projectDir.resolve("../kernel/target/$profile").normalize()
    val devArgs = listOf("-Djna.library.path=${kernelDir.invariantSeparatorsPath}") +
        listOf("gilune.vault", "gilune.probe").mapNotNull { key ->
            (project.findProperty(key) as String?)?.let { "-D$key=$it" }
        }
    // A provider rather than `jvmArgs(...)`: the Compose plugin assigns the
    // task's jvmArgs wholesale when it configures `run`, which silently
    // dropped these — a dev run then could not load the kernel and opened
    // whatever vault the installed app last used (2026-10-19).
    run.jvmArgumentProviders.add(CommandLineArgumentProvider { devArgs })
}

tasks.withType<Test>().configureEach {
    if (System.getenv("GILUNE_CI") == "1") {
        systemProperty("gilune.uiDiagnostics", "true")
        // Runtime gates must execute on this runner, even when compilation
        // is cached. Restored XML is not evidence about its UI environment.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("CI must execute its runtime tests") { true }
    }
    val profile = (project.findProperty("gilune.kernel.profile") as String?) ?: "debug"
    val kernelDir = rootProject.projectDir.resolve("../kernel/target/$profile").normalize()
    systemProperty(
        "jna.library.path",
        kernelDir.toString(),
    )
    // The native implementation can change without changing generated Kotlin.
    // Such a rebuild must invalidate the JVM test result cache.
    inputs.file(kernelDir.resolve(System.mapLibraryName("gilune_ffi")))
        .withPropertyName("giluneKernel")
        .withPathSensitivity(PathSensitivity.NONE)
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("passed", "failed")
    }
}

// A thin, explicit runtime directory consumed by Linux-hosted Windows packaging.
// Dependency resolution brings in the SDK/domain/bindings JARs too.
tasks.register<Sync>("exportDesktopBundle") {
    group = "distribution"
    val jar = tasks.named<Jar>("desktopJar")
    dependsOn(jar)
    into(layout.buildDirectory.dir("export/desktop/lib"))
    from(jar.flatMap { it.archiveFile })
    from(configurations.named("desktopRuntimeClasspath"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
    val bindingsProject = project(":kernelBindings")
    val bindings = bindingsProject.file(project.findProperty("gilune.desktop.bindings")
        ?: "src/desktopMain/generated").resolve("uniffi/gilune_ffi/gilune_ffi.kt")
    inputs.file(bindings)
    inputs.property("exportVersion", appVersionName)
    inputs.property("exportTarget", desktopTarget ?: "host")
    doLast {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bindings.readBytes()).joinToString("") { "%02x".format(it) }
        destinationDir.resolve("export.properties").writeText(
            "version=$appVersionName\ntarget=${desktopTarget ?: "host"}\nbindings.sha256=$hash\n")
    }
}

// Compile on the build host, execute on Windows with the standalone Gradle
// test project in .github/windows-tests. No source compiler is needed there.
tasks.register<Sync>("exportDesktopTests") {
    group = "verification"
    dependsOn("desktopTestClasses", "exportDesktopBundle")
    into(layout.buildDirectory.dir("export/tests"))
    // A TaskProvider would also depend on running desktopTest. Only its
    // compiled inputs belong here: the Windows test runner executes them.
    from(kotlin.targets.getByName("desktop").compilations.getByName("test").output.allOutputs) {
        into("classes")
    }
    from(configurations.named("desktopTestRuntimeClasspath")) { into("lib") }
    from(tasks.named<Jar>("desktopJar").flatMap { it.archiveFile }) { into("lib") }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

// The sherpa-onnx jars come from GitHub release assets, which Gradle does
// not verify on its own. Every one on the desktop runtime classpath must
// match the SHA-256 its release publishes, or nothing compiles against it.
val verifySherpaJars by tasks.registering {
    group = "verification"
    val runtime = configurations.named("desktopRuntimeClasspath")
    inputs.files(runtime)
    doLast {
        val jars = runtime.get().files.filter { it.name.startsWith("sherpa-onnx-") }
        require(jars.isNotEmpty()) { "no sherpa-onnx jar resolved" }
        for (jar in jars) {
            val expected = sherpaDigests[jar.name] ?: error("no published digest recorded for ${jar.name}")
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
            require(actual == expected) { "${jar.name}: SHA-256 $actual, the release publishes $expected" }
        }
    }
}
tasks.matching { it.name == "compileKotlinDesktop" }.configureEach { dependsOn(verifySherpaJars) }
