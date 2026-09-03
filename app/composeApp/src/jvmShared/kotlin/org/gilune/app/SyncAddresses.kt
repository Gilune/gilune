// Which addresses a settings screen puts on the screen (ADR 0019 § 1).
//
// **One rule, both shells**, for the reason `SyncAlerts` gives about
// wording: a machine is reachable at several addresses — a Wi-Fi card, a
// hypervisor's host-only adapter, a VPN — and it cannot know which one the
// other device shares. The kernel therefore publishes every candidate in
// its own ranking and `listening_on` is merely the first of them. A shell
// that showed only that first one was the difference between somebody
// reading the right address off the screen and giving up, and a shell that
// re-derived the list its own way would be a second answer to a question
// that already has one.
//
// So the fallback lives here once rather than once per shell, and each
// screen decides only how to draw the rows.

package org.gilune.app

import uniffi.gilune_ffi.SyncStatus

/// Every address this device answers on, best first — the facts under
/// "This device" on the desktop, and the line under the switch on the
/// phone.
///
/// `listening_on` is the best-ranked of `listening_addresses` by contract,
/// and the list is empty exactly when there is nothing to say. The single
/// field is kept as the fallback rather than assumed away, so a kernel that
/// could name only one still puts that one on the screen.
///
/// A device that is not listening shows no row at all: an address it is not
/// answering on would be a lie somebody could waste an afternoon on. Blank
/// is dropped for the same reason.
fun deviceAddresses(status: SyncStatus): List<String> {
    val published = status.listeningAddresses.filter { it.isNotBlank() }
    return published.ifEmpty { listOfNotNull(status.listeningOn).filter { it.isNotBlank() } }
}
