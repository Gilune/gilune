// Which addresses a settings screen puts on the screen (ADR 0019 § 1).
//
// `deviceAddresses` is shared between the shells, so its rule is pinned
// here rather than beside either of them: `jvmSharedTest` runs in the
// desktop suite and on the device, which is the same reason `SyncAlertsTest`
// lives here.
//
// The rule is small and every part of it is a decision:
//
//  - **Every candidate, in the kernel's order.** A machine on Wi-Fi, a
//    hypervisor bridge and a VPN cannot know which of them the other device
//    shares. The kernel ranks them — same segment first — and a shell that
//    re-sorted or de-duplicated would be a second answer to a question that
//    already has one.
//  - **`listening_on` is the fallback, not the source.** It is the first of
//    `listening_addresses` by contract, and it is consulted only when the
//    list is empty, so a kernel that could name one still puts that one on
//    the screen.
//  - **Nothing at all while it is not listening.** An address a device does
//    not answer on is worse than no address: somebody will type it.

package org.gilune.app

import uniffi.gilune_ffi.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncAddressesTest {

    private fun status(
        listeningOn: String?,
        listeningAddresses: List<String> = listOfNotNull(listeningOn),
    ) = SyncStatus(
        enabled = listeningOn != null,
        deviceId = "device-0",
        deviceName = "A machine",
        fingerprint = "WXYZ-1234",
        identityPub = "",
        keyProtection = "none",
        enrolment = "enrolled",
        enrolmentNotice = null,
        listeningOn = listeningOn,
        listeningAddresses = listeningAddresses,
        wanted = listeningOn != null,
        advertising = false,
        discovery = "none",
        pairing = null,
        peers = emptyList(),
    )

    @Test
    fun everyAddressIsShownInTheOrderTheKernelPublishedThem() {
        val several = listOf("192.168.1.77:51837", "172.17.0.1:51837", "10.8.0.3:51837")
        assertEquals(
            several,
            deviceAddresses(status("192.168.1.77:51837", several)),
            "the screen re-ordered or dropped one of the kernel's candidates",
        )
    }

    @Test
    fun aKernelThatNamedOnlyOneStillPutsThatOneOnTheScreen() {
        assertEquals(
            listOf("192.168.1.77:51837"),
            deviceAddresses(status("192.168.1.77:51837", emptyList())),
        )
    }

    @Test
    fun aDeviceThatIsNotListeningShowsNoAddressAtAll() {
        // The second-order case: an address nobody answers on is worse than
        // none, because somebody will read it out and type it in.
        assertEquals(emptyList(), deviceAddresses(status(null, emptyList())))
        // And blanks are dropped rather than drawn as an empty row.
        assertEquals(emptyList(), deviceAddresses(status(null, listOf("", "  "))))
        assertEquals(emptyList(), deviceAddresses(status("   ", emptyList())))
    }
}
