plugins {
    kotlin("multiplatform") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            // UniFFI-generated bindings land here (see kernel README):
            //   cargo run --bin uniffi-bindgen -- generate --library <dll> \
            //     --language kotlin --out-dir shell/composeApp/src/desktopMain/generated
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
            dependencies {
                implementation(kotlin("test"))
            }
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

compose.desktop {
    application {
        mainClass = "MainKt"
        // The spike loads the kernel dylib from the cargo target dir; a real
        // app would package it as a resource. Debug by default; override with
        // -Ps1.kernel.profile=release.
        val profile = (project.findProperty("s1.kernel.profile") as String?) ?: "debug"
        val kernelDir = rootProject.projectDir.resolve("../kernel/target/$profile").normalize()
        jvmArgs += "-Djna.library.path=$kernelDir"
    }
}
