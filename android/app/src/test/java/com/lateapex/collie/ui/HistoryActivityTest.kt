package com.lateapex.collie.ui

import android.content.ComponentName
import android.content.Intent
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import com.lateapex.collie.network.TranscriptResult
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HistoryActivityTest {
    @Test
    fun historyIsSecureAndRejectsAnUnscopedLaunch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(
            HistoryActivity::class.java,
            Intent(context, HistoryActivity::class.java),
        ).create().get()

        assertTrue(activity.isFinishing)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
    }

    @Test
    fun historyActivityIsNotExported() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(ComponentName(context, HistoryActivity::class.java), 0)
        assertFalse(info.exported)
    }

    @Test
    fun transcriptFindAndUserTurnNavigationControlsAreWired() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(context, PaneAddress(Scope(), "w1:p1"), "Pane", "codex"),
        ).create().get()

        assertTrue(activity.findViewById<View>(R.id.history_find_previous).hasOnClickListeners())
        assertTrue(activity.findViewById<View>(R.id.history_find_next).hasOnClickListeners())
        assertTrue(activity.findViewById<View>(R.id.history_previous_user).hasOnClickListeners())
        assertTrue(activity.findViewById<View>(R.id.history_next_user).hasOnClickListeners())
        assertTrue(activity.findViewById<View>(R.id.history_header_normal) is CollieWideMaxWidthLinearLayout)
        assertTrue(activity.findViewById<View>(R.id.history_header_find) is CollieWideMaxWidthLinearLayout)
    }

    @Test
    fun historyNavigationAndSearchControlsKeepTheFortyFourDpTouchFloor() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(context, PaneAddress(Scope(), "w1:p1"), "Pane", "codex"),
        ).create().get()
        val floor = activity.resources.getDimensionPixelSize(R.dimen.collie_touch_target)

        listOf(
            R.id.history_find_open,
            R.id.history_close,
            R.id.history_find_previous,
            R.id.history_find_next,
            R.id.history_find_close,
            R.id.history_previous_user,
            R.id.history_next_user,
        ).forEach { id ->
            val control = activity.findViewById<View>(id)
            assertTrue("view $id should retain a 44dp width", control.layoutParams.width >= floor)
            assertTrue("view $id should retain a 44dp height", control.layoutParams.height >= floor)
        }
        assertTrue(activity.findViewById<View>(R.id.history_user_jumps).layoutParams.width >= floor)
    }

    @Test
    fun findUsesAHeaderTakeoverAndRestoresTheRouteHeaderWhenClosed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(context, PaneAddress(Scope(), "w1:p1"), "Pane", "codex"),
        ).create().get()

        assertTrue(activity.findViewById<View>(R.id.history_header_normal).visibility == View.VISIBLE)
        assertTrue(activity.findViewById<View>(R.id.history_header_find).visibility != View.VISIBLE)
        activity.findViewById<View>(R.id.history_find_open).performClick()
        assertTrue(activity.findViewById<View>(R.id.history_header_normal).visibility != View.VISIBLE)
        assertTrue(activity.findViewById<View>(R.id.history_header_find).visibility == View.VISIBLE)
        activity.findViewById<View>(R.id.history_find_close).performClick()
        assertTrue(activity.findViewById<View>(R.id.history_header_normal).visibility == View.VISIBLE)
        assertTrue(activity.findViewById<View>(R.id.history_header_find).visibility != View.VISIBLE)
    }

    @Test
    fun systemBackClosesFindBeforeLeavingHistory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(context, PaneAddress(Scope(), "w1:p1"), "Pane", "codex"),
        ).create().start().resume()
        val activity = controller.get()

        activity.findViewById<View>(R.id.history_find_open).performClick()
        activity.onBackPressedDispatcher.onBackPressed()

        assertFalse(activity.isFinishing)
        assertTrue(activity.findViewById<View>(R.id.history_header_normal).visibility == View.VISIBLE)
        assertTrue(activity.findViewById<View>(R.id.history_header_find).visibility != View.VISIBLE)

        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun rendersNewestSixtyThenGrowsTheInMemoryWindowByOneHundredTwenty() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(context, PaneAddress(Scope(), "w1:p1"), "Pane", "codex"),
        ).create().get()
        val values = (1..200).map { index ->
            TranscriptEntry(
                uuid = "u$index",
                ts = "2026-01-01T00:00:00Z",
                role = if (index % 2 == 0) "user" else "assistant",
                parts = listOf(TranscriptPart(kind = "text", text = "message $index")),
            )
        }
        HistoryActivity::class.java.getDeclaredField("entries").apply {
            isAccessible = true
            set(activity, values)
        }
        HistoryActivity::class.java.getDeclaredField("total").apply {
            isAccessible = true
            setInt(activity, values.size)
        }
        HistoryActivity::class.java.getDeclaredMethod("renderEntries").apply {
            isAccessible = true
            invoke(activity)
        }

        assertEquals("60/200", activity.findViewById<TextView>(R.id.history_count).text.toString())
        HistoryActivity::class.java.getDeclaredMethod("growUpward").apply {
            isAccessible = true
            invoke(activity)
        }
        assertEquals("180/200", activity.findViewById<TextView>(R.id.history_count).text.toString())
        HistoryActivity::class.java.getDeclaredMethod("growUpward").apply {
            isAccessible = true
            invoke(activity)
        }
        assertEquals("200/200", activity.findViewById<TextView>(R.id.history_count).text.toString())
    }

    @Test
    fun canonicalHistoryPagingConstantsStayInSyncWithTheWebContract() {
        assertEquals(60, HistoryActivity.INITIAL_RENDER)
        assertEquals(120, HistoryActivity.RENDER_STEP)
        assertEquals(5_000, HistoryActivity.HISTORY_PAGE_SIZE)
        assertEquals(800, HistoryActivity.GROW_THRESHOLD_DP)
    }

    @Test
    fun initialBottomScrollCannotConsumeTheStagedRenderWindow() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(context, PaneAddress(Scope(), "w1:p1"), "Pane", "codex"),
        ).create().get()
        val values = (1..670).map { index ->
            TranscriptEntry(
                uuid = "u$index",
                ts = "2026-01-01T00:00:00Z",
                role = "assistant",
                parts = listOf(TranscriptPart(kind = "text", text = "message $index")),
            )
        }
        HistoryActivity::class.java.getDeclaredField("entries").apply {
            isAccessible = true
            set(activity, values)
        }
        HistoryActivity::class.java.getDeclaredField("total").apply {
            isAccessible = true
            setInt(activity, values.size)
        }
        HistoryActivity::class.java.declaredMethods.single {
            it.name == "renderEntries" && it.parameterCount == 2
        }.apply {
            isAccessible = true
            invoke(activity, null, true)
        }

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("60/670", activity.findViewById<TextView>(R.id.history_count).text.toString())
    }

    @Test
    fun transcriptFindPaintsEveryExactOccurrenceAndTheCurrentOccurrenceDistinctly() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(context, PaneAddress(Scope(), "w1:p1"), "Pane", "codex"),
        ).create().get()
        HistoryActivity::class.java.getDeclaredField("entries").apply {
            isAccessible = true
            set(
                activity,
                listOf(
                    TranscriptEntry(
                        uuid = "u1",
                        ts = "2026-01-01T00:00:00Z",
                        role = "user",
                        parts = listOf(TranscriptPart(kind = "text", text = "Needle then needle")),
                    ),
                ),
            )
        }
        HistoryActivity::class.java.getDeclaredMethod("renderEntries").apply {
            isAccessible = true
            invoke(activity)
        }

        activity.findViewById<EditText>(R.id.history_find_query).setText("needle")

        val prose = activity.findViewById<ViewGroup>(R.id.history_entries)
            .textViews()
            .single { it.text.toString().contains("Needle then needle") }
        val styled = prose.text as Spanned
        val backgrounds = styled.getSpans(0, styled.length, BackgroundColorSpan::class.java)
        assertEquals(2, backgrounds.size)
        assertEquals(
            0,
            backgrounds.count { it.backgroundColor == activity.getColor(R.color.collie_find_current) },
        )
        assertEquals("1/1", activity.findViewById<TextView>(R.id.history_find_count).text.toString())
    }

    @Test
    fun configurationRecreationRetainsTheReaderWindowFindFocusAndExpandedTools() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(context, PaneAddress(Scope(), "w1:p1"), "Pane", "codex"),
        ).create().start().resume()
        val activity = controller.get()
        val values = (1..72).map { index ->
            TranscriptEntry(
                uuid = "u$index",
                ts = "2026-01-01T00:00:00Z",
                role = if (index % 2 == 0) "user" else "assistant",
                parts = buildList {
                    add(TranscriptPart(kind = "text", text = "message $index"))
                    if (index == 64) {
                        add(
                            TranscriptPart(
                                kind = "tool",
                                name = "Read",
                                summary = "fixture",
                                result = TranscriptResult("tool result 64"),
                            ),
                        )
                    }
                },
            )
        }
        HistoryActivity::class.java.getDeclaredField("entries").apply {
            isAccessible = true
            set(activity, values)
        }
        HistoryActivity::class.java.getDeclaredField("total").apply {
            isAccessible = true
            setInt(activity, values.size)
        }
        HistoryActivity::class.java.getDeclaredField("renderCount").apply {
            isAccessible = true
            setInt(activity, 70)
        }
        HistoryActivity::class.java.getDeclaredMethod("renderEntries").apply {
            isAccessible = true
            invoke(activity)
        }
        activity.findViewById<View>(R.id.history_find_open).performClick()
        activity.findViewById<EditText>(R.id.history_find_query).setText("message 64")
        activity.findViewById<View>(R.id.history_find_next).performClick()
        val collapsedTool = activity.findViewById<ViewGroup>(R.id.history_entries)
            .descendants()
            .single { it.contentDescription?.toString() == "Read · fixture, collapsed" }
        collapsedTool.performClick()
        activity.findViewById<View>(R.id.history_root).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val scroll = activity.findViewById<android.widget.ScrollView>(R.id.history_scroll)
        scroll.scrollTo(0, 120)
        val savedScrollY = scroll.scrollY
        assertTrue(savedScrollY > 0)

        controller.configurationChange()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        val recreated = controller.get()

        assertEquals("70/72", recreated.findViewById<TextView>(R.id.history_count).text.toString())
        assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.history_header_find).visibility)
        assertEquals("message 64", recreated.findViewById<EditText>(R.id.history_find_query).text.toString())
        assertTrue(recreated.findViewById<EditText>(R.id.history_find_query).hasFocus())
        assertEquals("u64", HistoryActivity::class.java.getDeclaredField("focusedUuid").apply {
            isAccessible = true
        }.get(recreated))
        assertEquals(0, HistoryActivity::class.java.getDeclaredField("findCursor").apply {
            isAccessible = true
        }.getInt(recreated))
        @Suppress("UNCHECKED_CAST")
        val expanded = HistoryActivity::class.java.getDeclaredField("expandedTools").apply {
            isAccessible = true
        }.get(recreated) as Set<Pair<String, Int>>
        assertTrue("u64" to 1 in expanded)
        assertEquals(72, (HistoryActivity::class.java.getDeclaredField("entries").apply {
            isAccessible = true
        }.get(recreated) as List<*>).size)
        assertTrue(
            recreated.findViewById<ViewGroup>(R.id.history_entries)
                .descendants()
                .any { it.contentDescription?.toString() == "Read · fixture, expanded" },
        )
        assertEquals(savedScrollY, recreated.findViewById<android.widget.ScrollView>(R.id.history_scroll).scrollY)
    }

    private fun ViewGroup.textViews(): List<TextView> = buildList {
        repeat(childCount) {
            when (val child = getChildAt(it)) {
                is TextView -> add(child)
                is ViewGroup -> addAll(child.textViews())
            }
        }
    }

    private fun ViewGroup.descendants(): List<View> = buildList {
        repeat(childCount) {
            val child = getChildAt(it)
            add(child)
            if (child is ViewGroup) addAll(child.descendants())
        }
    }
}
