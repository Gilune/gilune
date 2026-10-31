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
            
            dependencies { api(project(":pluginSdk")) }
        }
        val androidMain by getting {
            kotlin.srcDir("src/jvmShared/kotlin")
            
            dependencies { api(project(":pluginSdk")) }
        }
        val desktopTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}

android {
    namespace = "org.gilune.plugins.findreplace"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
}
