package dev.openeos.control.ui

import android.content.Context
import dev.openeos.control.data.DownloadHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** One writer per app-private path for all ViewModels in this process. No camera session owns it. */
internal object DownloadHistoryProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stores = mutableMapOf<String, DownloadHistoryStore>()

    @Synchronized
    fun get(context: Context): DownloadHistoryStore {
        val privateRoot = requireNotNull(context.applicationContext.noBackupFilesDir) {
            "Private download history storage is unavailable."
        }
        check(privateRoot.isAbsolute) { "Private download history storage must be absolute." }
        val directory = File(privateRoot, "download-history")
        return stores.getOrPut(directory.absolutePath) { DownloadHistoryStore(directory, scope) }
    }
}
