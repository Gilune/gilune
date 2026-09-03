// What Settings ▸ Sync decides, without a device to decide it on
// (ADR 0019 § 1, § 8, § 10).
//
// M5b shipped this screen's helpers written to be testable and untested;
// M5c adds four more and pins the lot. Every one of them is a wording
// decision, and a wording decision that nothing checks is one that drifts:
// the halting sentences in particular have to keep saying that nothing was
// lost, because that is the only thing standing between a person and the
// conclusion that sync broke their notes.

package org.gilune.app

import uniffi.gilune_ffi.PeerInfo
import uniffi.gilune_ffi.SyncStatus
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun peer(
    name: String = "Davids laptop",
    state: String = "paired",
    address: String = "192.168.1.20:51837",
    staticPub: String = "aa".repeat(32),
) = PeerInfo(
    staticPub = staticPub,
    fingerprint = "ABCD-EFGH",
    deviceId = "device-1",
    name = name,
    address = address,
    state = state,
    pairedMs = 0,
    lastSeenMs = 0,
)

internal fun syncStatus(
    enabled: Boolean = true,
    advertising: Boolean = true,
    discovery: String = DISCOVERY_NAME,
    keyProtection: String = KEY_PROTECTION_KEYSTORE,
    listeningOn: String? = "192.168.1.20:51837",
    /// Every address the phone answers on. Defaults to whatever
    /// `listeningOn` is, because that is the contract — the single field is
    /// the first of the list — so a scenario that does not care about
    /// addresses keeps meaning exactly what it meant before the list
    /// existed.
    listeningAddresses: List<String> = listOfNotNull(listeningOn),
    peers: List<PeerInfo> = emptyList(),
    /// The persisted choice, which is a different fact from `enabled` and
    /// the only one a fresh process has. It follows `enabled` unless a
    /// scenario asks them apart — a phone that came back up and could not
    /// bind, or a background worker that never binds at all.
    wanted: Boolean = enabled,
) = SyncStatus(
    enabled = enabled,
    deviceId = "device-0",
    deviceName = "This phone",
    fingerprint = "WXYZ-1234",
    identityPub = "bb".repeat(32),
    keyProtection = keyProtection,
    enrolment = "continued",
    enrolmentNotice = null,
    listeningOn = listeningOn,
    listeningAddresses = listeningAddresses,
    wanted = wanted,
    advertising = advertising,
    discovery = discovery,
    pairing = null,
    peers = peers,
)

class SyncScreenTest {

    // ------------------------------------------------------- alerts (§ 8)
    //
    // The sentences themselves moved to `SyncAlerts.kt` and are pinned in
    // `SyncAlertsTest` (jvmSharedTest, so both suites run them). What is
    // left here is the one thing that is this shell's own: it asks for the
    // phone's noun, and it never shows a person the kernel's identifiers.

    @Test
    fun thePhoneAsksForItsOwnNounAndKeepsTheKernelsIdentifiersOffTheScreen() {
        for (kind in ALERT_KINDS) {
            val sentence = alertSentence(kind, "device-1", "seq 7 after 5", PHONE_ALERT_WORDS)
            assertFalse(sentence.contains("device-1"), "nor the subject: $sentence")
            assertFalse(sentence.contains("seq 7 after 5"), "nor the detail: $sentence")
            assertFalse(sentence.contains("this device"), "a phone is a phone: $sentence")
        }
        assertTrue(
            alertSentence(SYNC_UNSUPPORTED_VERSION, "", "", PHONE_ALERT_WORDS)
                .contains("this phone"),
        )
    }

    // ---------------------------------------------------- discovery (§ 1)

    @Test
    fun beingReachableAndBeingFindableAreSaidSeparately() {
        val findable = discoverySentence(enabled = true, advertising = true, discovery = DISCOVERY_NAME)
        assertTrue(findable.contains("announcing itself"), findable)

        // The case the ADR splits the two flags for: TCP bound, UDP 5353
        // not. A person is told they are reachable and not findable, and
        // what to do instead.
        val reachableOnly = discoverySentence(enabled = true, advertising = false, discovery = DISCOVERY_NAME)
        assertTrue(reachableOnly.contains("can reach this phone"), reachableOnly)
        assertTrue(reachableOnly.contains("not"), reachableOnly)
        assertTrue(reachableOnly.contains("address"), "it names the way round: $reachableOnly")

        val off = discoverySentence(enabled = false, advertising = false, discovery = DISCOVERY_NAME)
        assertTrue(off.contains("sync is off"), off)
    }

    /// A host with no adapter is a supported configuration, not a broken
    /// one, and the wording has to agree with that.
    @Test
    fun noAdapterIsSaidAsWorkingDifferentlyRatherThanAsBroken() {
        for (route in listOf("none", "")) {
            val sentence = discoverySentence(enabled = true, advertising = false, discovery = route)
            assertTrue(sentence.contains("That works"), sentence)
        }
    }

    @Test
    fun aFoundDeviceSaysWhatItIsToThisPhone() {
        assertEquals("not paired yet", foundDeviceState("unknown", compatible = true))
        assertEquals("already paired with this phone", foundDeviceState("paired", compatible = true))
        assertTrue(foundDeviceState("revoked", compatible = true).startsWith("revoked"))
        // Incompatibility outranks roster state: a device running another
        // version cannot be talked to whatever the roster says.
        assertTrue(foundDeviceState("paired", compatible = false).contains("cannot talk"))
    }

    @Test
    fun nothingFoundNamesTheCauseRatherThanAskingForPatience() {
        assertTrue(NOTHING_FOUND.contains("guest"), NOTHING_FOUND)
        assertTrue(NOTHING_FOUND.contains("waiting will not"), NOTHING_FOUND)
    }

