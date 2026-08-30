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
        // The diagnostic screen and boundary tests are plain JVM code shared
        // by the desktop and Android targets; both source sets include the
        // same directories rather than fighting KMP's hierarchy for a
        // jvm+android intermediate set — spike pragmatism.
        // UniFFI bindings are generated PER TARGET, from that target's own
        // binary: the API checksums baked into the bindings are
        // target-dependent (S1 finding), so a shared bindings file passes on
        // one platform and throws checksum-mismatch on the other.
        //   cargo run --bin uniffi-bindgen -- generate --library <dll|so> \
        //     --language kotlin --out-dir src/<target>Main/generated
        val desktopMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            kotlin.srcDir("src/desktopMain/generated")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("net.java.dev.jna:jna:5.14.0")
            }
        }
        val desktopTest by getting {
            kotlin.srcDir("src/jvmSharedTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            kotlin.srcDir("src/androidMain/generated")
            dependencies {
                implementation(compose.material3)
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
                implementation("androidx.work:work-runtime-ktx:2.9.1")
                implementation("net.java.dev.jna:jna:5.14.0@aar")
            }
        }
        val androidInstrumentedTest by getting {
            kotlin.srcDir("src/jvmSharedTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test:core:1.6.1")
            }
        }
    }
}

android {
    namespace = "org.gilune.spike.s1"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.gilune.spike.s1"
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
            // The kernel .so is dropped here by the cargo cross-build (see
            // README); AGP packages it per-ABI.
            jniLibs.srcDir("src/androidMain/jniLibs")
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.gilune.spike.s1.MainKt"
        // Dev runs load the kernel dylib from the cargo target dir; the
        // packaged distributable carries it as an app resource instead
        // (resources/windows-x64/, wired into jna.library.path by main()).
        // Debug by default; override with -Ps1.kernel.profile=release.
        val profile = (project.findProperty("s1.kernel.profile") as String?) ?: "debug"
        val kernelDir = rootProject.projectDir.resolve("../kernel/target/$profile").normalize()
        jvmArgs += "-Djna.library.path=$kernelDir"

        nativeDistributions {
            packageName = "GiluneS1Diagnostics"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
        }
        buildTypes.release.proguard {
            // Spike measures unminified size; minification of JNA/generated
            // bindings is its own project.
            isEnabled.set(false)
        }
    }
}

tasks.withType<Test>().configureEach {
    val profile = (project.findProperty("s1.kernel.profile") as String?) ?: "debug"
    systemProperty(
        "jna.library.path",
        rootProject.projectDir.resolve("../kernel/target/$profile").normalize().toString(),
    )
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}
