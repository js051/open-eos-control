package dev.openeos.control.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.openeos.control.R
import java.time.ZoneId

/** Draft input belongs to the dialog; only Apply or Clear changes the ViewModel's filter. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MediaDateRangeDialog(
    range: MediaDateRange?,
    displayZone: ZoneId,
    onApply: (MediaDateRange?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var start by remember { mutableStateOf(range?.start?.toString().orEmpty()) }
    var end by remember { mutableStateOf(range?.end?.toString().orEmpty()) }
    val draft = mediaDateRangeFromInput(start, end)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = modifier.testTag("media-date-dialog").padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = 600.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            // The whole form scrolls in the real Dialog window, including with the IME or large text.
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.media_date_filter), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.media_date_explanation))
                Text(stringResource(R.string.media_date_zone, displayZone.id))
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text(stringResource(R.string.media_date_start)) },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().testTag("media-date-start"),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text(stringResource(R.string.media_date_end)) },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().testTag("media-date-end"),
                )
                if (draft == null && (start.isNotEmpty() || end.isNotEmpty())) {
                    Text(stringResource(R.string.media_date_invalid), color = MaterialTheme.colorScheme.error)
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("media-date-cancel")) {
                        Text(stringResource(R.string.cancel))
                    }
                    if (range != null) {
                        TextButton(onClick = { onApply(null) }, modifier = Modifier.testTag("media-date-clear")) {
                            Text(stringResource(R.string.media_date_clear))
                        }
                    }
                    TextButton(
                        onClick = { draft?.let(onApply) },
                        enabled = draft != null,
                        modifier = Modifier.testTag("media-date-apply"),
                    ) {
                        Text(stringResource(R.string.media_date_apply))
                    }
                }
            }
        }
    }
}
