// Device-local facts on the phone (ADR 0017 § 6). `Prefs` does not port
// — `java.util.prefs` is unusable on Android — so the theme, the idle
// timer and the first-run notice live in DataStore, which the ADR names.
//
// Reads are a flow with a stated default, deliberately: nothing here is
// on the path to the first frame, so the composer draws with the default
// and corrects itself a moment later rather than waiting on a disk read
// (§ 1).

package org.gilune.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.giluneDataStore: DataStore<Preferences> by preferencesDataStore(name = "gilune")

private val THEME = stringPreferencesKey("theme")
private val IDLE_MINUTES = intPreferencesKey("composerIdleMinutes")
private val VAULT_NOTICE_SEEN = booleanPreferencesKey("vaultNoticeSeen")

class MobilePrefs(context: Context) {
    private val store = context.applicationContext.giluneDataStore

    /// A corrupt or unreadable store reads as defaults rather than
    /// crashing the app: a preference must never be what stops a capture.
    private fun <T> read(map: (Preferences) -> T): Flow<T> =
        store.data.catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }.map(map)

    val theme: Flow<ThemeChoice> = read { prefs ->
        prefs[THEME]?.let { stored -> ThemeChoice.entries.firstOrNull { it.name == stored } } ?: ThemeChoice.System
    }

    val idleTimeout: Flow<IdleTimeout> = read { prefs ->
        prefs[IDLE_MINUTES]?.let(IdleTimeout::fromStored) ?: IdleTimeout.Default
    }

    /// Whether the vault truth has been stated (§ 5: "first run says this
    /// plainly rather than letting the user infer the desktop promise").
    val vaultNoticeSeen: Flow<Boolean> = read { prefs -> prefs[VAULT_NOTICE_SEEN] ?: false }

    suspend fun setTheme(choice: ThemeChoice) {
        store.edit { it[THEME] = choice.name }
    }

    suspend fun setIdleTimeout(timeout: IdleTimeout) {
        store.edit { it[IDLE_MINUTES] = timeout.stored }
    }

    suspend fun markVaultNoticeSeen() {
        store.edit { it[VAULT_NOTICE_SEEN] = true }
    }
}

private fun emptyPreferences(): Preferences = androidx.datastore.preferences.core.emptyPreferences()
