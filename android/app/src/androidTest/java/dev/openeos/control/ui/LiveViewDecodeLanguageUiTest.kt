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
import dev.openeos.control.R
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Compose resource checks with synthetic details; no physical-camera claim. */
class LiveViewDecodeLanguageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun retainedDecodeFailureUsesCurrentLanguageAndPreservesDetails() {
        val language = mutableStateOf("zh-TW")
        val contentType = mutableStateOf<String?>(null)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(language.value))) {
                MaterialTheme { ErrorBanner(LiveViewDecodeFailure(4, contentType.value).messageToken, {}) }
            }
        }
        for (type in listOf(null, "", "image/jpeg", "SocketTimeoutException;%s;繁中")) {
            compose.runOnIdle { contentType.value = type }
            for (tag in listOf("zh-TW", "en", "zh-TW")) {
                compose.runOnIdle { language.value = tag }
                val configuration = Configuration(compose.activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                }
                val resources = compose.activity.createConfigurationContext(configuration).resources
                val expected = resources.getString(R.string.camera_error_live_view_decode_failed, 4,
                    type ?: resources.getString(R.string.camera_error_live_view_content_type_unknown))
                compose.onNodeWithText(expected).assertIsDisplayed()
                compose.onNodeWithText(LiveViewDecodeFailure(4, type).messageToken).assertDoesNotExist()
            }
        }
    }

    @Test fun providerWrappedTokenRemainsRaw() {
        val raw = "IOException: ${LiveViewDecodeFailure(4, "image/jpeg").messageToken}"
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("zh-TW"))) {
                MaterialTheme { ErrorBanner(raw, {}) }
            }
        }
        compose.onNodeWithText(raw).assertIsDisplayed()
    }
}
