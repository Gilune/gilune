// The navigation rail: two panels behind a pair of tabs — the vault as
// folders and notes, and a search across their contents — with the
// creation, renaming and deletion a file browser owes you. It works the
// vault the way any third-party tool does — plain file IO, reconciled by
// the kernel afterwards (constitution rule 13 as UI). A new note is
// never named up front: it lands as Untitled and takes its name later,
// from whatever ends up in it. Folder names and renames are typed inline
// in the tree, not in a modal (ADR 0011 design session).
//
// Captured audio sits beside its note as `<stem>.wav`; the rail treats
// such files as the note's attachments and keeps them folded away, since
// the note is what the user came for.
//
// Deleting is the one gesture that is not plain file IO all the way
// down: removing a projected note raises a tombstone proposal rather
// than destroying anything, so this panel answers that proposal at the
// point of deletion. Deletions made outside the app surface as a banner
// instead.

package org.gilune.app

import org.gilune.plugins.tasks.TASKS_RAIL_PANEL

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.People
import org.gilune.plugins.people.PEOPLE_PANEL
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.gilune.plugin.sdk.PagePreset

enum class VaultEntryKind { DIRECTORY, NOTE, ATTACHMENT }

data class VaultEntry(
    val relPath: String,
    val kind: VaultEntryKind,
    val depth: Int,
    val hasAttachments: Boolean = false,
) {
    val isDir: Boolean get() = kind == VaultEntryKind.DIRECTORY
}

/// Flat, ordered listing of the vault tree with collapsed folders left
/// unopened. `expanded` names both: an expanded folder lists its
/// children, an expanded note lists its attachments. Only folders and
/// notes are listed by default; `.gilune` and dot-files never are, and a
/// non-markdown file no note owns is not vault content the rail shows —
/// it stays on disk regardless. Pure over the filesystem so it is
/// trivially testable.
fun listVault(vaultDir: String, expanded: Set<String> = emptySet()): List<VaultEntry> {
    val root = File(vaultDir)
    val out = mutableListOf<VaultEntry>()
    fun walk(dir: File, depth: Int) {
        val children = dir.listFiles()?.filterNot { it.name.startsWith(".") } ?: return
        val attachments = children.groupBy { it.attachmentStem() }
        for (child in children.sortedWith(TREE_ORDER)) {
            val rel = child.relativeTo(root).invariantSeparatorsPath
            if (child.isDirectory) {
                out.add(VaultEntry(rel, VaultEntryKind.DIRECTORY, depth))
                if (rel in expanded) walk(child, depth + 1)
                continue
            }
            if (!child.name.endsWith(".md")) continue
            val owned = attachments[child.name.removeSuffix(".md")].orEmpty()
            out.add(VaultEntry(rel, VaultEntryKind.NOTE, depth, hasAttachments = owned.isNotEmpty()))
            if (rel !in expanded) continue
            for (file in owned.sortedBy { it.name.lowercase() }) {
                val attachment = file.relativeTo(root).invariantSeparatorsPath
                out.add(VaultEntry(attachment, VaultEntryKind.ATTACHMENT, depth + 1))
            }
        }
    }
    walk(root, 0)
    return out
}

private val TREE_ORDER =
    compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }

/// A non-markdown file belongs to the sibling note that shares its stem:
/// `kitchen.wav` is an attachment of `kitchen.md`. Sharing the stem is
/// the whole of the binding — nothing records it — which is why renaming
/// a note has to take its attachments with it.
internal fun File.attachmentStem(): String? =
    if (isFile && !name.startsWith(".") && !name.endsWith(".md")) {
        name.substringBeforeLast('.')
    } else {
        null
    }

/// Notes matching a query, best first: name prefix beats name substring
/// beats path substring. Pure, for the jump-to search in the top bar.
fun searchNotes(vaultDir: String, query: String): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val root = File(vaultDir)
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .onEnter { !it.name.startsWith(".") }
        .filter { it.isFile && it.name.endsWith(".md") }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .filter { it.lowercase().contains(q) }
        .sortedWith(
            compareBy(
                { rel ->
                    val name = rel.substringAfterLast('/').lowercase()
                    when {
                        name.startsWith(q) -> 0
                        name.contains(q) -> 1
                        else -> 2
                    }
                },
                { it.length },
            ),
        )
        .toList()
}

