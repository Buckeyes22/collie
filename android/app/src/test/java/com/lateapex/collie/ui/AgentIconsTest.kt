package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class AgentIconsTest {
    @Test
    fun mapsEveryHerdrIntegrationAndToleratesKnownFamilyVariants() {
        val expected = mapOf(
            "claude-code" to R.drawable.ic_agent_claude,
            "codex" to R.drawable.ic_agent_codex,
            "opencode" to R.drawable.ic_agent_opencode,
            "opencode-dev" to R.drawable.ic_agent_opencode,
            "pi.local" to R.drawable.ic_agent_pi,
            "omp" to R.drawable.ic_agent_omp,
            "agy" to R.drawable.ic_agent_antigravity,
            "antigravity_cli" to R.drawable.ic_agent_antigravity,
            "grok-build" to R.drawable.ic_agent_grok,
            "kimi" to R.drawable.ic_agent_kimi,
            "qwen-code" to R.drawable.ic_agent_qwen,
            "copilot" to R.drawable.ic_agent_copilot,
            "github-copilot" to R.drawable.ic_agent_copilot,
            "hermes-agent" to R.drawable.ic_agent_hermes,
            "goose" to R.drawable.ic_agent_goose,
        )

        expected.forEach { (agent, drawable) -> assertEquals(agent, drawable, agentIcon(agent)) }
        listOf(
            "claudette", "codexical", "opencodex", "pitwall", "omphalos", "agyness",
            "antigravitywell", "grokking", "kimiko", "qwench", "copilotage", "hermesian",
            "gooseberry", "unknown-agent",
        ).forEach { agent ->
            assertEquals(agent, R.drawable.ic_agent_generic, agentIcon(agent))
        }
    }

    @Test
    fun opencodeUsesTheCurrentBlockMarkInsteadOfTheLegacyOutline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(R.drawable.ic_agent_opencode)
        val pathData = buildList {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "path") {
                    parser.getAttributeValue(ANDROID_NAMESPACE, "pathData")?.let(::add)
                }
            }
        }

        assertTrue(pathData.any { it.contains("M3,0H21V24H3Z") })
        assertTrue(pathData.any { it.contains("M7.5,9.6H16.5V19.2H7.5Z") })
        assertFalse(pathData.any { it.contains("8.75") })
    }

    @Test
    fun everyMappedVectorInflatesAndDraws() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = listOf(
            "claude", "codex", "opencode", "pi", "omp", "antigravity_cli", "grok", "kimi",
            "qwen", "copilot", "hermes", "goose", "unknown-agent",
        ).map(::agentIcon).distinct()

        resources.forEach { id ->
            val name = context.resources.getResourceEntryName(id)
            val drawable = AppCompatResources.getDrawable(context, id)
            assertNotNull(name, drawable)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            drawable!!.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            assertTrue("$name has no intrinsic width", drawable.intrinsicWidth > 0)
            assertTrue("$name has no intrinsic height", drawable.intrinsicHeight > 0)
        }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
