package dev.openeos.control.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Called only inside the importer's owned IO stage. No persisted URI permission is taken. */
internal suspend fun readCubeLutDocument(resolver: ContentResolver, uri: Uri): CubeLutDocument {
    val context = currentCoroutineContext()
    context.ensureActive()
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "Imported LUT.cube"
    context.ensureActive()
    val text = resolver.openInputStream(uri)?.use { input ->
        readCubeLutText(input) { context.ensureActive() }
    } ?: error("The selected LUT could not be opened.")
    context.ensureActive()
    return CubeLutDocument(name, text)
}