/// Which panel the rail shows — **a plugin-keyed value, not a two-case
/// enum** (ADR 0015 § 5, T3). The shell owns [FILES] and [SEARCH]; every
/// other key belongs to a `navigation` contribution, so a plugin adds a
/// destination without this type growing a case and removing it takes the
/// destination away again.
data class RailPanel(val key: String) {
    companion object {
        val FILES = RailPanel("files")
        val SEARCH = RailPanel("search")
    }
}

class TreeState {
    val expanded = mutableStateListOf<String>()
    var selectedDir by mutableStateOf("")
    var selectedFile by mutableStateOf<String?>(null)

    /// Which rail panel shows. It lives here rather than inside the rail
    /// because the window switches to search before focusing its query
    /// field (ctrl+f), and a focus request cannot select a panel.
    var railPanel by mutableStateOf(RailPanel.FILES)

    fun toggle(relPath: String) {
        if (!expanded.remove(relPath)) expanded.add(relPath)
    }
}

/// The rail: tabs over whichever panel is showing. `searchFocus` belongs
/// to the search panel's query field, so a window shortcut that focuses
/// it must set `tree.railPanel` to SEARCH first — a focus request cannot
/// switch panels. `onCreateNote` is handed a note that already exists on
/// disk. `onOpenToSide`, `onRenamed` and `onDeleted` are how the tree
/// tells the pane host what happened to a note it may have open
/// (ADR 0014); each defaults to nothing, so a rail without panes behind
/// it still compiles.
@Composable
fun RailPanels(
    state: GiluneState,
    tree: TreeState,
    searchFocus: FocusRequester,
    onOpen: (String) -> Unit,
    onCreateNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialSearchQuery: String = "",
    onOpenToSide: (String) -> Unit = {},
    onRenamed: (String, String) -> Unit = { _, _ -> },
    onDeleted: (String) -> Unit = {},
    navigation: NavigationVisibility = Prefs.navigation,
) {
    val colours = MaterialTheme.colorScheme
    // The rail's upper region as ADR 0011 always described it: a declared
    // navigation slot. Read per composition from the live plugin list, so
    // a tab appears and disappears with the plugin behind it and with the
    // plugin's own panel switch (ADR 0015 § 6, T3).
    val destinations = navigation.visible(navigationPanels(state.plugins))
    // A destination that has just gone — switched off, disabled, removed —
    // leaves the rail looking at nothing, so the rail falls back to the
    // files it always has. Held as a value rather than written back:
    // composition reads state, it does not edit it.
    val shellOwned = tree.railPanel == RailPanel.FILES || tree.railPanel == RailPanel.SEARCH
    val active =
        if (shellOwned || destinations.any { it.key == tree.railPanel.key }) tree.railPanel else RailPanel.FILES
    Column(modifier) {
        // Exactly as tall as a pane's tab strip, so the divider below it
        // runs unbroken across the seam (owner-reported misalignment,
        // screenshot 2026-08-31).
        Row(
            Modifier.fillMaxWidth().height(PANE_HEADER_HEIGHT).padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            RailTab(Icons.Outlined.Description, "files", active == RailPanel.FILES) {
                tree.railPanel = RailPanel.FILES
            }
            RailTab(Icons.Default.Search, "search", active == RailPanel.SEARCH, shortcut = "Ctrl+F") {
                tree.railPanel = RailPanel.SEARCH
            }
            for (destination in destinations) {
                // The label is the contribution's own, verbatim: it is
                // what the plugin calls its destination, and the shell is
                // not the one to reword it.
                ContextMenuArea(items = { listOf(ContextMenuItem("Hide ${destination.label}") {
                    navigation.setVisible(destination.key, false)
                }) }) {
                    RailTab(navigationIcon(destination.key), destination.label, active.key == destination.key) {
                        tree.railPanel = RailPanel(destination.key)
                    }
                }
            }
        }
        HorizontalDivider(color = colours.outlineVariant)
        when (active.key) {
            RailPanel.SEARCH.key ->
                SearchPanel(state, searchFocus, onOpen, Modifier.weight(1f), initialSearchQuery)
            TASKS_RAIL_PANEL -> TasksPanel(state, onOpen, Modifier.weight(1f))
            // FILES, and — defensively — a key some contribution named
            // that this host has no panel for. A rail always shows
            // something, and the tree is what it shows.
            else -> {
                val collection = BUNDLED_PLUGIN_EXTENSIONS.pageCollections(state.plugins).singleOrNull { it.collection.id == active.key }
                if (collection != null) PageCollectionPanel(collection, state, onOpen, Modifier.weight(1f))
                else VaultTree(
                state,
                tree,
                onOpen,
                onCreateNote,
                Modifier.weight(1f),
                onOpenToSide = onOpenToSide,
                onRenamed = onRenamed,
                onDeleted = onDeleted,
            )
            }
        }
    }
}

