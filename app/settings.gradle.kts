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
        // sherpa-onnx publishes its JVM jars as GitHub release assets rather
        // than to a Maven repository (ADR 0027 S3). Only its own group may
        // resolve from here, and composeApp checks each jar's SHA-256
        // against the digest the release publishes.
        exclusiveContent {
            forRepository {
                ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download") {
                    name = "sherpa-onnx releases"
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}

rootProject.name = "gilune-app"
include(":composeApp")

include(":kernelBindings", ":pluginSdk", ":plugins:tasks", ":plugins:people", ":plugins:findReplace", ":plugins:contractFixture")
