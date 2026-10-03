plugins { java }

// Run the Linux-compiled tests against the exact Windows bundle under review.
// This project has no repositories and no Kotlin/Compose compilation plugins.
val artifacts = providers.gradleProperty("artifacts").map { file(it) }
tasks.test {
    val root = artifacts.get()
    testClassesDirs = files(root.resolve("tests/classes"))
    classpath = files(root.resolve("tests/classes")) + fileTree(root.resolve("tests/lib")) {
        include("*.jar")
    }
    systemProperty("jna.library.path", root.resolve("package/Gilune/resources").absolutePath)
    systemProperty("gilune.uiDiagnostics", "true")
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Runtime validation always executes") { true }
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("passed", "failed", "skipped")
    }
    doFirst {
        check(root.resolve("package/Gilune/resources/gilune_ffi.dll").isFile)
        check(testClassesDirs.files.any { it.isDirectory })
    }
}
