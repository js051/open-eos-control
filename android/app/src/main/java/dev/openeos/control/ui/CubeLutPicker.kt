package dev.openeos.control.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException

/** Register at app root, not inside the dismissible monitoring sheet. */
@Composable
internal fun rememberCubeLutPickerLauncher(viewModel: CameraViewModel): () -> Unit {
    val context = LocalContext.current
    // The saved ticket belongs to this launch even if Remove/Cancel revoked its owner.
    var ticket by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val completed = ticket
        ticket = null
        if (completed != null) viewModel.importCubeLut(context, completed, uri)
    }
    LaunchedEffect(viewModel, ticket) {
        ticket?.let(viewModel::restoreCubeLutPicker)
    }
    return launch@{
        if (ticket != null) return@launch
        val accepted = viewModel.beginCubeLutPicker() ?: return@launch
        ticket = accepted
        try {
            launcher.launch(arrayOf("text/plain", "application/octet-stream", "application/x-cube"))
        } catch (cancelled: CancellationException) {
            ticket = null
            viewModel.importCubeLut(context, accepted, null)
            throw cancelled
        } catch (failure: RuntimeException) {
            ticket = null
            viewModel.reportCubeLutPickerFailure(accepted, failure.message ?: failure::class.java.simpleName)
        }
    }
}
