// Presentation mode (ADR 0012 amendment, owner 2026-10-19): the rendered
// note with no caret, where a click acts instead of starting an edit. A
// box ticks — through the same decorations the editor uses, so `done:` is
// stamped exactly as it is there — a link opens, a `[[wiki link]]` finds
// its note, and a plugin's reference (a person's @mention) opens the page
// it names. Nothing typed reaches the note: there is no field to type in.
//
// The shell knows no reference syntax. A plugin's document view says where
// each reference is written; the renderer makes those words clickable, and
// what a click opens is the plugin's own lookup, run here.

package org.gilune.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gilune.plugin.sdk.DocumentReference
import org.gilune.plugin.sdk.EditorDecoration
import uniffi.gilune_ffi.inspectSourceText
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLDecoder

@Composable
internal fun PresentedNote(
    state: GiluneState,
    editor: EditorState,
    decorations: List<EditorDecoration>,
    openNote: ((String) -> Unit)?,
    write: () -> Unit,
) {
    val appearance = LocalNoteAppearance.current
    val plugins = state.plugins.toList()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // The keys live here — Esc back to editing, the arrows to scroll — so
    // the view takes the focus when it appears. As with the body field,
    // the requester answers only once attached, so ask over a few frames.
    LaunchedEffect(Unit) {
        var attempts = 0
        while (runCatching { focus.requestFocus() }.isFailure && attempts++ < 5) withFrameNanos { }
    }

    // Where each plugin reference is written, moved from document to body
    // offsets: the renderer draws the body alone.
    val document = editor.document()
    val bodyStart = editor.frontMatterRaw.length
    val references = rememberQueryWork(document, plugins) {
        val snapshot = inspectSourceText(document)
        BUNDLED_PLUGIN_EXTENSIONS.documentViews(plugins).mapNotNull { it.describe(snapshot) }
            .flatMap { view -> view.references }
            .flatMap { reference -> reference.written.map { (it.first - bodyStart)..(it.last - bodyStart) to reference } }
            .filter { (range, _) -> range.first >= 0 }
    }.value.value.orEmpty()

    fun openReference(reference: DocumentReference) {
        val kernel = state.gilune.value ?: return
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { kernel.lookup(reference.query.copy(cursor = null, pageSize = 2u)) }
            }
            val page = found.getOrNull()
            when {
                page == null -> state.message.value = "could not look up ${reference.label}: ${found.exceptionOrNull()?.message}"
                !page.indexComplete -> state.message.value = "still indexing — try ${reference.label} again in a moment"
                page.hits.size == 1 -> openNote?.invoke(page.hits.single().relPath)
                page.hits.isEmpty() -> state.message.value = "no page for ${reference.label} yet"
                else -> state.message.value = "more than one page claims ${reference.label}"
            }
        }
    }

    fun openWiki(target: String) {
        scope.launch {
            val match = withContext(Dispatchers.IO) { resolveWikiLink(vaultNotes(state.vaultDir), target) }
            if (match != null) openNote?.invoke(match) else state.message.value = "no note named $target"
        }
    }

    fun openAddress(target: String) {
        if (target.contains("://") || target.startsWith("mailto:", ignoreCase = true)) {
            runCatching { Desktop.getDesktop().browse(URI(target)) }
                .onFailure { state.message.value = "could not open $target: ${it.message}" }
            return
        }
        val note = editor.openRelPath ?: return
        val match = resolveVaultAddress(state.vaultDir, note, target)
        if (match != null) openNote?.invoke(match) else state.message.value = "no note at $target"
    }

    val links = ReadLinks(references) { link ->
        when (link) {
            is ReadLink.Address -> openAddress(link.target)
            is ReadLink.Wiki -> openWiki(link.target)
            is ReadLink.Reference -> openReference(link.reference)
        }
    }

    // A tick rewrites the one line the parser said the box is on, through
    // the activation chain, and writes at once — § 1 of ADR 0015, as in
    // the editor. The caret the editor will come back to is kept in bounds.
    fun toggle(line: Int) {
        val lines = editor.text.text.split('\n').toMutableList()
        if (line !in lines.indices) return
        lines[line] = activateLine(lines[line], decorations) ?: return
        val written = lines.joinToString("\n")
        val selection = editor.text.selection
        editor.text = editor.text.copy(
            text = written,
            selection = TextRange(selection.start.coerceIn(0, written.length), selection.end.coerceIn(0, written.length)),
        )
        editor.dirty = true
        write()
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = when (event.key) {
                    Key.DirectionDown -> 60f
                    Key.DirectionUp -> -60f
                    Key.PageDown -> scroll.viewportSize * 0.9f
                    Key.PageUp -> -scroll.viewportSize * 0.9f
                    else -> null
                }
                when {
                    event.key == Key.Escape -> { editor.togglePresentation(); true }
                    step != null -> { scope.launch { scroll.animateScrollBy(step) }; true }
                    else -> false
                }
            }
            .testTag("presented-note"),
    ) {
        // The `editor.blocks` seam (ADR 0015 § 5, T4): the shell's own
        // `filter` is the table's only entry, and every other fence is the
        // code box it has always been. Reading the vault cursor inside the
        // registration is what makes a block recompute when the vault
        // moves rather than per keystroke (§ 4).
        MarkdownBody(
            remember(editor.text.text) { parseMarkdown(editor.text.text) },
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            renderers = BlockRenderers(listOf(filterBlockRegistration(state, plugins, onOpen = openNote))),
            onToggleTask = ::toggle,
            textStyle = appearance.reading.textStyle,
            blockSpacing = appearance.reading.blockSpacing.dp,
            links = links,
        )
    }
}

/// Every note in the vault by its relative path — names only, no contents
/// read, the same walk quick-open makes.
internal fun vaultNotes(vaultDir: String): List<String> {
    val root = File(vaultDir)
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .onEnter { !it.name.startsWith(".") }
        .filter { it.isFile && it.name.endsWith(".md") }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .toList()
}

/// The note a `[[wiki link]]` names, Obsidian's way: a heading anchor is
/// dropped, `.md` is optional, a path names that note exactly, and a bare
/// name finds the note of that name wherever it is — the shortest path
/// when several share it. Case is not significant. Null when none does.
internal fun resolveWikiLink(notes: List<String>, target: String): String? {
    val wanted = target.substringBefore('#').trim().removeSuffix(".md").trim('/')
    if (wanted.isEmpty()) return null
    notes.firstOrNull { it.removeSuffix(".md").equals(wanted, ignoreCase = true) }?.let { return it }
    val name = wanted.substringAfterLast('/')
    return notes
        .filter { it.substringAfterLast('/').removeSuffix(".md").equals(name, ignoreCase = true) }
        .minByOrNull { it.length }
}

/// The note a Markdown link's destination names: relative to the linking
/// note's folder first, then to the vault, percent-escapes decoded, a
/// `#anchor` dropped and `.md` optional. Null when it names no note in the
/// vault — a path escaping the vault included.
internal fun resolveVaultAddress(vaultDir: String, fromNote: String, target: String): String? {
    val raw = target.substringBefore('#')
    if (raw.isEmpty()) return null
    val path = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
    val root = File(vaultDir).canonicalFile
    val folder = File(root, fromNote).parentFile ?: root
    for (base in listOf(folder, root)) {
        for (candidate in listOf(path, "$path.md")) {
            val file = File(base, candidate).canonicalFile
            val inside = file.toPath().startsWith(root.toPath())
            if (inside && file.isFile && file.name.endsWith(".md")) return file.relativeTo(root).invariantSeparatorsPath
        }
    }
    return null
}
