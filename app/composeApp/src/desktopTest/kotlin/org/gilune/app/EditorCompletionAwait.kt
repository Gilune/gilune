package org.gilune.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText

// Compose idleness does not cover the IO worker or native validation. A
// popup can already exist while it is still loading the next set of options.
internal fun ComposeContentTestRule.waitForFirstEditorOption(label: String) {
    waitUntil(5_000) {
        onAllNodesWithText("Finding suggestions…").fetchSemanticsNodes().isEmpty() &&
            onAllNodes(hasTestTag("completion-0") and hasText(label)).fetchSemanticsNodes().size == 1
    }
}
