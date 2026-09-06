plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvm("desktop")
    androidTarget()
    sourceSets {
        val desktopMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies { api(project(":kernelBindings")) }
        }
        val androidMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies { api(project(":kernelBindings")) }
        }
        val desktopTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}

android {
    namespace = "org.gilune.plugin.sdk"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
}
