pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gilune-app"
include(":composeApp")

include(":kernelBindings", ":pluginSdk", ":plugins:tasks", ":plugins:people", ":plugins:contractFixture")
