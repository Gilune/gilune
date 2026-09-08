package org.gilune.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

// Compose idleness does not cover the IO worker or native validation. A
// popup can already exist while it is still loading the next set of options.
internal fun ComposeContentTestRule.waitForFirstEditorOption(label: String) {
    waitUntil(10_000) {
        onAllNodesWithText("Finding suggestions…").fetchSemanticsNodes().isEmpty() &&
            onAllNodes(hasTestTag("completion-0") and hasText(label)).fetchSemanticsNodes().size == 1
    }
}

internal fun ComposeContentTestRule.chooseFirstEditorOption(label: String) {
    waitForFirstEditorOption(label)
    // The label may be merged into its parent. Click the actual interactive
    // row on both platforms, only once its labelled option is ready.
    onNodeWithTag("completion-0", useUnmergedTree = true).assertHasClickAction().performClick()
}
