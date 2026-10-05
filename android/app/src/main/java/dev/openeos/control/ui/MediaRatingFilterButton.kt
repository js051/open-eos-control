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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.openeos.control.R

@Composable
internal fun MediaRatingFilterButton(
    selected: MediaRatingFilter,
    onSelected: (MediaRatingFilter) -> Unit,
    menuModifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val fullLabel = stringResource(R.string.media_rating_filter_current, mediaRatingFilterLabel(selected))
    val shortLabel = when (selected) {
        MediaRatingFilter.ALL -> stringResource(R.string.media_all)
        MediaRatingFilter.UNRATED -> stringResource(R.string.media_rating_short_zero)
        MediaRatingFilter.UNKNOWN -> stringResource(R.string.media_rating_short_unknown)
        MediaRatingFilter.FIVE -> stringResource(R.string.media_rating_five)
        else -> stringResource(R.string.media_rating_short_minimum, requireNotNull(selected.minimumStars))
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.widthIn(max = 200.dp).heightIn(min = 48.dp)
                .testTag("media-rating-filter").semantics { contentDescription = fullLabel },
        ) {
            Text(
                stringResource(R.string.media_rating_filter_current, shortLabel),
                color = if (selected == MediaRatingFilter.ALL) AppSubtleText else AppAccent,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = menuModifier.widthIn(max = 320.dp).testTag("media-rating-menu"),
        ) {
            MediaRatingFilter.entries.forEach { value ->
                DropdownMenuItem(
                    text = {
                        Text(
                            mediaRatingFilterLabel(value),
                            color = if (selected == value) AppAccent else AppText,
                            fontWeight = if (selected == value) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("media-rating-${value.name}")
                        .semantics { this.selected = selected == value },
                    onClick = { expanded = false; onSelected(value) },
                )
            }
        }
    }
}

@Composable
internal fun mediaRatingFilterLabel(filter: MediaRatingFilter): String = when (filter) {
    MediaRatingFilter.ALL -> stringResource(R.string.media_rating_all)
    MediaRatingFilter.UNRATED -> stringResource(R.string.media_rating_unrated)
    MediaRatingFilter.UNKNOWN -> stringResource(R.string.media_rating_unknown)
    MediaRatingFilter.FIVE -> stringResource(R.string.media_rating_five)
    else -> stringResource(R.string.media_rating_at_least, requireNotNull(filter.minimumStars))
}
