// The window keeps its icon decoded for as long as it is open, so the
// icon's pixels are memory the app holds at rest: the 1,254 px logo cost
// 9–10 MB to show at most 256 px (ADR 0032, 2026-11-02).

package org.gilune.app

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowIconTest {
    @Test
    fun theWindowIconIsNoLargerThanWindowsDrawsOne() {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(WINDOW_ICON), "$WINDOW_ICON is a resource")
        val image = stream.use { ImageIO.read(it) }
        assertTrue(image.width <= 256 && image.height <= 256, "the window icon is ${image.width}×${image.height}")
    }

    @Test
    fun theWindowIsGivenThatIcon() {
        val main = File("src/desktopMain/kotlin/org/gilune/app/Main.kt").readText()
        assertTrue("icon = painterResource(WINDOW_ICON)" in main, "the window's icon is the small one")
    }
}
