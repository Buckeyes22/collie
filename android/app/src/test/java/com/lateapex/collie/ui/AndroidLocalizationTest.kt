package com.lateapex.collie.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static guard: visible native copy belongs in the English Android resources. */
class AndroidLocalizationTest {
    @Test
    fun englishResourcesArePresentAndWellFormed() {
        val resources = File("src/main/res")
        val base = resourceValues(File(resources, "values"))
        assertTrue("No base Android string resources found", base.strings.isNotEmpty())
        assertTrue("English plurals must define an other quantity", base.plurals.values.all { "other" in it })
    }

    @Test
    fun visibleKotlinAndLayoutCopyUsesResources() {
        val sourceRoot = File("src/main/java/com/lateapex/collie/ui")
        val sink = Regex(
            """(?:\b(?:text|hint|contentDescription|error|mutationError|status)\s*=|\b(?:setTitle|setMessage|setPositiveButton|setNegativeButton|showStatus|message|header|actionButton|button|textField)\()\s*\"[A-Za-z][^\"]*\"""",
        )
        val resultSink = Regex(
            """(?:ComposerMediaResult\.(?:Failure|Draft)|ImageSelectionResult\.Rejected|SpeechAvailability\.Unavailable)\([^\n]*\"[A-Za-z][^\"]*\"""",
        )
        val kotlinFailures = sourceRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (line, value) ->
                    if (sink.containsMatchIn(value) || resultSink.containsMatchIn(value)) {
                        "${file.name}:${line + 1}: ${value.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        val layoutFailures = File("src/main/res/layout").walkTopDown()
            .filter { it.extension == "xml" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (line, value) ->
                    val visible = Regex("""android:(?:text|hint|contentDescription)=\"(?!@)[^\"]*[A-Za-z][^\"]*\"""")
                    if (visible.containsMatchIn(value)) "${file.name}:${line + 1}: ${value.trim()}" else null
                }
            }
            .toList()

        assertEquals("Hardcoded visible Android copy", emptyList<String>(), kotlinFailures + layoutFailures)
    }

    private data class ResourceValues(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private fun resourceValues(directory: File): ResourceValues {
        if (!directory.isDirectory) return ResourceValues(emptyMap(), emptyMap())
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        directory.listFiles().orEmpty()
            .filter { it.extension == "xml" }
            .forEach { file ->
                val children = factory.newDocumentBuilder().parse(file).documentElement.childNodes
                for (index in 0 until children.length) {
                    val node = children.item(index)
                    if (node.attributes?.getNamedItem("translatable")?.nodeValue == "false") continue
                    val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                    when (node.nodeName) {
                        "string" -> strings[name] = node.textContent
                        "plurals" -> {
                            val items = linkedMapOf<String, String>()
                            for (itemIndex in 0 until node.childNodes.length) {
                                val item = node.childNodes.item(itemIndex)
                                if (item.nodeName != "item") continue
                                val quantity = item.attributes?.getNamedItem("quantity")?.nodeValue ?: continue
                                items[quantity] = item.textContent
                            }
                            plurals[name] = items
                        }
                    }
                }
            }
        return ResourceValues(strings, plurals)
    }

}
