package dev.openeos.control.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidManifestTest {
    @Test
    fun mainActivityKeepsCameraLayoutInTheDevicesNaturalOrientation() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile())
        val activities = document.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) }
            .first { activity ->
                activity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name")?.nodeValue == ".MainActivity"
            }

        assertEquals(
            "MainActivity must keep the camera layout fixed while controls rotate independently",
            "nosensor",
            mainActivity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "screenOrientation")?.nodeValue.orEmpty(),
        )
        assertEquals(
            setOf("orientation", "screenSize", "keyboardHidden"),
            mainActivity.attributes.getNamedItemNS(ANDROID_NAMESPACE, "configChanges")
                ?.nodeValue
                .orEmpty()
                .split('|')
                .filter(String::isNotBlank)
                .toSet(),
        )
    }

    @Test
    fun cameraImportProviderSharesOnlyTheDedicatedCacheDirectory() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile())
        val providers = document.getElementsByTagName("provider")
        val provider = (0 until providers.length)
            .map { providers.item(it) }
            .firstOrNull { node ->
                node.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name")?.nodeValue ==
                    "androidx.core.content.FileProvider"
            }
        assertNotNull("Camera Import requires a FileProvider", provider)
        assertEquals(
            "false",
            provider?.attributes?.getNamedItemNS(ANDROID_NAMESPACE, "exported")?.nodeValue,
        )
        assertEquals(
            "true",
            provider?.attributes?.getNamedItemNS(ANDROID_NAMESPACE, "grantUriPermissions")?.nodeValue,
        )

        val paths = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(cameraImportPathsFile())
        val cachePath = paths.getElementsByTagName("cache-path").item(0)
        assertEquals("camera-import/", cachePath.attributes.getNamedItem("path")?.nodeValue)
    }

    private fun manifestFile(): File = sequenceOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile) ?: error("Android manifest not found")

    private fun cameraImportPathsFile(): File = sequenceOf(
        File("src/main/res/xml/camera_import_paths.xml"),
        File("app/src/main/res/xml/camera_import_paths.xml"),
    ).firstOrNull(File::isFile) ?: error("Camera Import FileProvider paths not found")

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
