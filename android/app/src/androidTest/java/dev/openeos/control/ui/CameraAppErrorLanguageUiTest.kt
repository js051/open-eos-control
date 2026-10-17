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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.intl.LocaleList
import dev.openeos.control.R
import dev.openeos.control.data.CameraMediaItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
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

    @Test fun saveFeedbackLocalizesAppFailuresButPreservesProviderDetailsAndExplicitRetry() {
        val language = mutableStateOf("zh-TW")
        val message = mutableStateOf(CameraAppError.DOWNLOAD_DESTINATION_UNAVAILABLE.messageToken)
        val raw = "IOException: synthetic provider detail"
        var retries = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(language.value))) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    MediaViewerDialog(
                        item = CameraMediaItem("synthetic-item", "SYNTHETIC.JPG", "image"),
                        displayZone = ZoneId.of("UTC"), bytes = null, streamSource = null,
                        loading = false, offlinePlaceholder = true, position = 0, totalCount = 1,
                        canMovePrevious = false, canMoveNext = false, onPrevious = {}, onNext = {},
                        downloadEnabled = true, saveFeedback = MediaSaveFeedback.Failed(message.value),
                        onDownload = { retries++ }, onDismiss = {},
                    )
                }
            }
        }
        for (tag in listOf("zh-TW", "en", "zh-TW")) {
            val configuration = Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val resources = compose.activity.createConfigurationContext(configuration).resources
            for (detail in listOf(CameraAppError.DOWNLOAD_DESTINATION_UNAVAILABLE.messageToken, raw)) {
                compose.runOnIdle { language.value = tag; message.value = detail }
                val translated = if (detail == raw) raw else resources.getString(
                    CameraAppError.DOWNLOAD_DESTINATION_UNAVAILABLE.messageResource)
                compose.onNodeWithText(resources.getString(R.string.media_save_failed, translated))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(CameraAppError.DOWNLOAD_DESTINATION_UNAVAILABLE.messageToken).assertDoesNotExist()
                compose.onNodeWithText(resources.getString(R.string.media_save_retry)).performScrollTo().performClick()
            }
        }
        compose.runOnIdle { assertEquals(6, retries) }
    }
}