/// A contributed destination's tab icon. Keyed rather than declared: an
/// icon name in `config` would be a string the shell has to resolve into
/// this same table, and the honest v1 position (§ 7) is that the
/// behaviour behind a slot id is Kotlin here. A key this host does not
/// know gets the plugin glyph, so an unrecognised destination still looks
/// like what it is.
internal fun navigationIcon(key: String): ImageVector = when (key) {
    TASKS_RAIL_PANEL -> Icons.Outlined.CheckBox
    PEOPLE_PANEL -> Icons.Outlined.People
    else -> Icons.Outlined.Extension
}

@Composable
private fun RailTab(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    shortcut: String? = null,
    onSelect: () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val name = label.replaceFirstChar { it.uppercaseChar() }
    Tooltip(if (shortcut == null) name else "$name ($shortcut)") {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) colours.primaryContainer else colours.surface)
                .clickable(onClick = onSelect),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(15.dp),
                tint = if (selected) colours.onPrimaryContainer else colours.onSurfaceVariant,
            )
        }
    }
}

/// The gestures that put a name into the tree, both answered by the same
/// inline row. Notes are not among them — they are created untitled.
private sealed interface PendingName {
    data class CreateFolder(val parent: String) : PendingName
    data class Rename(val relPath: String, val isDir: Boolean) : PendingName
}

private class TreeActions(
    val open: (String) -> Unit,
    val openToSide: (String) -> Unit,
    val newNote: (String) -> Unit,
    val newFolder: (String) -> Unit,
    val rename: (VaultEntry) -> Unit,
    val delete: (String) -> Unit,
    val pageCollections: List<FolderPageCollection>,
    val createPage: (FolderPageCollection) -> Unit,
) {
    fun pageMenuItems(folder: String) = pageCollections.filter { File(it.folder) == File(folder) }.map { collection ->
        ContextMenuItem(collection.binding.collection.folderCreateLabel!!) { createPage(collection.copy(folder = folder)) }
    }

    /// What the vault root offers: the same creation a folder's menu has.
    fun rootMenuItems() = listOf(
        ContextMenuItem("New note") { newNote("") },
        ContextMenuItem("New folder") { newFolder("") },
    ) + pageMenuItems("")
}