    // -------------------------------------------------- scheduling (§ 10)

    @Test
    fun aForegroundSyncNeedsBothSyncOnAndSomebodyToSyncWith() {
        assertFalse(shouldSyncOnForeground(null))
        assertFalse(shouldSyncOnForeground(syncStatus(enabled = true, peers = emptyList())))
        assertFalse(shouldSyncOnForeground(syncStatus(enabled = false, peers = listOf(peer()))))
        assertFalse(
            shouldSyncOnForeground(syncStatus(enabled = true, peers = listOf(peer(state = "revoked")))),
            "a revoked peer is nobody to sync with",
        )
        assertTrue(shouldSyncOnForeground(syncStatus(enabled = true, peers = listOf(peer()))))
    }

    // ------------------------------------- the M5b helpers, pinned at last

    @Test
    fun theSummaryCountsOnlyPairedDevices() {
        assertEquals("not set up on this device", syncSummary(null))
        assertEquals("off · no paired devices", syncSummary(syncStatus(enabled = false)))
        assertEquals("on · 1 paired device", syncSummary(syncStatus(peers = listOf(peer()))))
        assertEquals(
            "on · 2 paired devices",
            syncSummary(syncStatus(peers = listOf(peer(), peer(name = "desk"), peer(state = "revoked")))),
        )
    }

    /// The `none` case is the one the ADR asks to be stated rather than
    /// hidden: the fallback is allowed, and a person is told the key is
    /// sitting there unwrapped.
    @Test
    fun keyProtectionSaysWhatActuallyProtectedTheKey() {
        val keystore = keyProtectionSentence(KEY_PROTECTION_KEYSTORE)
        assertTrue(keystore.contains("Keystore"), keystore)
        assertTrue(keystore.contains("does not protect it from Gilune itself"), keystore)

        val none = keyProtectionSentence("none")
        assertTrue(none.contains("Nothing is protecting"), none)
        assertTrue(none.contains("unwrapped"), none)

        // An adapter this build has never heard of is named, not hidden.
        assertTrue(keyProtectionSentence("dpapi-user").contains("dpapi-user"))
    }

    @Test
    fun aPairingCodeIsSixDigitsOrLess() {
        assertEquals("123456", normalisedPairingCode("123456"))
        assertEquals("123456", normalisedPairingCode("12 34-56"))
        assertEquals("123456", normalisedPairingCode("1234567890"))
        assertEquals("", normalisedPairingCode("hello"))
        assertEquals("42", normalisedPairingCode("4a2b"))
    }

    /// The screen draws the shared message line, filtered exactly as the
    /// composer filters it: the vault path and the opening line are a
    /// developer's facts, not an outcome.
    @Test
    fun theNoticeDropsWhatIsNotAnOutcome() {
        assertNull(syncNotice(""))
        assertNull(syncNotice("   "))
        assertNull(syncNotice("vault: /data/user/0/org.gilune.app/files/vault"))
        assertNull(syncNotice("opening vault…"))
        assertEquals("paired", syncNotice("paired"))
        assertEquals("device sync: 3 received, 0 sent", syncNotice("device sync: 3 received, 0 sent"))

        // The unreadable-identity state drops the line too, because the
        // screen says that one properly beside the button that fixes it.
        // It is the **typed** answer that decides, not the words: the same
        // sentence is kept when the flag is false and dropped when it is
        // true, which is the whole of this round's fix.
        val said = "sync unavailable: sealed elsewhere"
        assertEquals(said, syncNotice(said, identityUnreadable = false))
        assertNull(syncNotice(said, identityUnreadable = true))
    }

    // ----------------------------------------- the adapter's pure corners

    @Test
    fun aTxtRecordIsReadWithoutTrustingIt() {
        assertEquals("", txtString(null, TXT_VERSION))
        assertEquals("", txtString(emptyMap(), TXT_VERSION))
        assertEquals("", txtString(mapOf(TXT_VERSION to null), TXT_VERSION))
        assertEquals(
            SYNC_PROTOCOL,
            txtString(mapOf(TXT_VERSION to " $SYNC_PROTOCOL ".toByteArray()), TXT_VERSION),
        )
        // A record long enough to fill a settings screen is capped rather
        // than drawn.
        val long = txtString(mapOf(TXT_FINGERPRINT to "x".repeat(4000).toByteArray()), TXT_FINGERPRINT)
        assertEquals(255, long.length)
    }

    @Test
    fun theDialledAddressPrefersIpv4AndBracketsIpv6() {
        val v4 = InetAddress.getByName("192.168.1.20")
        val v6 = InetAddress.getByName("2001:db8::1")
        val loopback = InetAddress.getByName("127.0.0.1")
        val linkLocal = InetAddress.getByName("fe80::1")

        assertEquals(v4, preferredHost(listOf(v6, v4)))
        assertEquals(v6, preferredHost(listOf(loopback, v6)))
        assertNull(preferredHost(listOf(loopback, linkLocal)), "neither is dialable from here")
        assertNull(preferredHost(emptyList()))

        assertEquals("192.168.1.20:51837", dialAddress(v4, 51837))
        // Bracketed, because that is what makes an IPv6 literal parse
        // beside a port at all. The literal's own spelling is the
        // platform's business — compressed on Android, expanded on the
        // desktop JVM — so only the shape is pinned.
        val dialled = dialAddress(v6, 51837)!!
        assertTrue(dialled.startsWith("["), dialled)
        assertTrue(dialled.endsWith("]:51837"), dialled)
        assertNull(dialAddress(null, 51837))
        assertNull(dialAddress(v4, 0))
        assertNull(dialAddress(v4, 70000))
    }
}
