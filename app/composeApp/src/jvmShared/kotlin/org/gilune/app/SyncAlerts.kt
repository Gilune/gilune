// What a standing integrity condition says to a person (ADR 0019 § 8).
//
// **One table, both shells.** These sentences shipped twice — once in
// `SyncSettings.kt` and once in `SyncScreen.kt` — and two copies of a
// user-visible contract is one copy that quietly goes wrong: a kind added
// to the kernel would have been worded on the desktop and left silent on
// the phone, which is exactly the "a halt nobody is told about" failure
// § 8 exists to prevent. So the wording lives here, beside `Palette` and
// `Agenda`, and each shell supplies only what actually differs.
//
// Two things differ, and they are both parameters rather than two tables:
//
//  - **What the machine is called.** A phone is a phone and a desktop is a
//    device; every other word is the same.
//  - **Whether the kernel's own identifiers are carried.** The desktop
//    shows the subject and detail in an aside, because a person at a
//    keyboard comparing two machines can use an operation id. The phone
//    does not, because a device id in a settings screen on a phone is
//    something to squint at rather than something to act on. The
//    **unknown-kind fallback carries them on both**, since the kernel's
//    words are the only words there are.
//
// Three rules hold for every sentence, and each of them is tested:
//
//  - **The raw kind string never reaches a screen.** It is the kernel's
//    name for a policy, not something to show anybody.
//  - **Every sentence answers what stopped, why, and what happens next**,
//    and the "next" is always the same promise, because it is always the
//    same behaviour: applying resumes from the frontier once the condition
//    clears. Without that each of these reads as a loss, which none of
//    them is.
//  - **Not every condition is damage**, and the ones that are not say so
//    in their first line rather than in their last. `sync-unknown-op-kind`
//    is a newer version's work kept byte for byte and forwarded;
//    `sync-awaiting-referent` is two devices meeting in the wrong order.
//    Painting those as faults sends somebody hunting for a problem that
//    does not exist.

package org.gilune.app

// ---------------------------------------------------------------------
// The kinds, spelled as the kernel raises them (`alerts.rs`). Duplicated
// here rather than exported over the FFI, which is the same shape the
// event names already live with; the tests below pin every one, so a kind
// the kernel adds and this table has never heard of is a red test rather
// than a silent fallback in front of a user.
// ---------------------------------------------------------------------

const val SYNC_GAP = "sync-gap"
const val SYNC_CORRUPT_OPERATION = "sync-corrupt-operation"
const val SYNC_UNSUPPORTED_VERSION = "sync-unsupported-version"
const val SYNC_UNKNOWN_OP_KIND = "sync-unknown-op-kind"
const val SYNC_LIMIT_EXCEEDED = "sync-limit-exceeded"
const val SYNC_BLOB_DIGEST_MISMATCH = "sync-blob-digest-mismatch"
const val SYNC_REVOKED_DEVICE = "sync-revoked-device"

/// A change refers to something whose own history has not arrived yet.
/// **Not damage and not a halt**: the two devices met in the wrong order,
/// the stream carries on, and the condition clears itself at the next
/// exchange. It is surfaced at all because a person watching a note fail
/// to appear deserves to know why.
const val SYNC_AWAITING_REFERENT = "sync-awaiting-referent"

/// A note another device deleted had been edited here, so the edited file
/// was kept and is offered as an import. **Not damage either** — it is
/// rule 6 working, preserving both intentions rather than letting a delete
/// win a race — but it is the one condition in this table that asks a
/// person to make a decision.
///
/// Note the prefix: this one is raised by the vault rather than by a
/// stream, so it is `vault-` and not `sync-`. Its `subject` is an object
/// id and its `detail` names the file, which is why the desktop's aside
/// earns its place here more than anywhere else in the table.
const val VAULT_DELETED_NOTE_EDITED = "vault-deleted-note-edited"

/// Every kind this build has words of its own for.
val ALERT_KINDS: List<String> = listOf(
    SYNC_GAP,
    SYNC_CORRUPT_OPERATION,
    SYNC_UNSUPPORTED_VERSION,
    SYNC_UNKNOWN_OP_KIND,
    SYNC_LIMIT_EXCEEDED,
    SYNC_BLOB_DIGEST_MISMATCH,
    SYNC_REVOKED_DEVICE,
    SYNC_AWAITING_REFERENT,
    VAULT_DELETED_NOTE_EDITED,
)

