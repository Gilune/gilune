// Projection rewrites files under an open editor. A buffer read before
// that rewrite must not be written back over it (ADR 0012).

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditingTest {
    @Test
    fun anUnchangedFileMayBeOverwritten() {
        val opened = contentHash("first draft")
        assertTrue(mayOverwrite(opened, contentHash("first draft")))
    }

    @Test
    fun aFileThatChangedSinceItWasReadMayNotBeOverwritten() {
        val opened = contentHash("first draft")
        assertFalse(
            mayOverwrite(opened, contentHash("the kernel canonicalised this")),
            "saving a stale buffer would silently revert what arrived",
        )
    }

    @Test
    fun aFileThatVanishedMayBeRecreated() {
        assertTrue(mayOverwrite(contentHash("first draft"), null))
    }
}
