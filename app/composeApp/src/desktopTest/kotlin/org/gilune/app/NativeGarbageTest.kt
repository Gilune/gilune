// ADR 0032: Skia's paragraph cache held a copy of every text the editor
// laid out and never hit. Compose keeps the font collection that owns it
// private, so the switch reaches it by reflection — this fails the day a
// Compose upgrade moves it, instead of the cache quietly coming back.

package org.gilune.app

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeGarbageTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun skiasParagraphCacheIsReachedAndKeepsNothingOnceOff() {
        var resolver: Any? = null
        var text by mutableStateOf("first words")
        compose.setContent {
            resolver = LocalFontFamilyResolver.current
            BasicText(text)
        }
        compose.waitForIdle()
        val cache = assertNotNull(NativeGarbage.paragraphCache(resolver!!), "Compose moved its font collection")

        assertTrue(NativeGarbage.disableParagraphCache(resolver!!))
        assertEquals(0, cache.count, "what it held is dropped")
        repeat(5) {
            text = "words number $it"
            compose.waitForIdle()
        }
        assertEquals(0, cache.count, "new texts are laid out without being kept")
    }
}
