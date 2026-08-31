// The rules an editor must not break, kept apart from any screen so they
// can be tested directly (ADR 0012).
//
// Projection rewrites files under an open editor — canonicalising an
// edit, or adding a transcript to a note you happen to be reading. A
// buffer read before that rewrite is stale, and writing it back would
// silently revert content the kernel had just settled.

package org.gilune.app

import java.security.MessageDigest

fun contentHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun contentHash(text: String): String = contentHash(text.toByteArray())

/// Whether a buffer opened at `hashAtOpen` may be written over what is on
/// disk now. A file that vanished may be recreated; a file that changed
/// since it was read may not be overwritten without a decision.
fun mayOverwrite(hashAtOpen: String, hashOnDisk: String?): Boolean =
    hashOnDisk == null || hashOnDisk == hashAtOpen
