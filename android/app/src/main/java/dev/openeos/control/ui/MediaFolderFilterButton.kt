package dev.openeos.control.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaFolder

@Composable
internal fun MediaFolderFilterButton(
    selected: MediaFolderFilter,
    folders: List<CameraMediaFolder>,
    unknownCount: Int,
    onSelected: (MediaFolderFilter) -> Unit,
    modifier: Modifier = Modifier,
    menuModifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = mediaFolderFilterLabel(selected, folders)
    val buttonLabel = stringResource(R.string.media_folder_filter_current, currentLabel)
    Box(modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.widthIn(max = 200.dp).heightIn(min = 48.dp).testTag("media-folder-filter")
                .semantics { contentDescription = buttonLabel; this.selected = selected != MediaFolderFilter.All },
        ) {
            Text(stringResource(R.string.media_folder_filter_label), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected == MediaFolderFilter.All) AppSubtleText else AppAccent)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = menuModifier.widthIn(max = 320.dp).heightIn(max = 360.dp).testTag("media-folder-menu"),
        ) {
            FolderOption(
                stringResource(R.string.media_folder_all), selected == MediaFolderFilter.All,
                "media-folder-all", { expanded = false; onSelected(MediaFolderFilter.All) },
            )
            FolderOption(
                stringResource(R.string.media_folder_unknown_count, unknownCount), selected == MediaFolderFilter.Unknown,
                "media-folder-unknown", { expanded = false; onSelected(MediaFolderFilter.Unknown) },
            )
            folders.forEach { folder ->
                FolderOption(folder.label,
                    (selected as? MediaFolderFilter.Folder)?.folder?.id == folder.id,
                    "media-folder-option-${folder.id}",
                    { expanded = false; onSelected(MediaFolderFilter.Folder(folder)) })
            }
            Text(
                stringResource(if (folders.isEmpty()) R.string.media_folder_not_provided else R.string.media_folder_loaded_only),
                color = AppSubtleText,
            )
        }
    }
}

@Composable
private fun FolderOption(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, maxLines = 3, overflow = TextOverflow.Ellipsis, color = if (selected) AppAccent else AppText) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag)
            .semantics { this.selected = selected; contentDescription = label },
    )
}

@Composable
internal fun mediaFolderFilterLabel(filter: MediaFolderFilter, folders: List<CameraMediaFolder>): String = when (filter) {
    MediaFolderFilter.All -> stringResource(R.string.media_folder_all)
    MediaFolderFilter.Unknown -> stringResource(R.string.media_folder_unknown)
    is MediaFolderFilter.Folder -> folders.firstOrNull { it.id == filter.folder.id }?.label ?: filter.folder.label
}
