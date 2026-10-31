// A transcription provider's decisions about a claimed job (ADR 0018 § 2),
// shared by every app-side engine: Moonshine on the phone, NVIDIA NeMo on the
// desktop (ADR 0027 S3). Pure over the subject's own document.

package org.gilune.app

import org.json.JSONObject

/// What the provider should do with a claimed job, decided from the
/// subject's own document alone — no engine, no bytes, no clock.
sealed interface ProviderAction {
    data object Decode : ProviderAction

    /// Complete the job, write nothing. The subject is not a recording, or
    /// it already has words.
    data class NothingToDo(val why: String) : ProviderAction

    /// Fail the job. Deliberately distinct from [NothingToDo]: a document
    /// that cannot be read is a fault to retry and eventually quarantine,
    /// exactly as `whisper.rs` treats an unparseable payload.
    data class Fault(val why: String) : ProviderAction
}

/// The decision, pure over the subject's head document.
///
/// **The wordless gate lives here** (§ 3). It is asked of every job, not
/// only of a catch-up, which means it also narrows § 3's named hole: with
/// both providers somehow enabled, this one will not overwrite words the
/// other already wrote. It narrows rather than closes — two engines
/// decoding the same silent-so-far capture at once still race — and only
/// the shell's one-active rule stops that.
///
/// **Except when somebody asked** (ADR 0029). "Transcribe again" is a new
/// event whose payload says `again`, and the gate exists to stop a
/// catch-up overwriting words nobody asked to replace — a person asking is
/// the opposite case, so the request decodes whatever the note holds.
fun providerAction(subjectPayload: String, eventPayload: String = ""): ProviderAction {
    val document = runCatching { JSONObject(subjectPayload) }.getOrNull()
        ?: return ProviderAction.Fault("the subject's document could not be read")
    if (document.optString("media") != "wav") {
        return ProviderAction.NothingToDo("not a recording")
    }
    if (document.optString("text").isNotBlank() && !askedAgain(eventPayload)) {
        return ProviderAction.NothingToDo("already has words")
    }
    return ProviderAction.Decode
}

/// The kernel's `reprocess::AGAIN_KEY`, read off the event that queued the
/// job. A payload that does not parse asked nothing.
private fun askedAgain(eventPayload: String): Boolean =
    runCatching { JSONObject(eventPayload).optBoolean("again", false) }.getOrDefault(false)

/// The subject's document with the transcript in it — **whisper's shape,
/// field for field**: parse the head document, replace `text`, keep
/// everything else (`media`, `rel_path`, the capture's own facts) exactly
/// as it was, and submit it as a revision of the subject (ADR 0013). The
/// only difference between the two engines in the vault is the origin the
/// kernel stamps, which is the whole point of distinct processor ids.
fun transcriptDocument(subjectPayload: String, text: String): String =
    JSONObject(subjectPayload).put("text", text).toString()
