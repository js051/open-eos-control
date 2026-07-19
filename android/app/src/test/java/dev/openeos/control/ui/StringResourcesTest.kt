package dev.openeos.control.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class StringResourcesTest {
    @Test
    fun traditionalChineseContainsEveryEnglishResourceWithMatchingFormats() {
        val english = readResources(resourceFile("values/strings.xml"))
        val traditionalChinese = readResources(resourceFile("values-zh-rTW/strings.xml"))

        assertEquals(english.keys, traditionalChinese.keys)
        english.forEach { (name, sourceValues) ->
            assertEquals(
                "Format arguments differ for $name",
                sourceValues.map(::formatArguments).toSet(),
                traditionalChinese.getValue(name).map(::formatArguments).toSet(),
            )
        }
    }

    @Test
    fun localeConfigDeclaresEnglishAndTraditionalChinese() {
        val localeConfig = resourceFile("xml/locales_config.xml").readText()

        assertTrue(localeConfig.contains("android:name=\"en\""))
        assertTrue(localeConfig.contains("android:name=\"zh-TW\""))
    }

    private fun resourceFile(path: String): File = sequenceOf(
        File("src/main/res/$path"),
        File("app/src/main/res/$path"),
    ).firstOrNull(File::isFile) ?: error("Android resource not found: $path")

    private fun readResources(file: File): Map<String, List<String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val resources = document.documentElement.childNodes
        return buildMap {
            for (index in 0 until resources.length) {
                val element = resources.item(index) as? Element ?: continue
                val name = element.getAttribute("name")
                when (element.tagName) {
                    "string" -> put(name, listOf(element.textContent))
                    "plurals" -> {
                        val items = element.getElementsByTagName("item")
                        put(name, List(items.length) { items.item(it).textContent })
                    }
                }
            }
        }
    }

    private fun formatArguments(value: String): List<String> =
        FORMAT_ARGUMENT.findAll(value).map { it.value }.sorted().toList()

    companion object {
        private val FORMAT_ARGUMENT = Regex("%\\d+\\$(?:\\.\\d+)?[a-zA-Z]")
    }
}
