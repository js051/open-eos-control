package dev.openeos.control.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.openeos.control.data.CameraMediaItem

internal data class CameraMediaPickerLaunchers(
    val downloadDocument: (CameraMediaItem) -> Unit,
    val downloadFolder: (List<CameraMediaItem>) -> Unit,
    val upload: () -> Unit,
)

/** Register at the app root, including while disconnected, so restored results are consumed there. */
@Composable
internal fun rememberCameraMediaPickerLaunchers(viewModel: CameraViewModel): CameraMediaPickerLaunchers {
    val context = LocalContext.current
    // Only request IDs are saveable. A replacement ViewModel must not recover old camera work.
    var documentRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var folderRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var uploadRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = documentRequestId
        documentRequestId = null
        viewModel.completeMediaPicker(context, CameraMediaPickerKind.DOWNLOAD_DOCUMENT, id, uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val id = folderRequestId
        folderRequestId = null
        viewModel.completeMediaPicker(context, CameraMediaPickerKind.DOWNLOAD_FOLDER, id, uri)
    }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = uploadRequestId
        uploadRequestId = null
        viewModel.completeMediaPicker(context, CameraMediaPickerKind.UPLOAD, id, uri)
    }
    return CameraMediaPickerLaunchers(
        downloadDocument = { item ->
            viewModel.beginMediaPicker(CameraMediaPickerKind.DOWNLOAD_DOCUMENT, listOf(item))?.let { id ->
                documentRequestId = id
                try {
                    documentLauncher.launch(item.name)
                } catch (_: Exception) {
                    if (documentRequestId == id) documentRequestId = null
                    viewModel.failMediaPickerLaunch(context, CameraMediaPickerKind.DOWNLOAD_DOCUMENT, id)
                }
            }
        },
        downloadFolder = { items ->
            viewModel.beginMediaPicker(CameraMediaPickerKind.DOWNLOAD_FOLDER, items)?.let { id ->
                folderRequestId = id
                try {
                    folderLauncher.launch(null)
                } catch (_: Exception) {
                    if (folderRequestId == id) folderRequestId = null
                    viewModel.failMediaPickerLaunch(context, CameraMediaPickerKind.DOWNLOAD_FOLDER, id)
                }
            }
        },
        upload = {
            viewModel.beginMediaPicker(CameraMediaPickerKind.UPLOAD)?.let { id ->
                uploadRequestId = id
                try {
                    uploadLauncher.launch(arrayOf("image/*", "video/*", "application/octet-stream"))
                } catch (_: Exception) {
                    if (uploadRequestId == id) uploadRequestId = null
                    viewModel.failMediaPickerLaunch(context, CameraMediaPickerKind.UPLOAD, id)
                }
            }
        },
    )
}
