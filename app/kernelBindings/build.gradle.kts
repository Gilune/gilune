plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvm("desktop")
    androidTarget()
    sourceSets {
        val desktopMain by getting {
            kotlin.srcDir(project.findProperty("gilune.desktop.bindings")
                ?: "src/desktopMain/generated")
            dependencies { api("net.java.dev.jna:jna:5.14.0") }
        }
        val androidMain by getting {
            kotlin.srcDir("src/androidMain/generated")
            dependencies { api("net.java.dev.jna:jna:5.14.0@aar") }
        }
        val desktopTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}

android {
    namespace = "org.gilune.kernel.bindings"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
}
