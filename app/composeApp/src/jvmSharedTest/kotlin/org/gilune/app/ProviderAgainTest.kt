// The wordless gate and the request that overrides it (ADR 0029), as the
// pure rule both app-side providers ask — NeMo here, Moonshine on the
// phone. In `jvmSharedTest`, so both suites pin it.

package org.gilune.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProviderAgainTest {
    private val worded = """{"media":"wav","rel_path":"Captures/a.wav","text":"what was said"}"""

    @Test
    fun aRecordingThatHasWordsIsLeftAloneUnlessSomebodyAsked() {
        assertIs<ProviderAction.NothingToDo>(providerAction(worded))
        assertIs<ProviderAction.NothingToDo>(providerAction(worded, """{"media":"wav"}"""))
        assertEquals(ProviderAction.Decode, providerAction(worded, """{"media":"wav","again":true}"""))
    }

    @Test
    fun onlyATrueAgainCountsAndAPayloadThatDoesNotParseAskedNothing() {
        assertIs<ProviderAction.NothingToDo>(providerAction(worded, """{"again":false}"""))
        assertIs<ProviderAction.NothingToDo>(providerAction(worded, """{"again":"yes"}"""))
        assertIs<ProviderAction.NothingToDo>(providerAction(worded, "not json"))
    }

    @Test
    fun askingNeverMakesSomethingThatIsNotARecordingDecodable() {
        assertIs<ProviderAction.NothingToDo>(
            providerAction("""{"media":"txt","text":"typed"}""", """{"again":true}"""),
        )
    }
}
