package org.gilune.app

import java.io.File
import java.lang.ref.WeakReference

/** Open buffers register independently of whether their tab is composed.
 * The shared gate serialises autosave with host-dispatched refactoring writes. */
internal interface LiveDocumentBuffer {
    val documentFile: String?
    val hasUnsavedText: Boolean
}

internal object DocumentEditGate {
    val monitor = Any()
    private val buffers = mutableListOf<WeakReference<LiveDocumentBuffer>>()
    fun register(buffer: LiveDocumentBuffer) = synchronized(monitor) {
        buffers.removeAll { it.get() == null || it.get() === buffer }
        buffers += WeakReference(buffer)
    }
    fun dirty(vault: String, path: String): Boolean = synchronized(monitor) {
        val file = File(vault, path).absoluteFile.normalize().path
        buffers.any { reference -> reference.get()?.let { it.documentFile == file && it.hasUnsavedText } == true }
    }
}
