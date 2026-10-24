package com.nuvio.app.features.livetv

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Native file selection for local M3U/XMLTV files.
 *
 * Returns an absolute path, or null when the user cancelled or the dialog could not be shown.
 * The dialog always runs on the AWT event thread; a caller already on that thread (Compose
 * desktop) invokes it directly so the nested modal loop can pump its own events.
 *
 * No filename filter is installed: AWT applies [FileDialog.setFilenameFilter] on macOS only, so a
 * filter would silently blur the accepted file set across platforms.
 */
internal fun chooseLiveTvFile(
    parent: Window?,
    title: String,
): String? {
    val result = AtomicReference<String?>(null)
    val showDialog = { result.set(showFileDialog(parent, title)) }

    if (EventQueue.isDispatchThread()) {
        showDialog()
    } else {
        EventQueue.invokeAndWait(showDialog)
    }
    return result.get()
}

private fun showFileDialog(
    parent: Window?,
    title: String,
): String? {
    val dialog = FileDialog(parent as? Frame, title, FileDialog.LOAD)
    dialog.isMultipleMode = false
    dialog.isVisible = true
    val directory = dialog.directory
    val file = dialog.file
    dialog.dispose()
    if (directory.isNullOrBlank() || file.isNullOrBlank()) return null
    return runCatching { File(directory, file).absolutePath }.getOrNull()
}
