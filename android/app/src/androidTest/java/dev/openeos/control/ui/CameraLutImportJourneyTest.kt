package dev.openeos.control.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Real production Compose picker/screen -> ViewModel -> ContentResolver/FileProvider ->
 * bounded decoder/parser. Only the external OS picker is replaced by a deferred registry.
 * No camera is connected. Restoration here recreates Compose saved state with the SAME
 * ViewModel; it is not a process-death, Activity rotation, DocumentsUI or physical-phone claim.
 */
class CameraLutImportJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var model: CameraViewModel
    private lateinit var directory: File
    private lateinit var restoration: StateRestorationTester
    private val registry = LutPickerRegistry()

    @Before fun setUp() {
        directory = File(compose.activity.cacheDir, "camera-import/lut-journey-${UUID.randomUUID()}")
        check(directory.mkdirs())
        compose.runOnUiThread {
            model = CameraViewModel()
            store.put("lut-journey", model)
            model.openSettingPicker(SettingPicker.MONITOR)
        }
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val load = rememberCubeLutPickerLauncher(model)
                val state by model.uiState.collectAsState()
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    CameraControlScreen(state, connectionRecoveryTestActions().copy(
                        loadCubeLut = load, clearCubeLut = model::clearCubeLut,
                        cancelCubeLutImport = model::cancelCubeLutImport,
                        closePicker = model::closeSettingPicker,
                    ))
                }
            }
        }
    }

    @After fun tearDown() {
        if (::model.isInitialized) {
            val job = model.viewModelScope.coroutineContext[Job]!!
            compose.runOnIdle { store.clear() }
            compose.waitUntil(10_000) { job.isCompleted }
        }
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun validUnicodeDocumentLoadsAndRemoveClearsWithoutTouchingOtherAssists() {
        compose.runOnIdle { model.setHistogramVisible(true); model.setZebraThreshold(90) }
        select(document("測試 🎥.cube", CUBE.toByteArray()))
        awaitName("測試 🎥")
        assertTrue(model.uiState.value.monitorSettings.histogramVisible)
        assertEquals(90, model.uiState.value.monitorSettings.zebraThresholdPercent)
        assertArrayEquals(floatArrayOf(.25f, .5f, .75f), model.uiState.value.monitorSettings.cubeLut!!.sample(.25f, .5f, .75f), .000001f)
        compose.onNodeWithTag("monitor-lut-remove").performScrollTo().performClick()
        compose.runOnIdle { assertNull(model.uiState.value.monitorSettings.cubeLut) }
    }

    @Test fun badUtf8InsideCommentPreservesAppliedAndDisplaysScopedErrorThenRetryWorks() {
        select(document("first.cube", CUBE.toByteArray())); awaitName("first")
        select(document("invalid.cube", byteArrayOf(35, 32, 0x80.toByte(), 10) + CUBE.toByteArray()))
        compose.waitUntil(10_000) { model.uiState.value.cubeLutImport.error != null }
        assertEquals("first", model.uiState.value.monitorSettings.cubeLut?.name)
        assertNull(model.uiState.value.error)
        compose.onNodeWithTag("monitor-lut-error").performScrollTo().assertIsDisplayed()
        select(document("retry.cube", CUBE.toByteArray())); awaitName("retry")
        assertNull(model.uiState.value.cubeLutImport.error)
    }

    @Test fun sharedByteVectorsUseRealProviderReadAndExistingParser() {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("utf8-vectors.json")
            .use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
        val vectors = fixture.getJSONArray("vectors")
        assertEquals(16, vectors.length())
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val previous = model.uiState.value.monitorSettings.cubeLut
            select(document(vector.getString("id") + ".cube", Base64.getDecoder().decode(vector.getString("base64"))))
            compose.waitUntil(10_000) { !model.uiState.value.cubeLutImport.loading }
            if (vector.getBoolean("accepted")) {
                assertNull(vector.getString("id"), model.uiState.value.cubeLutImport.error)
                assertNotNull(model.uiState.value.monitorSettings.cubeLut)
            } else {
                assertNotNull(vector.getString("id"), model.uiState.value.cubeLutImport.error)
                assertSame(previous, model.uiState.value.monitorSettings.cubeLut)
            }
        }
    }

    @Test fun dismissedPickerRetainsAppliedAndReportsNoFailure() {
        select(document("first.cube", CUBE.toByteArray())); awaitName("first")
        launch(); compose.runOnIdle { registry.deliver(null) }
        compose.runOnIdle {
            assertEquals("first", model.uiState.value.monitorSettings.cubeLut?.name)
            assertNull(model.uiState.value.cubeLutImport.error)
            assertFalse(model.uiState.value.cubeLutImport.pickerOpen)
        }
    }

    @Test fun removeAndExplicitCancelFenceOutstandingPickerResults() {
        select(document("first.cube", CUBE.toByteArray())); awaitName("first")
        launch(); compose.runOnIdle { model.cancelCubeLutImport(); registry.deliver(document("cancelled.cube", CUBE.toByteArray())) }
        compose.runOnIdle { assertEquals("first", model.uiState.value.monitorSettings.cubeLut?.name) }
        launch(); compose.runOnIdle { model.clearCubeLut(); registry.deliver(document("removed.cube", CUBE.toByteArray())) }
        compose.runOnIdle { assertNull(model.uiState.value.monitorSettings.cubeLut); assertFalse(model.uiState.value.cubeLutImport.loading) }
    }

    @Test fun closingSheetAndDisconnectKeepThePickerAndAppliedLutAppScoped() {
        launch()
        compose.runOnIdle { model.closeSettingPicker(); model.disconnect(); registry.deliver(document("local.cube", CUBE.toByteArray())) }
        awaitName("local")
        compose.runOnIdle { model.disconnect(); model.openSettingPicker(SettingPicker.MONITOR) }
        assertEquals("local", model.uiState.value.monitorSettings.cubeLut?.name)
        compose.onNodeWithTag("monitor-lut-remove").performScrollTo().assertIsDisplayed()
    }

    @Test fun sameOwnerSavedStateRestorationRetainsOneImmutableRequest() {
        launch(); val count = registry.launchCount
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(count, registry.launchCount); registry.deliver(document("restored.cube", CUBE.toByteArray())) }
        awaitName("restored")
    }

    @Test fun launcherFailureIsScopedAndAllowsRetry() {
        registry.failure = ActivityNotFoundException("Synthetic picker unavailable")
        launch()
        compose.waitUntil(10_000) { model.uiState.value.cubeLutImport.error != null }
        assertFalse(model.uiState.value.cubeLutImport.pickerOpen); assertNull(model.uiState.value.error)
        select(document("retry.cube", CUBE.toByteArray())); awaitName("retry")
    }

    @Test fun orphanedRestoredTicketIsVisibleAndCannotSilentlyStartAnotherPicker() {
        compose.runOnIdle { model.restoreCubeLutPicker("synthetic-previous-owner") }
        compose.onNodeWithTag("monitor-lut-picker-recovery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("monitor-lut-load").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            model.importCubeLut(compose.activity, "synthetic-previous-owner", document("stale.cube", CUBE.toByteArray()))
            assertNull(model.uiState.value.monitorSettings.cubeLut)
            assertFalse(model.uiState.value.cubeLutImport.pickerRecoveryRequired)
        }
    }

    private fun launch() { compose.onNodeWithTag("monitor-lut-load").performScrollTo().performClick() }
    private fun select(uri: Uri) { launch(); compose.runOnIdle { registry.deliver(uri) } }
    private fun awaitName(name: String) {
        compose.waitUntil(10_000) { !model.uiState.value.cubeLutImport.loading && model.uiState.value.monitorSettings.cubeLut?.name == name }
    }
    private fun document(name: String, bytes: ByteArray): Uri {
        val file = File(directory, name).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.camera_import", file)
    }
    companion object { private const val CUBE = "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n" }
}

private class LutPickerRegistry : ActivityResultRegistry() {
    var launchCount = 0
    private var code: Int? = null
    var failure: RuntimeException? = null
    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        assertTrue(contract is ActivityResultContracts.OpenDocument)
        failure?.let { failure = null; throw it }
        check(code == null) { "Overlapping OS picker requests" }
        code = requestCode; launchCount++
    }
    fun deliver(uri: Uri?) {
        val completed = requireNotNull(code); code = null
        assertTrue(dispatchResult(completed, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(it) }))
    }
}