/// The conditions that are **not** damage: nothing stopped, nothing was
/// lost, and nothing needs fixing. They are surfaced because silence about
/// them would be its own confusion, not because anything is wrong.
///
/// A kind this build has never heard of is deliberately **not** in here: an
/// unrecognised condition is treated as a halt, because assuming a newer
/// kernel's condition is harmless is the one assumption that could hide a
/// real one.
val NON_DAMAGE_ALERT_KINDS: Set<String> = setOf(
    SYNC_UNKNOWN_OP_KIND,
    SYNC_AWAITING_REFERENT,
    VAULT_DELETED_NOTE_EDITED,
)

fun alertIsDamage(kind: String): Boolean = kind !in NON_DAMAGE_ALERT_KINDS

/// How one shell names itself and what it is willing to show.
///
/// Held as a value rather than as two functions so that adding a third
/// shell — a headless agent's status page, say — is a constant and not a
/// third copy of the table.
data class AlertWords(
    /// What this shell calls the machine it runs on, mid-sentence.
    val thisDevice: String,
    /// Whether a known condition's sentence carries the kernel's `subject`
    /// and `detail` in an aside.
    val identify: Boolean,
)

val DESKTOP_ALERT_WORDS = AlertWords(thisDevice = "this device", identify = true)
val PHONE_ALERT_WORDS = AlertWords(thisDevice = "this phone", identify = false)

/// The alert's subject and detail, carried without claiming what kind of
/// thing they are — a device id, an operation id and a blob id all arrive
/// in the same field, and a sentence that guessed would sooner or later
/// guess wrong.
private fun aside(words: AlertWords, subject: String, detail: String): String {
    if (!words.identify) return ""
    val kept = listOf(subject, detail).filter { it.isNotBlank() }
    return if (kept.isEmpty()) "" else kept.joinToString(", ", prefix = " (", postfix = ")")
}

/// The promise every sentence ends on, because it is the behaviour every
/// condition actually has: the frontier is where applying resumes, so
/// nothing here is a loss and none of these sentences may read as one.
private const val RESUMES = "Gilune picks up where it left off"

/// What a standing alert says, in user words.
fun alertSentence(
    kind: String,
    subject: String,
    detail: String,
    words: AlertWords,
): String {
    val about = aside(words, subject, detail)
    return when (kind) {
        SYNC_GAP ->
            "Changes from another device arrived with one of them missing$about, so " +
                "nothing after the gap has been taken in: applying the rest would " +
                "record a history that never happened. Everything before the gap is " +
                "already here, and $RESUMES as soon as the missing change arrives."

        SYNC_CORRUPT_OPERATION ->
            "A change did not arrive as it was written$about, so it has been left " +
                "where it is and nothing after it has been taken in — a damaged " +
                "change is not something to guess at. $RESUMES as soon as an " +
                "undamaged copy arrives."

        SYNC_UNSUPPORTED_VERSION ->
            "Changes from another device are written in a version of Gilune's sync " +
                "format ${words.thisDevice} does not understand$about, so none of " +
                "them have been taken in. Bringing both devices to the same version " +
                "is what clears it, and $RESUMES when it does."

        SYNC_UNKNOWN_OP_KIND ->
            "Something a newer version of Gilune wrote came through$about, and " +
                "${words.thisDevice} does not know how to read that kind of entry. " +
                "Nothing stopped and nothing is lost: it is kept exactly as it " +
                "arrived and passed on to your other devices unread, and $RESUMES " +
                "with it once ${words.thisDevice} is running a version that " +
                "understands it."

        SYNC_LIMIT_EXCEEDED ->
            "One exchange reached the limit on how much a device may send at " +
                "once$about, so it was ended tidily rather than cut off part way " +
                "through a change. Nothing is half applied, and $RESUMES at the " +
                "next sync."

        SYNC_BLOB_DIGEST_MISMATCH ->
            "A recording or attachment did not match the fingerprint it was sent " +
                "with$about, so the bytes were thrown away rather than kept as if " +
                "they were right. Nothing else stopped, and $RESUMES when that file " +
                "arrives intact."

        SYNC_REVOKED_DEVICE ->
            "Changes first made on a device you revoked were offered by another " +
                "device$about, and ${words.thisDevice} refused them. What that " +
                "device sent before you revoked it is still in your vault — revoking " +
                "protects what comes next, it does not undo. $RESUMES with every " +
                "device you have not revoked."

        // Two devices met in the wrong order. Deliberately worded as a
        // wait rather than as a fault: nothing stopped, the stream carried
        // on, and the condition clears itself.
        SYNC_AWAITING_REFERENT ->
            "Something from another device points at a note whose own history has " +
                "not arrived here yet$about, so it is being held until it does. " +
                "Nothing stopped and nothing is lost — your devices simply met in " +
                "the wrong order — and $RESUMES with it at the next sync, without " +
                "anybody doing anything."

        // Rule 6 working rather than a race being lost. The person is the
        // only one who can settle it, so the sentence hands them the
        // decision instead of announcing a fault — and it says where the
        // file is, because the file still being there is the whole point.
        VAULT_DELETED_NOTE_EDITED ->
            "A note another device deleted had been edited on ${words.thisDevice} " +
                "since that device last saw it$about, so the edited file was kept " +
                "and is offered as an import rather than removed. Nothing stopped " +
                "and nothing was lost: both intentions are held, and keeping or " +
                "deleting the file here is what settles it. $RESUMES with everything " +
                "else regardless."

        // A kind this build has never heard of is still treated as a halt,
        // and a halt nobody is told about is the failure mode § 8 names.
        // The kernel's own words are the only ones there are, so they are
        // shown on **both** shells — an aside is a nicety, and this is the
        // one case where there would otherwise be nothing at all.
        else -> {
            val said = detail.ifBlank { subject }.ifBlank { "no further detail" }
            "Something about a change from another device stopped it being taken " +
                "in: $said. Nothing past it has been applied and nothing is half " +
                "done, and $RESUMES once the condition clears."
        }
    }
}

