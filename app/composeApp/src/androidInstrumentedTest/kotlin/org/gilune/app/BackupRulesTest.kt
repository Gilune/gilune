// What may leave this phone in a backup (ADR 0019 § 4).
//
// **The defect this pins.** The manifest declared no backup rules at all,
// so Android's default carried everything in the app's private storage —
// including `filesDir/sync`, which holds the device identity, the pairing
// roster and the remembered on-or-off. § 4 keeps the identity outside the
// vault precisely so a restored copy is a new device; the default backup
// undid that through the back door.
//
// It was also broken in the immediate sense, which is how it was found: the
// identity file is sealed by an AndroidKeyStore key and Keystore keys are
// not backed up, so the file arrived on the restored phone and nothing on
// it could open the file. Enrolment then failed for good.
//
// A rules file is XML that only the platform reads, so what a test can do
// is read it too: that it parses, that it excludes the directory, and that
// it does not quietly exclude the vault as well.

package org.gilune.app

import android.content.res.XmlResourceParser
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupRulesTest {

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    /// One `<exclude>` as the platform would read it: the section it is
    /// in, its domain and its path.
    private data class Exclusion(val section: String, val domain: String, val path: String)

    private fun exclusions(id: Int): List<Exclusion> {
        val found = mutableListOf<Exclusion>()
        val parser: XmlResourceParser = resources.getXml(id)
        var section = "root"
        parser.use {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (parser.name == "exclude") {
                        found += Exclusion(section, parser.attribute("domain"), parser.attribute("path"))
                    } else {
                        section = parser.name
                    }
                }
                event = parser.next()
            }
        }
        return found
    }

    @Test
    fun theApiThirtyOneRulesExcludeTheSyncDirectoryFromBackupAndFromDeviceTransfer() {
        val rules = exclusions(R.xml.data_extraction_rules)
        // Both halves. Cloud backup is the restored-phone case; device
        // transfer is phone-to-phone, and an identity that travelled that
        // way would be two live devices sharing one sequence space, which
        // is worse than the one this was found through.
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertTrue(
                rules.any { it.section == section && it.domain == "file" && it.path == "sync" },
                "$section does not exclude filesDir/sync: $rules",
            )
        }
    }

    @Test
    fun theOlderRulesSayTheSameThingBecauseMinSdkIsBelowThirtyOne() {
        // minSdk 29, so a phone on Android 11 reads `fullBackupContent`
        // and never looks at the file above. One of the two shipping
        // alone would be an exclusion that holds on some phones.
        val rules = exclusions(R.xml.backup_rules)
        assertTrue(
            rules.any { it.domain == "file" && it.path == "sync" },
            "the pre-31 rules do not exclude filesDir/sync: $rules",
        )
    }

    @Test
    fun theVaultItselfIsNotExcludedBecauseARestoredVaultIsTheWholePoint() {
        // § 4's third row: a vault that arrives with no identity beside it
        // mints a new identity and a new device id and starts a fresh
        // sequence space. The person's notes come back; the device is new.
        // Excluding the vault would have "fixed" the identity problem by
        // throwing the notes away.
        val everything = exclusions(R.xml.data_extraction_rules) + exclusions(R.xml.backup_rules)
        assertEquals(
            emptyList(),
            everything.filter { it.path != "sync" },
            "nothing but the device-local sync state is held back",
        )
    }

    /// Backup rules take **un-namespaced** attributes — `<exclude
    /// domain="file" path="sync"/>`, with no `android:` prefix — unlike
    /// almost everything else in `res/xml`. Asking for the android
    /// namespace here answers null, which would make every assertion below
    /// pass against nothing. The android namespace is tried second so a
    /// file written the other way is read rather than silently empty.
    private fun XmlResourceParser.attribute(name: String): String =
        getAttributeValue(null, name) ?: getAttributeValue(ANDROID_NS, name) ?: ""

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
