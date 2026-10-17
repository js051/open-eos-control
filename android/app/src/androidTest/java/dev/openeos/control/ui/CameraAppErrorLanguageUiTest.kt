package dev.openeos.control.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.intl.LocaleList
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Resource-rendering checks with selected-locale overrides; no physical camera or system-picker claim. */
class CameraAppErrorLanguageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun retainedAppFailuresFollowTheCurrentUiLanguage() {
        val language = mutableStateOf("zh-TW")
        val error = mutableStateOf(CameraAppError.MAINTENANCE_RETIREMENT_UNCONFIRMED)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(language.value))) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    ErrorBanner(error.value.messageToken, {})
                }
            }
        }
        for (failure in CameraAppError.entries) {
            compose.runOnIdle { error.value = failure }
            for (tag in listOf("zh-TW", "en", "zh-TW")) {
                compose.runOnIdle { language.value = tag }
                val configuration = Configuration(compose.activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                }
                val expected = compose.activity.createConfigurationContext(configuration).getString(failure.messageResource)
                compose.onNodeWithText(expected).assertIsDisplayed()
                compose.onNodeWithText(failure.messageToken).assertDoesNotExist()
            }
        }
    }

    @Test fun rawProviderDiagnosticIsNotRewrittenByTheAppErrorMapper() {
        val raw = "IOException: synthetic provider detail"
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("zh-TW"))) {
                MaterialTheme(colorScheme = OpenEosColorScheme) { ErrorBanner(raw, {}) }
            }
        }
        compose.onNodeWithText(raw).assertIsDisplayed()
    }
}
