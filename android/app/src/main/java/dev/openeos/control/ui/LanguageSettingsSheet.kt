package dev.openeos.control.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.openeos.control.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsSheet(state: CameraUiState, actions: CameraActions) {
    if (state.activeSettingPicker != SettingPicker.LANGUAGE) return
    val current = AppLanguageManager.current()
    ModalBottomSheet(
        onDismissRequest = actions.closePicker,
        containerColor = AppSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.language),
                color = AppText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LanguageOption(
                label = stringResource(R.string.language_system),
                selected = current == AppLanguage.SYSTEM,
            ) { actions.setAppLanguage(AppLanguage.SYSTEM) }
            LanguageOption(
                label = stringResource(R.string.language_english),
                selected = current == AppLanguage.ENGLISH,
            ) { actions.setAppLanguage(AppLanguage.ENGLISH) }
            LanguageOption(
                label = stringResource(R.string.language_traditional_chinese),
                selected = current == AppLanguage.TRADITIONAL_CHINESE,
            ) { actions.setAppLanguage(AppLanguage.TRADITIONAL_CHINESE) }
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, color = AppText, modifier = Modifier.padding(start = 8.dp))
    }
}
