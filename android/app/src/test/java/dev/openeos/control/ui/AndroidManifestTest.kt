package dev.openeos.control.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidManifestTest {
    @Test
    fun mainActivityDefersDisplayOrientationToSystemSettings() {
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
            "MainActivity must not bypass the user's system rotation setting",
            "",
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

    private fun manifestFile(): File = sequenceOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull(File::isFile) ?: error("Android manifest not found")

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