/// `onCreateNote` receives an untitled note that is already on disk;
/// opening it is the least a caller can do, so that is the default.
/// A rename and a deletion are reported as they happen: a tab over the
/// note has to follow it, or go with it (ADR 0014).
@Composable
fun VaultTree(
    state: GiluneState,
    tree: TreeState,
    onOpen: (String) -> Unit,
    onCreateNote: (String) -> Unit = onOpen,
    modifier: Modifier = Modifier,
    onOpenToSide: (String) -> Unit = {},
    onRenamed: (String, String) -> Unit = { _, _ -> },
    onDeleted: (String) -> Unit = {},
) {
    var pending by remember { mutableStateOf<PendingName?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var moveRevision by remember { mutableStateOf(0) }
    val folderCollections = rememberFolderPageCollections(state)
    var creatingPage by remember { mutableStateOf<FolderPageCollection?>(null) }
    val drag = remember(state.vaultDir) { VaultTreeDrag() }
    val list = rememberLazyListState()
    val entries = remember(state.vaultCursor.value, state.vaultDir, tree.expanded.toList(), moveRevision) {
        listVault(state.vaultDir, tree.expanded.toSet())
    }
    // The Captures folder is the inbox: the count is simply how many
    // notes still sit in it, shown quietly — never a nagging badge.
    val capturesCount = remember(state.vaultCursor.value, state.vaultDir, moveRevision) {
        File(state.vaultDir, "Captures").listFiles()?.count { it.name.endsWith(".md") } ?: 0
    }
    val colours = MaterialTheme.colorScheme

    fun reveal(parent: String) {
        if (parent.isNotEmpty() && parent !in tree.expanded) tree.expanded.add(parent)
    }

    // Focus leaves any naming row too, which answers it.
    val focusManager = LocalFocusManager.current
    fun chooseRoot() {
        tree.selectedDir = ""
        focusManager.clearFocus()
    }

    fun newFolder(parent: String = tree.selectedDir) {
        reveal(parent)
        pending = PendingName.CreateFolder(parent)
    }

    // Creating a note asks nothing: it exists the moment you ask for it,
    // and is renamed once there is something in it worth naming.
    fun newNote(parent: String = tree.selectedDir) {
        reveal(parent)
        onCreateNote(createUntitledNote(state.vaultDir, parent))
        moveRevision++
        state.sync(announce = false)
    }

    val actions = TreeActions(
        open = onOpen,
        openToSide = onOpenToSide,
        newNote = ::newNote,
        newFolder = ::newFolder,
        rename = { entry -> pending = PendingName.Rename(entry.relPath, entry.isDir) },
        delete = { relPath -> deleting = relPath },
        pageCollections = folderCollections,
        createPage = { creatingPage = it },
    )

    val dragModifier = treeDragModifier(drag, tree, list) { entry, directory ->
        try {
            synchronized(DocumentEditGate.monitor) {
                val moved = moveVaultEntry(state.vaultDir, entry.relPath, directory)
                reportMovedNotes(state.vaultDir, entry.relPath, moved, entry.isDir, onRenamed)
                tree.selectedFile = tree.selectedFile?.let { movedPath(it, entry.relPath, moved) }
                tree.selectedDir = movedPath(tree.selectedDir, entry.relPath, moved)
                val expanded = tree.expanded.map { movedPath(it, entry.relPath, moved) }
                tree.expanded.clear()
                tree.expanded.addAll(expanded)
                reveal(directory)
                moveRevision++
            }
            state.message.value = "Moved ${entry.relPath.substringAfterLast('/')} to ${directory.ifEmpty { "vault" }}"
            state.sync(announce = false)
        } catch (failure: Exception) {
            state.message.value = "Could not move ${entry.relPath.substringAfterLast('/')}: ${failure.message}"
            moveRevision++
        }
    }
    Column(modifier.testTag("vault-tree").then(dragModifier)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                ContextMenuArea(items = { actions.rootMenuItems() }) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(26.dp)
                            .testTag("vault-root-drop")
                            .onGloballyPositioned { drag.rootTarget = it.boundsInRoot() }
                            .background(if (drag.destination == "") colours.primaryContainer else colours.surface)
                            .clickable(onClick = ::chooseRoot),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            if (drag.source == null) "vault" else
                                drag.destination?.let { "Move to ${it.ifEmpty { "vault" }}" } ?: "Choose another folder",
                            fontSize = 11.sp,
                            color = colours.onSurfaceVariant,
                            maxLines = 1,
                            modifier = if (drag.source != null) Modifier.testTag("vault-drag-status") else Modifier,
                        )
                    }
                }
            }
            Tooltip("New folder") {
                IconButton(onClick = { newFolder() }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Outlined.CreateNewFolder,
                        contentDescription = "new folder",
                        modifier = Modifier.size(16.dp),
                        tint = colours.onSurfaceVariant,
                    )
                }
            }
            Tooltip("New note (Ctrl+N)") {
                IconButton(onClick = { newNote() }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.NoteAdd,
                        contentDescription = "new note",
                        modifier = Modifier.size(16.dp),
                        tint = colours.onSurfaceVariant,
                    )
                }
            }
        }

        // The space the rows leave is the vault itself: a click there makes
        // the root the folder new things go into, and its menu creates
        // there — otherwise a folder once clicked keeps every creation.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            ContextMenuArea(items = { actions.rootMenuItems() }) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(bottom = 6.dp).testTag("vault-tree-list")
                        .onGloballyPositioned { drag.viewport = it.boundsInRoot() }
                        .pointerInput(Unit) { detectTapGestures { chooseRoot() } },
                    state = list,
                ) {
                    val atRoot = (pending as? PendingName.CreateFolder)?.takeIf { it.parent.isEmpty() }
                    if (atRoot != null) {
                        item(key = "pending-root") {
                            InlineNameRow(state, atRoot, depth = 0, onDone = { pending = null; moveRevision++ })
                        }
                    }
                    items(entries, key = { it.relPath }) { entry ->
                        val renaming = (pending as? PendingName.Rename)?.takeIf { it.relPath == entry.relPath }
                        if (renaming != null) {
                            InlineNameRow(
                                state,
                                renaming,
                                depth = entry.depth,
                                onDone = { pending = null; moveRevision++ },
                                onRenamed = onRenamed,
                            )
                        } else {
                            TreeRow(entry, tree, state.vaultDir, capturesCount, actions, drag)
                        }
                        val inside = (pending as? PendingName.CreateFolder)
                            ?.takeIf { entry.isDir && it.parent == entry.relPath }
                        if (inside != null) {
                            InlineNameRow(state, inside, depth = entry.depth + 1, onDone = { pending = null; moveRevision++ })
                        }
                    }
                }
            }
        }
    }

    val doomed = deleting
    if (doomed != null) {
        DeleteDialog(state, doomed, onClose = { deleting = null; moveRevision++ }, onDeleted = onDeleted)
    }
    creatingPage?.let { target ->
        val binding = target.binding
        // An already-open form must disappear if its owner is disabled or loses
        // creation permission. The kernel also checks permission at commit.
        val owner = state.plugins.singleOrNull { it.pluginId == binding.pluginId }
        if (owner?.state == "enabled" && org.gilune.plugin.sdk.PAGE_CREATE_CAPABILITY in owner.granted) {
            PageCreationForm(binding, PagePreset(binding.collection.folderCreateLabel!!, emptyMap()), state,
                onOpen = { path ->
                    reveal(path.substringBeforeLast('/', ""))
                    moveRevision++
                    onOpen(path)
                }, initialFolder = target.folder, dismiss = { creatingPage = null })
        } else LaunchedEffect(target) { creatingPage = null }
    }
}

