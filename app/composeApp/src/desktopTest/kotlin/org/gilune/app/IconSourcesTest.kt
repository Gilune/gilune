// The icons the app draws come from the core set and from copies kept in
// jvmShared/.../app/icons (its README says how to add one). The extended
// set they were copied from is a 36 MB jar, and every package carried all
// of it for a few dozen icons (2026-11-01: the MSI went 139 → 99.6 MB
// without it). These rows read the sources so that neither half of the
// rule can drift: the dependency stays out, and each copy stays a faithful,
// licensed, used copy.
//
// Desktop-only on purpose, like DesktopLaunchArgumentsTest: the repository
// is not on the device the instrumented suite runs on.

package org.gilune.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IconSourcesTest {

    private val appDir: File by lazy {
        var here: File? = File(System.getProperty("user.dir")).absoluteFile
        while (here != null) {
            val candidate = File(here, "app/composeApp/build.gradle.kts")
            if (candidate.isFile) return@lazy File(here, "app")
            here = here.parentFile
        }
        error("app/composeApp was not found above ${System.getProperty("user.dir")}")
    }

    private val iconDir get() = File(appDir, "composeApp/src/jvmShared/kotlin/org/gilune/app/icons")

    private val copies get() = iconDir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }

    @Test
    fun noBuildFileDependsOnTheExtendedIconSet() {
        val buildFiles = appDir.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.name == "build.gradle.kts" }
            .toList()
        assertTrue(buildFiles.size > 1, "found only $buildFiles")
        for (file in buildFiles) {
            // Comments may name it to explain why it is absent.
            val code = file.readLines().joinToString("\n") { it.substringBefore("//") }
            assertFalse(code.contains("materialIconsExtended"), "$file depends on the extended icon set")
            assertFalse(code.contains("material-icons-extended"), "$file depends on the extended icon set")
        }
    }

    @Test
    fun everyCopiedIconIsLicensedNamedForItsIconAndInThisPackage() {
        assertTrue(copies.isNotEmpty())
        for (file in copies) {
            val text = file.readText()
            assertTrue(text.contains("Licensed under the Apache License, Version 2.0"), "${file.name} lost its licence header")
            assertTrue(text.contains("// Copied unchanged from material-icons-extended"), "${file.name} lacks the copy note")
            assertTrue(Regex("""(?m)^package org\.gilune\.app\.icons\s*$""").containsMatchIn(text), "${file.name} is in another package")
            val declared = Regex("""(?m)^public val Icons\.[A-Za-z.]+\.([A-Za-z0-9_]+): ImageVector""")
                .findAll(text).map { it.groupValues[1] }.toList()
            assertEquals(listOf(file.nameWithoutExtension), declared, "${file.name} should declare exactly the icon it is named for")
        }
    }

    @Test
    fun everyCopiedIconIsStillDrawnSomewhere() {
        val sources = File(appDir, "composeApp/src").walkTopDown()
            .filter { it.extension == "kt" && it.parentFile != iconDir }
            .joinToString("\n") { it.readText() }
        val unused = copies.map { it.nameWithoutExtension }
            .filterNot { sources.contains("import org.gilune.app.icons.$it") }
        assertEquals(emptyList(), unused, "copied icons nothing imports — delete them")
    }
}
