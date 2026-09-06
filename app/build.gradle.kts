plugins {
    kotlin("multiplatform") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("com.android.application") version "8.10.1" apply false
}

// Domain modules may depend on SDK/bindings, never the app or another domain.
val verifyPluginBoundaries by tasks.registering {
    group = "verification"
    description = "Verify SDK and domain module dependency boundaries."
}

subprojects {
    if (path == ":kernelBindings" || path == ":pluginSdk" || path.startsWith(":plugins:")) {
        val allowed = when (path) {
            ":kernelBindings" -> setOf(path)
            ":pluginSdk" -> setOf(path, ":kernelBindings")
            else -> setOf(path, ":pluginSdk", ":kernelBindings")
        }
        val verifyBoundary = tasks.register("verifyPluginBoundary") {
            group = "verification"
            doLast {
                configurations.filter { it.isCanBeResolved && it.name.endsWith("CompileClasspath") }
                    .forEach { configuration ->
                        val dependencies = configuration.incoming.resolutionResult.allComponents
                            .mapNotNull { (it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath }
                            .toSet()
                        check(dependencies.all { it in allowed }) {
                            "$path ${configuration.name} crosses a plugin boundary: ${dependencies - allowed}"
                        }
                    }
                logger.lifecycle("$path: compile dependencies stay within $allowed")
            }
        }
        rootProject.tasks.named("verifyPluginBoundaries") { dependsOn(verifyBoundary) }
        tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
            .configureEach { dependsOn(verifyBoundary) }
    }
}