@Composable
private fun TreeRow(
    entry: VaultEntry,
    tree: TreeState,
    vaultDir: String,
    capturesCount: Int,
    actions: TreeActions,
    drag: VaultTreeDrag,
) {
    DisposableEffect(entry, drag) { onDispose { drag.rows.remove(entry) } }
    val rowModifier = Modifier.testTag("vault-entry:${entry.relPath}")
        .onGloballyPositioned { drag.rows[entry] = it.boundsInRoot() }
    if (entry.kind == VaultEntryKind.ATTACHMENT) {
        AttachmentRow(entry, vaultDir, rowModifier)
        return
    }
    val colours = MaterialTheme.colorScheme
    val selected = !entry.isDir && tree.selectedFile == entry.relPath
    val rowFocus = remember { FocusRequester() }
    val label = entry.relPath.substringAfterLast('/')
    val parentDir = if (entry.isDir) entry.relPath else entry.relPath.substringBeforeLast('/', "")

    ContextMenuArea(items = {
        buildList {
            // Only a note goes to the side: a pane holds documents, and a
            // folder is not one (ADR 0014, from M+1).
            if (!entry.isDir) {
                add(ContextMenuItem("Open to the side") { actions.openToSide(entry.relPath) })
            }
            add(ContextMenuItem("New note") { actions.newNote(parentDir) })
            add(ContextMenuItem("New folder") { actions.newFolder(parentDir) })
            if (entry.isDir) addAll(actions.pageMenuItems(entry.relPath))
            add(ContextMenuItem("Rename") { actions.rename(entry) })
            add(ContextMenuItem("Delete") { actions.delete(entry.relPath) })
            add(ContextMenuItem("Reveal in Explorer") { revealInExplorer(vaultDir, entry) })
        }
    }) {
        Row(
            rowModifier
                .semantics { this.selected = selected }
                .focusRequester(rowFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Delete) {
                        actions.delete(entry.relPath)
                        true
                    } else false
                }
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(when {
                    entry.isDir && drag.destination == entry.relPath -> colours.secondaryContainer
                    selected || drag.source == entry -> colours.primaryContainer
                    // The folder new notes and folders go into, quietly.
                    entry.isDir && tree.selectedDir == entry.relPath -> colours.surfaceVariant
                    else -> colours.surface
                })
                .clickable {
                    rowFocus.requestFocus()
                    if (entry.isDir) {
                        tree.selectedDir = if (tree.selectedDir == entry.relPath) "" else entry.relPath
                        tree.toggle(entry.relPath)
                    } else {
                        actions.open(entry.relPath)
                    }
                }
                .padding(start = (6 + entry.depth * 14).dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            when {
                entry.isDir -> Icon(
                    chevron(entry.relPath in tree.expanded),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colours.onSurfaceVariant,
                )
                // A note's own chevron folds its attachments away; the
                // name beside it still opens the note.
                entry.hasAttachments -> Tooltip(
                    if (entry.relPath in tree.expanded) "Hide attachments" else "Show attachments",
                ) {
                    Box(
                        Modifier.size(14.dp).clickable { tree.toggle(entry.relPath) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            chevron(entry.relPath in tree.expanded),
                            contentDescription = "attachments",
                            modifier = Modifier.size(14.dp),
                            tint = colours.onSurfaceVariant,
                        )
                    }
                }
                else -> Spacer(Modifier.width(14.dp))
            }
            Icon(
                if (entry.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(if (entry.isDir) 14.dp else 13.dp),
                tint = colours.onSurfaceVariant,
            )
            Text(
                if (entry.isDir) label else label.removeSuffix(".md"),
                fontSize = 13.sp,
                maxLines = 1,
                color = if (selected) colours.onPrimaryContainer else colours.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (entry.isDir && entry.relPath == "Captures" && capturesCount > 0) {
                Spacer(Modifier.weight(1f))
                Text("$capturesCount", fontSize = 11.sp, color = colours.onSurfaceVariant)
            }
        }
    }
}

/// An attachment is shown only while its note is expanded and is never
/// opened here — the editor edits notes, and the file itself belongs to
/// whichever application owns its type.
@Composable
private fun AttachmentRow(entry: VaultEntry, vaultDir: String, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    ContextMenuArea(items = {
        listOf(ContextMenuItem("Reveal in Explorer") { revealInExplorer(vaultDir, entry) })
    }) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(
                    start = (12 + entry.depth * 14).dp,
                    top = 2.dp,
                    bottom = 2.dp,
                    end = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                attachmentIcon(entry.relPath),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colours.outline,
            )
            Text(
                entry.relPath.substringAfterLast('/'),
                fontSize = 11.sp,
                maxLines = 1,
                color = colours.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

private val AUDIO_SUFFIXES = setOf("wav", "m4a", "mp3", "ogg", "opus", "flac")

private fun attachmentIcon(relPath: String): ImageVector =
    if (relPath.substringAfterLast('.', "").lowercase() in AUDIO_SUFFIXES) {
        Icons.Outlined.Audiotrack
    } else {
        Icons.Outlined.AttachFile
    }

private fun chevron(open: Boolean): ImageVector =
    if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight

/// The inline naming row, shared by both naming gestures: type, Enter
/// commits, Escape cancels. A rename starts with the current name
/// selected.
@Composable
private fun InlineNameRow(
    state: GiluneState,
    pending: PendingName,
    depth: Int,
    onDone: () -> Unit,
    onRenamed: (String, String) -> Unit = { _, _ -> },
) {
    val colours = MaterialTheme.colorScheme
    val folder = when (pending) {
        is PendingName.CreateFolder -> true
        is PendingName.Rename -> pending.isDir
    }
    var field by remember {
        val start = startingName(pending)
        mutableStateOf(TextFieldValue(start, TextRange(0, start.length)))
    }
    val focus = remember { FocusRequester() }
    // Each gesture ends the row once: Enter, then the blur its removal
    // causes, must not commit twice.
    val ended = remember { booleanArrayOf(false) }
    var hadFocus by remember { mutableStateOf(false) }

    fun end() {
        ended[0] = true
        onDone()
    }

    fun commit() {
        if (ended[0]) return
        val trimmed = field.text.trim().trimEnd('/')
        if (isSegment(trimmed)) {
            when (pending) {
                is PendingName.CreateFolder -> {
                    File(folderBase(state.vaultDir, pending.parent), trimmed).mkdirs()
                    state.sync(announce = false)
                }
                is PendingName.Rename -> renameEntry(state, pending, trimmed, onRenamed)
            }
        }
        end()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (12 + depth * 14).dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .border(1.dp, colours.outline, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            if (folder) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = colours.onSurfaceVariant,
        )
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                // Clicking away answers like Enter: a name is kept, and
                // no name means nothing is created.
                .onFocusChanged {
                    if (hadFocus && !it.isFocused) commit()
                    hadFocus = it.isFocused
                }
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Enter -> {
                            commit()
                            true
                        }
                        event.key == Key.Escape -> {
                            if (!ended[0]) end()
                            true
                        }
                        else -> false
                    }
                },
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = colours.onSurface),
            cursorBrush = SolidColor(colours.primary),
        )
        if (!folder) {
            Text(".md", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colours.outline)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

private fun startingName(pending: PendingName): String = when (pending) {
    is PendingName.CreateFolder -> ""
    is PendingName.Rename -> pending.relPath.substringAfterLast('/')
        .let { if (pending.isDir) it else it.removeSuffix(".md") }
}

/// A name the tree may write: one segment, never a path and never
/// traversal.
private fun isSegment(name: String): Boolean =
    name.isNotEmpty() && !name.contains("..") && !name.contains('/') && !name.contains('\\')

/// Rename in place, taking a note's attachments with it. The kernel
/// tracks a note by its front-matter id, so the note's own history
/// survives any name; an attachment is bound to its note only by sharing
/// its stem, so the stem has to move as one or the pairing is lost. The
/// note moves first: an attachment left behind is merely unlisted, while
/// a note that failed to move would have stolen its attachments' names.
/// Returns the new rel path, or null when the rename was refused; the
/// caller owns syncing and any open-editor bookkeeping.
fun renameNote(vaultDir: String, relPath: String, newName: String, isDir: Boolean = false): String? {
    if (!isSegment(newName.trim())) return null
    val name = newName.trim()
    val note = !isDir && relPath.endsWith(".md")
    val source = File(vaultDir, relPath)
    val target = File(source.parentFile, if (note && !name.endsWith(".md")) "$name.md" else name)
    if (target.name == source.name) return relPath
    val caseOnly = target.name.equals(source.name, ignoreCase = true)
    if ((!caseOnly && target.exists()) || !source.renameTo(target)) return null
    if (note) {
        val stem = source.name.removeSuffix(".md")
        val renamed = target.name.removeSuffix(".md")
        source.parentFile?.listFiles()?.filter { it.attachmentStem() == stem }?.forEach {
            it.renameTo(File(it.parentFile, renamed + it.name.removePrefix(stem)))
        }
    }
    return target.relativeTo(File(vaultDir)).invariantSeparatorsPath
}

/// The next free Obsidian-style name for a new note in `dir`: Untitled,
/// then Untitled 1, Untitled 2, …
fun untitledName(dir: File): String {
    if (!File(dir, "Untitled.md").exists()) return "Untitled"
    var n = 1
    while (File(dir, "Untitled $n.md").exists()) n++
    return "Untitled $n"
}

/// Write an empty untitled note into `parent` (vault-relative, empty for
/// the vault root) and return its vault-relative path. The name is taken
/// from what is already on disk, so a second new note never overwrites
/// the first. The caller owns syncing and opening it.
fun createUntitledNote(vaultDir: String, parent: String): String {
    val base = folderBase(vaultDir, parent)
    base.mkdirs()
    val file = File(base, untitledName(base) + ".md")
    if (!file.exists()) file.writeText("")
    return file.relativeTo(File(vaultDir)).invariantSeparatorsPath
}

/// The folders a vault starts with. They are offered, not enforced:
/// the caller creates them once per vault, so one the user deletes stays
/// deleted. A folder already there under any casing is left alone.
val DEFAULT_FOLDERS = listOf("Notes", "Tasks", "Captures")

fun createDefaultFolders(vaultDir: String) {
    val root = File(vaultDir)
    val present = root.listFiles()?.filter { it.isDirectory }?.map { it.name.lowercase() }.orEmpty()
    DEFAULT_FOLDERS.filter { it.lowercase() !in present }.forEach { File(root, it).mkdirs() }
}

private fun folderBase(vaultDir: String, parent: String): File =
    if (parent.isEmpty()) File(vaultDir) else File(vaultDir, parent)

/// A rename is reported note by note, never as the folder it may have
/// been: a tab knows its own note's path and nothing else. Attachments
/// move with their note's stem and no tab holds one, so they are not
/// reported.
private fun renameEntry(
    state: GiluneState,
    pending: PendingName.Rename,
    name: String,
    onRenamed: (String, String) -> Unit,
) {
    val renamed = renameNote(state.vaultDir, pending.relPath, name, pending.isDir)
    if (renamed == null) {
        state.message.value = "could not rename ${pending.relPath.substringAfterLast('/')}"
        return
    }
    if (renamed != pending.relPath) {
        reportMovedNotes(state.vaultDir, pending.relPath, renamed, pending.isDir, onRenamed)
    }
    state.sync(announce = false)
}

private fun reportMovedNotes(
    vaultDir: String,
    old: String,
    renamed: String,
    isDir: Boolean,
    onRenamed: (String, String) -> Unit,
) {
    if (isDir) {
        File(vaultDir, renamed).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".md") }
            .forEach { file ->
                val now = file.relativeTo(File(vaultDir)).invariantSeparatorsPath
                onRenamed(old + now.removePrefix(renamed), now)
            }
    } else {
        onRenamed(old, renamed)
    }
}

private fun revealInExplorer(vaultDir: String, entry: VaultEntry) = revealInExplorer(File(vaultDir, entry.relPath))

/// Show `target` selected in the platform's file manager. Explorer takes
/// the path as an argument of its own after `/select,`: Java quotes an
/// argument holding a space, and a quoted `"/select,C:\…\My note.md"` is
/// one Explorer cannot read, so it opened Documents instead.
fun revealInExplorer(target: File) {
    runCatching {
        if (System.getProperty("os.name").startsWith("Windows")) {
            ProcessBuilder(revealCommand(target)).start()
        } else {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(target)
            else desktop.open(target.absoluteFile.parentFile)
        }
    }
}

internal fun revealCommand(target: File): List<String> = listOf("explorer.exe", "/select,", target.absolutePath)

@Composable
private fun DeleteDialog(
    state: GiluneState,
    relPath: String,
    onClose: () -> Unit,
    onDeleted: (String) -> Unit = {},
) {
    val isDir = File(state.vaultDir, relPath).isDirectory
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Delete ${relPath.substringAfterLast('/')}?") },
        text = {
            Text(
                if (isDir) "The folder and the notes inside it are removed from the vault."
                else "The note is removed from the vault. Its revisions and its original " +
                    "capture are kept — deleting a projection never destroys history.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(onClick = {
                deleteEntry(state, relPath, onDeleted)
                onClose()
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/// Delete a projected note and answer the tombstone proposal the deletion
/// raises. The object's identity is read before the file goes, because
/// afterwards there is nothing left to read it from — and so are the
/// note paths the deletion takes with it, so a tab over any of them can
/// be told (a folder deletion is reported note by note).
private fun deleteEntry(state: GiluneState, relPath: String, onDeleted: (String) -> Unit = {}) {
    if (state.gilune.value == null) return
    val file = File(state.vaultDir, relPath)
    val notes = if (file.isDirectory) {
        file.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.toList()
    } else {
        listOf(file)
    }
    val identities = notes.mapNotNull(::noteIdentity)
    val removed = notes
        .filter { it.name.endsWith(".md") }
        .map { it.relativeTo(File(state.vaultDir)).invariantSeparatorsPath }
    if (!file.deleteRecursively()) {
        state.message.value = "could not delete $relPath"
        return
    }
    removed.forEach(onDeleted)
    state.pluginAction("deleted $relPath") {
        it.reconcile()
        val open = it.openProposals().filter { proposal -> proposal.objectId in identities }
        for (proposal in open) it.resolveTombstone(proposal.proposalId, true)
        it.project()
    }
}

private fun noteIdentity(file: File): String? =
    runCatching { splitNote(file.readText()) }
        .getOrNull()
        ?.frontMatter
        ?.firstOrNull { it.key == "gilune-id" }
        ?.value
