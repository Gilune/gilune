package org.gilune.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Device-local visibility; hiding a destination never disables its plugin. */
class NavigationVisibility(initiallyHidden: Set<String> = emptySet(), private val persist: (Set<String>) -> Unit = {}) {
    var hidden by mutableStateOf(initiallyHidden)
        private set

    fun setVisible(key: String, visible: Boolean) {
        hidden = if (visible) hidden - key else hidden + key
        persist(hidden)
    }

    fun visible(panels: List<NavigationPanel>): List<NavigationPanel> = panels.filter { it.key !in hidden }
}
