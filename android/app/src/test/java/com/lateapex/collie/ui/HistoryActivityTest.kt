package com.lateapex.collie.ui

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Base64
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class HistoryActivityTest {
    @Test
    fun retainedHistoryImagesWaitForAnAdmittedReadAndRecoverForTheSameTurn() {
        val app = ApplicationProvider.getApplicationContext<com.lateapex.collie.CollieApplication>()
        val originalRepository = app.container.repository
        val originalLoader = app.container.imageLoader
        val repoField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val loaderField = app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }
        val store = object : com.lateapex.collie.data.ConnectionStore {
            override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(
                com.lateapex.collie.domain.Connection(com.lateapex.collie.domain.CollieOrigin("https://history.fixture/"), "history", "token"))
            override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val png = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val inline = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        var historyCalls = 0
        var responsePage: com.lateapex.collie.network.PaneHistoryResponse? = null
        val api = object : com.lateapex.collie.network.CollieApi by app.container.api {
            override suspend fun history(
                connection: com.lateapex.collie.domain.Connection,
                address: PaneAddress,
                limit: Int,
                before: String?,
                etag: String?,
            ): com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.PaneHistoryResponse> {
                historyCalls++
                val page = com.lateapex.collie.network.PaneHistoryResponse(
                    address.paneId,
                    available = true,
                    entries = listOf(TranscriptEntry(
                        "stable-turn", "2026-01-02T12:34:00Z", "assistant",
                        listOf(TranscriptPart(
                            kind = "tool",
                            name = "capture",
                            result = TranscriptResult(text = null, imageUrl = inline),
                            mimeType = "image/png",
                        )),
                    )),
                )
                responsePage = page
                return com.lateapex.collie.network.ApiResult.Success(page, 200)
            }
        }
        val repository = com.lateapex.collie.data.CollieRepository(api, store, app.container.originValidator)
        val loader = com.lateapex.collie.data.ScopedImageLoader(repository)
        repoField.set(app.container, repository)
        loaderField.set(app.container, loader)
        val controller = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(app, PaneAddress(Scope("peer", "work"), "pane"), "Pane", "codex"),
        ).create().start().resume()
        try {
            var activity = controller.get()
            val deadline = System.currentTimeMillis() + 5_000
            var collapsedTool: View? = null
            while (collapsedTool == null && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                collapsedTool = activity.findViewById<ViewGroup>(R.id.history_entries).descendants()
                    .firstOrNull { it.contentDescription?.toString()?.contains("collapsed", ignoreCase = true) == true }
                if (collapsedTool == null) Thread.sleep(10)
            }
            assertTrue("history renders the image-only tool header", collapsedTool != null)
            val expectedImage = requireNotNull(responsePage).entries.single().parts.single().result?.imageUrl
            assertEquals(inline, expectedImage)
            val projectedPart = HistoryPresentation.turn(requireNotNull(responsePage).entries.single(), "codex", activity.resources)
                .parts.single() as HistoryPart.Tool
            assertEquals(inline, projectedPart.image?.reference)
            assertTrue("actual tool header touch expands the image-only result", collapsedTool!!.performClick())
            val expandedHeader = activity.findViewById<ViewGroup>(R.id.history_entries).descendants()
                .firstOrNull { it.contentDescription?.toString()?.contains("expanded", ignoreCase = true) == true }
            val expandedToolsField = HistoryActivity::class.java.getDeclaredField("expandedTools").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val expandedTools = expandedToolsField.get(activity) as Set<Pair<String, Int>>
            assertTrue("header click must retain the expected UUID/part expansion key", ("stable-turn" to 0) in expandedTools)
            val entriesField = HistoryActivity::class.java.getDeclaredField("entries").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val activityEntries = entriesField.get(activity) as List<TranscriptEntry>
            val activityTool = HistoryPresentation.turn(activityEntries.single(), "codex", activity.resources).parts.single() as HistoryPart.Tool
            assertEquals("Activity page model must retain the image URL", inline, activityTool.image?.reference)
            val rendererField = HistoryActivity::class.java.getDeclaredField("renderer").apply { isAccessible = true }
            val renderer = rendererField.get(activity) as HistoryTurnRenderer
            val directTurn = HistoryPresentation.turn(activityEntries.single(), "codex", activity.resources)
            val directCards = renderer.turnView(directTurn, showHeader = false).let { view ->
                (view as ViewGroup).descendants().filterIsInstance<AuthenticatedImageView>()
            }
            assertEquals("same renderer plus expanded UUID/index constructs the image child", 1, directCards.size)
            val liveHierarchy = activity.findViewById<ViewGroup>(R.id.history_entries).descendants()
            assertTrue("expanded header remains in the live hierarchy", expandedHeader != null)
            val liveCards = liveHierarchy.filterIsInstance<AuthenticatedImageView>()
            assertEquals("Activity render must include the expanded image card", 1, liveCards.size)
            val firstCard = liveCards.single()
            assertFalse("test avoids detached-window attachment as a proxy for render construction", firstCard.isAttachedToWindow)

            // This performs an actual configuration-driven Activity replacement and transfers the
            // non-configuration ViewModel store, without ActivityController.recreate()'s final
            // visible() call draining an unbounded Robolectric Choreographer/layout frame loop.
            // The fixture remains deliberately window-detached; attached image loading is covered
            // by AuthenticatedImageView tests and real-device acceptance.
            val preConfigurationActivity = activity
            controller.recreateForTestWithoutVisibleFrameDrain()
            activity = controller.get()
            assertFalse("configuration change replaces the Activity instance", activity === preConfigurationActivity)
            val recreatedCard = activity.findViewById<ViewGroup>(R.id.history_entries).descendants()
                .filterIsInstance<AuthenticatedImageView>().single()
            assertTrue("recreation rebuilds the retained image card", recreatedCard !== firstCard)
            assertNull("recreated retained rows remain inert until a fresh page is accepted",
                (recreatedCard.getChildAt(0) as android.widget.ImageView).drawable)
            assertEquals("recreation alone does not grant old rows a new actor", 1, historyCalls)
            assertTrue("exhausted History exposes its explicit refresh action after recreation",
                activity.findViewById<View>(R.id.history_refresh).visibility == View.VISIBLE)

            repository.showSavedCopy(456L)
            shadowOf(Looper.getMainLooper()).idle()
            val inertCard = activity.findViewById<ViewGroup>(R.id.history_entries).descendants()
                .filterIsInstance<AuthenticatedImageView>().single()
            assertTrue("mere redraw does not retarget retained entries to new authority", inertCard !== firstCard)
            assertNull((inertCard.getChildAt(0) as android.widget.ImageView).drawable)
            assertFalse("unattached test window has not started the image card lifecycle", inertCard.isAttachedToWindow)
            val cardContextField = AuthenticatedImageView::class.java.getDeclaredField("readContext").apply { isAccessible = true }
            assertNull("saved-copy redraw must not mint a renderer actor", cardContextField.get(inertCard))
            val refresh = activity.findViewById<View>(R.id.history_refresh)
            assertTrue("retired retained History exposes a reachable fresh-read action", refresh.visibility == View.VISIBLE)
            assertTrue("reader can explicitly refresh image authority", refresh.performClick())
            val refreshDeadline = System.currentTimeMillis() + 5_000
            var recovered: AuthenticatedImageView? = null
            while ((historyCalls < 2 || recovered == null) && System.currentTimeMillis() < refreshDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                recovered = activity.findViewById<ViewGroup>(R.id.history_entries).descendants()
                    .filterIsInstance<AuthenticatedImageView>().firstOrNull()
                if (historyCalls < 2 || recovered == null) Thread.sleep(10)
            }
            assertEquals("fresh admitted page is required after saved-copy invalidation", 2, historyCalls)
            assertTrue("a new admitted History page recreates image actors for unchanged UUIDs", recovered != null)
            assertTrue(recovered !== firstCard)
            val refreshedContext = cardContextField.get(recovered) as? com.lateapex.collie.data.ScopedReadContext
            assertNotNull("fresh current page owns the recreated image card", refreshedContext)
            assertTrue(repository.isCurrent(requireNotNull(refreshedContext)))
            assertNull("window attachment and decode are covered by the visible host tests", (recovered!!.getChildAt(0) as android.widget.ImageView).drawable)
        } finally {
            controller.pause().stop().destroy()
            loader.close()
            repoField.set(app.container, originalRepository)
            loaderField.set(app.container, originalLoader)
        }
    }

    @Test
    fun partialHistoryRefreshFailurePreservesAdmittedRowsAndRetryCursor() {
        runPartialHistoryRefresh(
            com.lateapex.collie.network.ApiResult.Failure(
                com.lateapex.collie.network.ApiFailure.Http(503, "Temporary service failure", "server-error"),
            ),
        ) { "Temporary service failure" }
    }

    @Test
    fun partialHistoryRefreshNotModifiedKeepsSuccessfulActorsAndRetryCursor() {
        runPartialHistoryRefresh(com.lateapex.collie.network.ApiResult.NotModified(null)) {
            it.getString(R.string.history_load_failed)
        }
    }

    @Test
    fun partialHistoryRefreshUnavailablePageKeepsSuccessfulActorsAndRetryCursor() {
        runPartialHistoryRefresh(
            com.lateapex.collie.network.ApiResult.Success(
                com.lateapex.collie.network.PaneHistoryResponse(
                    "pane", available = false, reason = "no-log",
                ),
                200,
            ),
        ) { it.getString(R.string.history_unavailable_no_log) }
    }

    private fun runPartialHistoryRefresh(
        partialFailure: com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.PaneHistoryResponse>,
        expectedFailure: (HistoryActivity) -> String,
    ) {
        val app = ApplicationProvider.getApplicationContext<com.lateapex.collie.CollieApplication>()
        val originalRepository = app.container.repository
        val originalLoader = app.container.imageLoader
        val repoField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val loaderField = app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }
        val store = object : com.lateapex.collie.data.ConnectionStore {
            override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(
                com.lateapex.collie.domain.Connection(
                    com.lateapex.collie.domain.CollieOrigin("https://history.fixture/"), "history", "token",
                ),
            )
            override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val inline = "data:image/png;base64,AA=="
        fun imageEntry(uuid: String) = TranscriptEntry(
            uuid, "2026-01-02T12:34:00Z", "assistant",
            listOf(TranscriptPart(
                "tool",
                name = "capture",
                result = TranscriptResult(text = "Image output", imageUrl = inline),
                mimeType = "image/png",
            )),
        )
        val oldest = imageEntry("oldest-image")
        val newest = imageEntry("newest-image")
        val responses = mutableListOf<com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.PaneHistoryResponse>>(
            com.lateapex.collie.network.ApiResult.Success(
                com.lateapex.collie.network.PaneHistoryResponse(
                    "pane", available = true, entries = listOf(oldest, newest), hasMore = false, total = 2,
                ),
                200,
            ),
            com.lateapex.collie.network.ApiResult.Success(
                com.lateapex.collie.network.PaneHistoryResponse(
                    "pane", available = true, entries = listOf(newest), hasMore = true, total = 2,
                ),
                200,
            ),
            partialFailure,
            com.lateapex.collie.network.ApiResult.Success(
                com.lateapex.collie.network.PaneHistoryResponse(
                    "pane", available = true, entries = listOf(oldest), hasMore = false, total = 2,
                ),
                200,
            ),
        )
        val beforeRequests = mutableListOf<String?>()
        var historyCalls = 0
        val api = object : com.lateapex.collie.network.CollieApi by app.container.api {
            override suspend fun history(
                connection: com.lateapex.collie.domain.Connection,
                address: PaneAddress,
                limit: Int,
                before: String?,
                etag: String?,
            ): com.lateapex.collie.network.ApiResult<com.lateapex.collie.network.PaneHistoryResponse> {
                historyCalls++
                beforeRequests += before
                return responses.removeAt(0)
            }
        }
        val repository = com.lateapex.collie.data.CollieRepository(api, store, app.container.originValidator)
        val loader = com.lateapex.collie.data.ScopedImageLoader(repository)
        repoField.set(app.container, repository)
        loaderField.set(app.container, loader)
        val controller = Robolectric.buildActivity(
            HistoryActivity::class.java,
            HistoryActivity.intent(app, PaneAddress(Scope("peer", "work"), "pane"), "Pane", "codex"),
        ).create().start().resume()
        try {
            val initialDeadline = System.currentTimeMillis() + 5_000
            while (historyCalls < 1 && System.currentTimeMillis() < initialDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals("initial History page is accepted", 1, historyCalls)

            // Expand actual text-plus-image tool results so the refreshed row exercises the
            // UUID-bound tool-image branch, while the retained older row exercises its refusal.
            var activity = controller.get()
            val renderDeadline = System.currentTimeMillis() + 5_000
            while (activity.findViewById<ViewGroup>(R.id.history_entries).descendants()
                    .count { it.contentDescription?.toString()?.contains("collapsed", ignoreCase = true) == true } < 2 &&
                System.currentTimeMillis() < renderDeadline
            ) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            repeat(2) {
                val collapsed = activity.findViewById<ViewGroup>(R.id.history_entries).descendants()
                    .first { it.contentDescription?.toString()?.contains("collapsed", ignoreCase = true) == true }
                assertTrue("text-plus-image tool result expands through its actual header", collapsed.performClick())
            }
            assertEquals(2, activity.findViewById<ViewGroup>(R.id.history_entries).descendants()
                .filterIsInstance<AuthenticatedImageView>().size)

            // Simulate retained rows after recreation: their previous page actor is deliberately
            // absent in the new Activity, so only later successful refresh pages can restore it.
            val preConfigurationActivity = activity
            controller.recreateForTestWithoutVisibleFrameDrain()
            activity = controller.get()
            assertFalse("configuration change replaces the Activity instance", activity === preConfigurationActivity)
            assertEquals(1, historyCalls)
            repository.showSavedCopy(456L)
            shadowOf(Looper.getMainLooper()).idle()
            val refresh = activity.findViewById<View>(R.id.history_refresh)
            assertEquals(View.VISIBLE, refresh.visibility)
            assertTrue("the real visible refresh action is clickable", refresh.performClick())

            val partialDeadline = System.currentTimeMillis() + 5_000
            while (historyCalls < 3 && System.currentTimeMillis() < partialDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("one successful page then the selected refusal were requested", 3, historyCalls)
            activity = controller.get()
            assertEquals(listOf(null, null, "newest-image"), beforeRequests)
            val turnsField = HistoryActivity::class.java.getDeclaredField("turnViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val turnViews = turnsField.get(activity) as Map<String, View>
            val contextField = AuthenticatedImageView::class.java.getDeclaredField("readContext").apply { isAccessible = true }
            fun actor(uuid: String): com.lateapex.collie.data.ScopedReadContext? {
                val turn = turnViews.getValue(uuid) as ViewGroup
                val card = turn.descendants().filterIsInstance<AuthenticatedImageView>().single()
                return contextField.get(card) as? com.lateapex.collie.data.ScopedReadContext
            }
            val successfulActor = requireNotNull(actor("newest-image"))
            assertTrue("the current successful page keeps its image actor", repository.isCurrent(successfulActor))
            assertNull("the older retained row stays inert until its own page succeeds", actor("oldest-image"))
            assertEquals(expectedFailure(activity), activity.findViewById<TextView>(R.id.history_state).text.toString())
            assertEquals("the partial error leaves an accessible retry visible", View.VISIBLE, refresh.visibility)
            assertTrue("the retry settles enabled after refusal", refresh.isEnabled)
            assertFalse("the refresh spinner is settled",
                activity.findViewById<View>(R.id.history_progress).visibility == View.VISIBLE)

            assertTrue("retry resumes from the last successful page cursor", refresh.performClick())
            val retryDeadline = System.currentTimeMillis() + 5_000
            while (historyCalls < 4 && System.currentTimeMillis() < retryDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(4, historyCalls)
            assertEquals(listOf(null, null, "newest-image", "newest-image"), beforeRequests)
            assertNotNull("the previously admitted UUID remains valid", actor("newest-image"))
            assertNotNull("the successful retry admits the remaining older UUID", actor("oldest-image"))
            assertTrue("success clears the partial error state",
                activity.findViewById<TextView>(R.id.history_state).text.toString() != expectedFailure(activity))
            assertFalse("all admitted rows hide the retry control", refresh.visibility == View.VISIBLE)
            assertFalse("spinner remains settled after retry",
                activity.findViewById<View>(R.id.history_progress).visibility == View.VISIBLE)
        } finally {
            controller.pause().stop().destroy()
            loader.close()
            repoField.set(app.container, originalRepository)
            loaderField.set(app.container, originalLoader)
        }
    }

    @Test
    fun changingTheConnectionClosesTheOldServerHistory() {
        val app = ApplicationProvider.getApplicationContext<com.lateapex.collie.CollieApplication>()
        val store = object : com.lateapex.collie.data.ConnectionStore {
            override val connection = kotlinx.coroutines.flow.MutableStateFlow<com.lateapex.collie.domain.Connection?>(
                com.lateapex.collie.domain.Connection(com.lateapex.collie.domain.CollieOrigin("https://history.fixture/"), "history", null))
            override fun save(connection: com.lateapex.collie.domain.Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        val api = object : com.lateapex.collie.network.CollieApi by app.container.api {
            override suspend fun history(connection: com.lateapex.collie.domain.Connection,
                address: PaneAddress, limit: Int, before: String?, etag: String?) =
                com.lateapex.collie.network.ApiResult.Success(
                    com.lateapex.collie.network.PaneHistoryResponse(address.paneId, available = true, entries = listOf(
                        TranscriptEntry("secret-turn", "2026-01-01T00:00:00Z", "user", listOf(TranscriptPart("text", text = "sensitive body"))))), 200)
        }
        val repository = com.lateapex.collie.data.CollieRepository(api, store, app.container.originValidator)
        val original = app.container.repository
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(HistoryActivity::class.java,
            HistoryActivity.intent(app, PaneAddress(Scope(), "w1:p1"), "Pane", "codex"))
            .create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(controller.get().isFinishing)
            controller.saveInstanceState(android.os.Bundle())
            val retained = androidx.lifecycle.ViewModelProvider(controller.get())[HistoryReaderViewModel::class.java]
            assertTrue(retained.state?.entries?.isNotEmpty() == true)
            kotlinx.coroutines.runBlocking { repository.disconnect() }
            assertEquals(null, retained.state)
            assertEquals(null, retained.connection)
            assertEquals("", retained.findQuery)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.get().isFinishing)
        } finally {
            controller.pause().stop().destroy()
            field.set(app.container, original)
        }
    }

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
    fun historyNavigationAndSearchControlsKeepTheFortyEightDpTouchFloor() {
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
            assertTrue("view $id should retain a 48dp width", control.layoutParams.width >= floor)
            assertTrue("view $id should retain a 48dp height", control.layoutParams.height >= floor)
        }
        assertTrue(activity.findViewById<View>(R.id.history_user_jumps).layoutParams.width >= floor)
        assertTrue(activity.findViewById<View>(R.id.history_load_older).layoutParams.height >= floor)
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
        // Nothing is current until the reader steps, so the counter must not claim match 1.
        assertEquals("0/1", activity.findViewById<TextView>(R.id.history_find_count).text.toString())
        activity.findViewById<View>(R.id.history_find_next).performClick()
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
            .single { it.contentDescription?.toString() == "Read · fixture · Completed, collapsed" }
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
                .any { it.contentDescription?.toString() == "Read · fixture · Completed, expanded" },
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

/**
 * Robolectric's generic recreate() calls visible(), which drains Choreographer frames until idle.
 * History's transcript hierarchy schedules another layout frame during that drain, so the helper
 * can stay inside pre-draw indefinitely. configurationChange() performs the same Activity
 * replacement and non-configuration retention path, but its implementation does not force the
 * replacement window visible or wait for frame quiescence.
 */
private fun ActivityController<HistoryActivity>.recreateForTestWithoutVisibleFrameDrain() {
    val configuration = Configuration(get().resources.configuration)
    configuration.orientation = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Configuration.ORIENTATION_PORTRAIT
    } else {
        Configuration.ORIENTATION_LANDSCAPE
    }
    configurationChange(configuration)
}