// ---------------------------------------------------------------------
// Enrolment that failed because the key that sealed the identity is gone
// (ADR 0019 § 4).
// ---------------------------------------------------------------------

/// Whether a "sync unavailable" line is the one failure a person can
/// actually recover from.
///
/// The identity file is sealed by a platform key — a Keystore key on
/// Android, DPAPI on Windows — and **the key does not travel with the
/// file**. So a restored backup, a Keystore reset, or a Windows profile
/// that was rebuilt leaves a sealed file that nothing on this machine can
/// open, and `KeyWrap.unwrap` refuses rather than minting a fresh key that
/// could not open it either. That refusal is right: the alternative is
/// overwriting an identity whose peers still pin it.
///
/// It is also a dead end without a way out, which is what this exists for.
/// Every other enrolment failure is a transient or a bug; this one is a
/// state that clears by throwing away device-local state and enrolling
/// again, and the screen offers exactly that.
fun identityUnreadable(message: String?): Boolean {
    val said = message?.lowercase() ?: return false
    if (!said.contains("sync unavailable")) return false
    return said.contains("no longer on this") ||
        said.contains("could not open the identity key") ||
        said.contains("could not unwrap this device's identity key")
}

/// What to say about it, and what the button beside it will do. Stated in
/// full because the action deletes something: a person is owed the exact
/// scope before they press it, and the scope is small and permanent in
/// equal measure.
fun identityUnreadableSentence(thisDevice: String): String =
    "Sync cannot start on $thisDevice: the identity key it uses to prove which device " +
        "it is was sealed by a key $thisDevice no longer has. That is what a restored " +
        "backup looks like, and it is not damage — none of your notes are affected and " +
        "nothing has been lost.\n\n" +
        "Resetting sync throws away the identity $thisDevice holds, its list of paired " +
        "devices and its on-or-off setting, and enrols as a new device. It touches " +
        "nothing in your vault. Your other devices will not recognise this one " +
        "afterwards, so each of them has to be paired again — a new pairing, not an undo."

// ---------------------------------------------------------------------
// The banner's heading
// ---------------------------------------------------------------------

/// A banner holding nothing but conditions that are **not** damage must
/// not open by saying sync stopped — that is the whole reason
/// [alertIsDamage] is a function rather than a comment. One real halt
/// among the harmless ones outranks them all, because that is the one a
/// person came here to find out about.
fun alertsHeading(kinds: List<String>): String = when {
    kinds.isEmpty() -> ""
    kinds.any { alertIsDamage(it) } ->
        if (kinds.size == 1) {
            "Sync stopped short of taking everything in"
        } else {
            "Sync stopped short of taking everything in, for ${kinds.size} reasons"
        }
    kinds.all { it == SYNC_UNKNOWN_OP_KIND } -> "Sync kept something it could not read"
    kinds.all { it == SYNC_AWAITING_REFERENT } ->
        "Sync is waiting for the rest of another device's history"
    kinds.all { it == VAULT_DELETED_NOTE_EDITED } ->
        "A note deleted on another device was kept here"
    else -> "Nothing has stopped — ${kinds.size} things worth knowing"
}
