// Starting folders are plugins' (owner, 2026-10-19): a folder a vault
// begins with is one some plugin uses — Notes and Captures the vault's own,
// People the people plugin's — declared in the `vault.folders` slot rather
// than listed by the shell. A plugin with no folder declares none.

package org.gilune.app

import org.gilune.plugin.sdk.Slots
import org.gilune.plugin.sdk.jsonString
import uniffi.gilune_ffi.PluginInfo
import java.io.File

/** Every enabled plugin's declared starting folders, in declaration order,
 * each a vault-relative path. A malformed or escaping entry is dropped. */
fun declaredFolders(plugins: List<PluginInfo>): List<String> =
    plugins.filter { it.state == "enabled" }
        .flatMap { plugin -> plugin.contributions.filter { it.slot == Slots.VAULT_FOLDERS } }
        .mapNotNull { jsonString(it.config, "folder")?.trim()?.trim('/') }
        .filter { it.isNotEmpty() && it.split('/').none { part -> part.isEmpty() || part == "." || part == ".." } }
        .distinct()

/** Creates each declared folder the vault has not been given before on
 * this machine. `given`/`remember` are the record of that, so a folder the
 * user deletes stays deleted and a plugin installed later still gets its
 * own. A folder already present in any casing is left alone and counted as
 * given. Returns the folders made. */
fun createDeclaredFolders(
    vaultDir: String,
    folders: List<String>,
    given: (String) -> Boolean,
    remember: (String) -> Unit,
): List<String> {
    val root = File(vaultDir)
    val made = mutableListOf<String>()
    for (folder in folders) {
        if (given(folder)) continue
        val parent = File(root, folder).parentFile ?: root
        val present = parent.listFiles()?.any { it.isDirectory && it.name.equals(File(folder).name, ignoreCase = true) } == true
        if (present || File(root, folder).mkdirs()) {
            remember(folder)
            if (!present) made += folder
        }
    }
    return made
}
