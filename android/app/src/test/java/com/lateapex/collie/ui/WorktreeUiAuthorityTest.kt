package com.lateapex.collie.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WorktreeUiAuthorityTest {
    @Before fun bindCurrentAndroidMainLooper() {
        // PAUSED Robolectric replaces its main Looper between method sandboxes. The
        // coroutine Main dispatcher is otherwise cached for the previous Handler.
        // Preserve actual Android Main dispatch/ACK rather than running UI work off-main.
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher("worktree-fixture-main"))
    }

    @After fun restoreMainDispatcher() { Dispatchers.resetMain() }

    @Test fun stoppedActualMainFormWipesRetainedInputsAndOldClickCannotPlanButFreshFormCanRead(): Unit = runBlocking {
        withMain { fixture ->
            val a = fixture.activity
            awaitCurrentRoot(fixture)
            a.startWorktreeFlowForTest()
            val dialog = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
            val input = dialog.findViewById<EditText>(R.id.worktree_branch_input)!!
            input.setText("private-branch")
            val create = texts(dialog.window!!.decorView).single { it.text.toString() == a.getString(R.string.action_create) }
            val reads = fixture.lists
            fixture.controller.pause().stop()
            assertEquals("", input.text.toString())
            assertFalse(dialog.isShowing)
            create.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, fixture.plans)
            fixture.controller.start().resume()
            awaitCurrentRoot(fixture)
            a.startWorktreeFlowForTest()
            assertTrue((ShadowDialog.getLatestDialog() as CollieBottomSheetDialog).isShowing)
            assertTrue(fixture.lists > reads)
        }
    }

    @Test fun actualEmptyRepositoryChoiceRefusesWithoutConstructingAFormOrPlanning(): Unit = runBlocking {
        withMain { fixture ->
            val activity = fixture.activity
            val method = MainActivity::class.java.getDeclaredMethod("chooseWorktreeRepo", List::class.java, Scope::class.java).apply { isAccessible = true }
            val previous = ShadowDialog.getLatestDialog()
            method.invoke(activity, emptyList<WorkspaceSummary>(), Scope())
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(previous, ShadowDialog.getLatestDialog())
            assertEquals(0, fixture.plans)
            assertEquals(0, fixture.creates[0])
            assertEquals(0, fixture.removals[0])
        }
    }

    @Test fun offMainSameObjectRecommitAfterClearAcknowledgesCopiedFormRetirementAndNeverBorrowsFreshOwner(): Unit = runBlocking {
        withMain { fixture ->
            fixture.activity.startWorktreeFlowForTest()
            val old = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
            val input = old.findViewById<EditText>(R.id.worktree_branch_input)!!
            input.setText("retired-secret")
            val create = texts(old.window!!.decorView).single { it.text.toString() == fixture.activity.getString(R.string.action_create) }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val replaced = pool.submit { fixture.app.container.connectionStore.clear(); fixture.app.container.connectionStore.save(fixture.connection) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!replaced.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
                replaced.get(1, TimeUnit.SECONDS)
                assertFalse(old.isShowing)
                assertEquals("", input.text.toString())
                create.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, fixture.plans)
                assertNotNull(fixture.repository.readContext())
                fixture.activity.startWorktreeFlowForTest()
                assertTrue((ShadowDialog.getLatestDialog() as CollieBottomSheetDialog).isShowing)
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun actualForceReviewHeldAcrossReplacementCannotReopenOrDispatchAndFreshOwnerStillReads(): Unit = runBlocking {
        withMain { f ->
            val actor = f.repository.readContext()!!
            val lease = WorktreeUiLease(f.activity, f.repository, actor)
            val entry = MainActivity::class.java.declaredMethods.single { it.name == "confirmRemoveWorktree" }.apply { isAccessible = true }
            entry.invoke(f.activity, "linked", "topic", "/repo/topic", Scope(), lease)
            shadowOf(Looper.getMainLooper()).idle()
            val force = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(force.isShowing)
            val button = force.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            assertEquals(f.activity.getString(R.string.worktree_force_review), button.text.toString())
            button.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(f.forcedEntered.isCompleted)
            f.app.container.connectionStore.clear()
            f.app.container.connectionStore.save(Connection(f.connection.origin, "replacement", "replacement-fixture"))
            assertFalse(force.isShowing)
            f.forcedReply.complete(WorktreeRemovePlanResponse(true, 1, "linked", "topic", "/repo/topic", "/repo", "topic", 1, true, 0, "force-plan", System.currentTimeMillis() + 60_000, false))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(ShadowDialog.getLatestDialog().isShowing)
            assertEquals(0, f.removals[0])
            assertFalse(f.repository.isCurrent(actor))
            assertNotNull(f.repository.readContext())
            assertTrue(f.repository.folders(Scope(), f.repository.readContext()) is ApiResult.Success)
        }
    }

    @Test fun delayedOldNewFormCleanupWipesItsActualReviewButPreservesFreshOwnerFormAndGesture(): Unit = runBlocking {
        withMain { f ->
            val controller = Robolectric.buildActivity(NewActivity::class.java, NewActivity.intent(f.app, Scope()))
            val activity = controller.get()
            NewActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(activity, f.repository)
            controller.create().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val finished = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            // This public callback precedes the form lease's listener. It holds only the old
            // cleanup delivery; the committed replacement and successor actor remain real.
            val blocker = f.repository.onReadContextInvalidated { reason ->
                if (reason == com.lateapex.collie.data.AuthorityInvalidation.CONNECTION_CHANGED) {
                    entered.countDown()
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)) } catch (error: Throwable) { failure.set(error) }
                    finally { finished.countDown() }
                }
            }
            fun input(name: String) = NewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as EditText
            val launch = NewActivity::class.java.getDeclaredField("launchButton").apply { isAccessible = true }.get(activity) as com.google.android.material.button.MaterialButton
            val toggle = NewActivity::class.java.getDeclaredField("worktreeToggle").apply { isAccessible = true }.get(activity) as com.google.android.material.switchmaterial.SwitchMaterial
            try {
                input("cwd").setText("/repo")
                input("worktreeBranch").setText("old-topic")
                toggle.isChecked = true
                assertEquals("Manual repository choice survives the actual worktree toggle", "/repo", input("cwd").text.toString())
                assertTrue(launch.isEnabled)
                launch.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertNotNull("Actual worktree review after the deliberate click: plans=${f.plans}, " +
                    "launchEnabled=${launch.isEnabled}, " +
                    "status=${(NewActivity::class.java.getDeclaredField("status").apply { isAccessible = true }.get(activity) as TextView).text}", ShadowDialog.getLatestDialog())
                val oldReview = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                assertTrue(oldReview.isShowing)
                val retainedBody = texts(oldReview.window!!.decorView).first { it.text.toString().contains("old-topic") }
                val oldCreate = oldReview.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                assertEquals(1, f.plans)
                val a = f.repository.readContext()!!
                f.app.container.connectionStore.save(Connection(f.connection.origin, "fresh-owner", "fresh-owner-token"))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                shadowOf(Looper.getMainLooper()).idle()
                NewActivity::class.java.getDeclaredMethod("loadCatalog").apply { isAccessible = true }.invoke(activity)
                shadowOf(Looper.getMainLooper()).idle()
                val b = f.repository.readContext()!!
                assertFalse(f.repository.isCurrent(a))
                assertTrue(f.repository.isCurrent(b))
                input("cwd").setText("/repo")
                input("worktreeBranch").setText("fresh-topic")
                texts(activity.window.decorView).filterIsInstance<android.widget.RadioButton>().first { it.isEnabled }.performClick()
                assertEquals("Fresh owner's manual repository survives its deliberate target choice", "/repo", input("cwd").text.toString())
                assertTrue(launch.isEnabled)
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (oldReview.isShowing && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
                assertNull(failure.get())
                assertFalse(oldReview.isShowing)
                assertEquals("", retainedBody.text.toString())
                assertEquals("/repo", input("cwd").text.toString())
                assertEquals("fresh-topic", input("worktreeBranch").text.toString())
                assertTrue(launch.isEnabled)
                oldCreate.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, f.creates[0])
                launch.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val freshReview = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                assertNotSame(oldReview, freshReview)
                assertTrue(freshReview.isShowing)
                assertTrue(texts(freshReview.window!!.decorView).any { it.text.toString().contains("fresh-topic") })
                assertEquals(2, f.plans)
                assertEquals(0, f.creates[0])
            } finally { release.countDown(); blocker.close(); controller.pause().stop().destroy() }
        }
    }

    @Test fun repeatedCurrentMainCancelAndSupersededFormsReleaseCopiesAndSubscriptionsWhileResumed(): Unit = runBlocking {
        withMain { f ->
            awaitCurrentRoot(f)
            repeat(24) {
                f.activity.startWorktreeFlowForTest()
                val old = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
                val input = old.findViewById<EditText>(R.id.worktree_branch_input)!!
                input.setText("private-current-$it")
                assertEquals(1, WorktreeUiLease.activeCountForTesting(f.activity))
                f.activity.startWorktreeFlowForTest()
                assertFalse(old.isShowing); assertEquals("", input.text.toString())
                assertEquals(1, WorktreeUiLease.activeCountForTesting(f.activity))
                (ShadowDialog.getLatestDialog() as CollieBottomSheetDialog).dismiss()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, WorktreeUiLease.activeCountForTesting(f.activity))
                assertEquals(0, f.plans); assertEquals(0, f.creates[0]); assertEquals(0, f.removals[0])
            }
            assertTrue(f.controller.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            f.activity.startWorktreeFlowForTest()
            assertEquals(1, WorktreeUiLease.activeCountForTesting(f.activity))
            f.controller.pause().stop()
            assertEquals(0, WorktreeUiLease.activeCountForTesting(f.activity))
        }
    }

    @Test fun retainedActualOpenGestureIsConsumedOnceAndFreshReviewCanOpenAgain(): Unit = runBlocking {
        withMain { f ->
            awaitCurrentRoot(f)
            val actor = f.repository.readContext()!!
            var opens = 0
            f.activity.worktreeOpener = { _, _, _ -> opens++; ApiResult.Failure(ApiFailure.Network("controlled uncertain open")) }
            val method = MainActivity::class.java.declaredMethods.single { it.name == "confirmOpenWorktree" }.apply { isAccessible = true }
            val root = (MainActivity::class.java.getDeclaredField("latestShellState").apply { isAccessible = true }.get(f.activity) as MainUiState).snapshot!!.workspaces.single { it.workspaceId == "root" }
            val worktree = Worktree(path = "/repo/topic", branch = "topic", openWorkspaceId = null, linked = true, prunable = false)
            fun review(): android.widget.TextView {
                val lease = WorktreeUiLease(f.activity, f.repository, actor)
                method.invoke(f.activity, root, worktree, Scope(), lease)
                val dialog = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog
                return texts(dialog.window!!.decorView).single { it.text.toString() == f.activity.getString(R.string.action_open) }
            }
            val first = review()
            val listener = org.robolectric.shadows.ShadowView::class.java.getMethod("getOnClickListener").invoke(shadowOf(first)) as View.OnClickListener
            listener.onClick(first)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, opens)
            listener.onClick(first)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, opens)
            assertEquals(0, WorktreeUiLease.activeCountForTesting(f.activity))
            review().performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, opens)
        }
    }

    @Test fun actualNewInvalidAndCancelledReviewsReleaseLeaseWhileCurrentTypedFormRemainsUsable(): Unit = runBlocking {
        withMain { f ->
            val controller = Robolectric.buildActivity(NewActivity::class.java, NewActivity.intent(f.app, Scope()))
            val activity = controller.get()
            NewActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(activity, f.repository)
            controller.create().start().resume(); shadowOf(Looper.getMainLooper()).idle()
            fun input(name: String) = NewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as EditText
            val launch = NewActivity::class.java.getDeclaredField("launchButton").apply { isAccessible = true }.get(activity) as com.google.android.material.button.MaterialButton
            val toggle = NewActivity::class.java.getDeclaredField("worktreeToggle").apply { isAccessible = true }.get(activity) as com.google.android.material.switchmaterial.SwitchMaterial
            try {
                input("cwd").setText("/repo"); toggle.isChecked = true
                repeat(16) {
                    input("worktreeBranch").setText("")
                    launch.performClick(); shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(0, WorktreeUiLease.activeCountForTesting(activity))
                    assertEquals("/repo", input("cwd").text.toString())
                }
                val plans = f.plans
                repeat(16) {
                    input("worktreeBranch").setText("current-$it")
                    launch.performClick(); shadowOf(Looper.getMainLooper()).idle()
                    val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                    assertTrue(dialog.isShowing); assertEquals(1, WorktreeUiLease.activeCountForTesting(activity))
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(dialog.isShowing); assertEquals(0, WorktreeUiLease.activeCountForTesting(activity))
                    assertEquals("/repo", input("cwd").text.toString()); assertEquals(0, f.creates[0])
                }
                assertEquals(plans + 16, f.plans)
                input("worktreeBranch").setText("fresh-current")
                launch.performClick(); shadowOf(Looper.getMainLooper()).idle()
                assertTrue(ShadowDialog.getLatestDialog().isShowing)
                controller.pause().stop()
                assertEquals(0, WorktreeUiLease.activeCountForTesting(activity))
                assertEquals("", input("worktreeBranch").text.toString())
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun retainedCancelledInvalidAndRefusedNewFormsRetireWithTheirSavedCopiesAfterActualDisconnectOrPairRefusal(): Unit = runBlocking {
        for (mode in listOf("cancelled", "invalid", "refused")) for (pairing in listOf(false, true)) withMain { f ->
            // Robolectric has no hardware Android Keystore. Replace only crypto in this same
            // actual AppContainer journal; retirement/persistence/authority and assertions stay real.
            com.lateapex.collie.data.LaunchIntentStore::class.java.getDeclaredField("cipher").apply {
                isAccessible = true
                set(f.app.container.launchIntentStore, com.lateapex.collie.data.AuthenticatedLaunchIntentFixtureCipher())
            }
            val base = CollieRepository::class.java.getDeclaredField("api").apply { isAccessible = true }.get(f.repository) as CollieApi
            var pairExpired = false
            val api = object : CollieApi by base {
                override suspend fun planWorktree(connection: Connection, scope: Scope, cwd: String, branch: String?, parent: String?): ApiResult<WorktreePlanResponse> {
                    val result = base.planWorktree(connection, scope, cwd, branch, parent)
                    return if (mode == "refused" && result is ApiResult.Success) ApiResult.Success(result.value.copy(branchValid = false), 200) else result
                }
                override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean): ApiResult<PaneReadResponse> =
                    if (pairExpired) ApiResult.Failure(ApiFailure.Http(401, "controlled authoritative refusal", "pairing.expired", source = FailureSource.COLLIE, factor = FailureFactor.PAIRING)) else base.pane(connection, address, lines, etag, markSeen)
            }
            val repo = CollieRepository(api, f.app.container.connectionStore, f.app.container.originValidator, launchIntentStore = f.app.container.launchIntentStore)
            val screen = newFormScreen(f, repo)
            try {
                screen.input("cwd").setText(if (mode == "invalid") "" else "/repo")
                screen.input("worktreeBranch").setText("private-original-topic")
                screen.toggle.isChecked = true
                assertTrue(screen.launch.isEnabled); screen.launch.performClick(); shadowOf(Looper.getMainLooper()).idle()
                if (mode == "cancelled") {
                    val review = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                    assertTrue(review.isShowing); review.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(review.isShowing)
                }
                assertEquals(0, WorktreeUiLease.activeCountForTesting(screen.activity))
                screen.input("cwd").setText("/private-original-folder")
                screen.input("worktreeBaseRef").setText("private-original-ref")
                screen.input("worktreeParent").setText("/private-original-parent")
                val saved = android.os.Bundle(); screen.controller.saveInstanceState(saved)
                assertEquals("/private-original-folder", saved.getString("new.cwd"))
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val retired = pool.submit {
                        if (pairing) { pairExpired = true; runBlocking { assertTrue(repo.readPane(com.lateapex.collie.domain.PaneAddress(paneId = "p")) is ApiResult.Failure) } }
                        else runBlocking { repo.disconnect() }
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!retired.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
                    retired.get(1, TimeUnit.SECONDS)
                } finally { pool.shutdownNow() }
                for (name in listOf("cwd", "worktreeBranch", "worktreeBaseRef", "worktreeParent")) assertEquals("", screen.input(name).text.toString())
                for (key in listOf("new.cwd", "new.worktreeBranch", "new.worktreeBase", "new.worktreeParent")) assertEquals("", saved.getString(key).orEmpty())
                assertFalse(saved.getBoolean("new.folderSelected")); assertNull(saved.getString("new.formOwner"))
                screen.launch.performClick(); shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, f.creates[0])
                assertTrue(f.app.container.launchIntentStore.all().isEmpty())
            } finally { screen.controller.pause().stop().destroy() }
        }
    }

    @Test fun currentCancelledFormRotationPreservesManualInputsButReplacementCannotAdoptTheSameSavedStrings(): Unit = runBlocking {
        withMain { f ->
            var screen = newFormScreen(f, f.repository)
            val saved = android.os.Bundle()
            val delivered = java.util.concurrent.CountDownLatch(1)
            val successorWitness = java.util.concurrent.atomic.AtomicReference<com.lateapex.collie.data.CommittedConnectionSnapshot?>()
            var publicationTail: AutoCloseable? = null
            try {
                screen.input("cwd").setText("/repo"); screen.input("worktreeBranch").setText("current-topic"); screen.toggle.isChecked = true
                screen.launch.performClick(); shadowOf(Looper.getMainLooper()).idle()
                val review = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                review.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
                screen.input("worktreeBaseRef").setText("current-ref"); screen.input("worktreeParent").setText("/current-parent")
                screen.controller.saveInstanceState(saved)
                assertNotNull(saved.getString("new.formOwner")); assertNotNull(saved.getString("new.formIncarnation"))
                assertFalse(saved.toString().contains(f.connection.token!!))
                screen.controller.pause().stop().destroy()
                screen = newFormScreen(f, f.repository, saved)
                assertEquals("/repo", screen.input("cwd").text.toString()); assertEquals("current-topic", screen.input("worktreeBranch").text.toString())
                assertEquals("current-ref", screen.input("worktreeBaseRef").text.toString()); assertEquals("/current-parent", screen.input("worktreeParent").text.toString())
                assertEquals(0, WorktreeUiLease.activeCountForTesting(screen.activity)); assertEquals(0, f.creates[0])
                val successorSaved = android.os.Bundle(); screen.controller.saveInstanceState(successorSaved)
                screen.controller.pause().stop().destroy()
                // This NEW rotation fixture also owns an asynchronous publication. Register
                // before replacement, and keep its exact real Main ACKs inside this sandbox.
                publicationTail = f.app.container.connectionStore.observeCommitted { committed ->
                    val expected = successorWitness.get()
                    if (expected != null && committed.revision == expected.revision &&
                        committed.incarnationId == expected.incarnationId && committed.connection === expected.connection) delivered.countDown()
                }
                val successor = Connection(f.connection.origin, "replacement", "replacement-form-token")
                f.app.container.connectionStore.save(successor)
                val committedSuccessor = checkNotNull(f.app.container.connectionStore.captureCommitted())
                assertSame(successor, committedSuccessor.connection)
                successorWitness.set(committedSuccessor)
                screen = newFormScreen(f, f.repository, successorSaved)
                for (name in listOf("cwd", "worktreeBranch", "worktreeBaseRef", "worktreeParent")) assertEquals("", screen.input(name).text.toString())
                assertEquals(0, f.creates[0])
                screen.input("cwd").setText("/fresh-repository"); screen.input("worktreeBranch").setText("fresh-topic")
                assertEquals("/fresh-repository", screen.input("cwd").text.toString())
                val publicationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (delivered.count != 0L && System.nanoTime() < publicationDeadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
                assertEquals("Actual replacement publication and Main custody ACK complete before fixture teardown", 0L, delivered.count)
            } finally { publicationTail?.close(); screen.controller.pause().stop().destroy() }
        }
    }

    @Test fun delayedOriginalRetirementAfterCancelledReviewPreservesAdmittedSuccessorEditableForm(): Unit = runBlocking {
        withMain { f ->
            val screen = newFormScreen(f, f.repository)
            val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            val blocker = f.repository.onReadContextInvalidated { reason ->
                if (reason == com.lateapex.collie.data.AuthorityInvalidation.CONNECTION_CHANGED) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            }
            val pool = Executors.newSingleThreadExecutor()
            val delivered = java.util.concurrent.CountDownLatch(1)
            val successorWitness = java.util.concurrent.atomic.AtomicReference<com.lateapex.collie.data.CommittedConnectionSnapshot?>()
            // Register before the save can start the publisher's observer snapshot. This is a
            // real delivery tail, after repository retirement and Activity Main custody ACKs.
            val publicationTail = f.app.container.connectionStore.observeCommitted { committed ->
                val expected = successorWitness.get()
                if (expected != null && committed.revision == expected.revision &&
                    committed.incarnationId == expected.incarnationId && committed.connection === expected.connection) delivered.countDown()
            }
            try {
                screen.input("cwd").setText("/repo"); screen.input("worktreeBranch").setText("original-topic"); screen.toggle.isChecked = true
                screen.launch.performClick(); shadowOf(Looper.getMainLooper()).idle()
                (ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog).getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, WorktreeUiLease.activeCountForTesting(screen.activity))
                val successor = Connection(f.connection.origin, "successor", "successor-retained-form-token")
                val replacement = pool.submit { f.app.container.connectionStore.save(successor) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val committedSuccessor = checkNotNull(f.app.container.connectionStore.captureCommitted())
                assertSame(successor, committedSuccessor.connection)
                successorWitness.set(committedSuccessor)
                NewActivity::class.java.getDeclaredMethod("loadCatalog").apply { isAccessible = true }.invoke(screen.activity)
                shadowOf(Looper.getMainLooper()).idle()
                val current = f.repository.readContext()!!; assertTrue(f.repository.isCurrent(current))
                screen.input("cwd").setText("/current-B-repository"); screen.input("worktreeBranch").setText("current-B-topic")
                screen.input("worktreeBaseRef").setText("current-B-ref"); screen.input("worktreeParent").setText("/current-B-parent")
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!replacement.isDone && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
                replacement.get(1, TimeUnit.SECONDS)
                // save() returns after queuing publication. Join its actual tail before this
                // sandbox destroys Main; a completed save Future is not retirement completion.
                val publicationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (delivered.count != 0L && System.nanoTime() < publicationDeadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
                assertEquals("Original retirement and real Main ACK precede fixture teardown", 0L, delivered.count)
                assertEquals("/current-B-repository", screen.input("cwd").text.toString()); assertEquals("current-B-topic", screen.input("worktreeBranch").text.toString())
                assertEquals("current-B-ref", screen.input("worktreeBaseRef").text.toString()); assertEquals("/current-B-parent", screen.input("worktreeParent").text.toString())
                assertTrue(f.repository.isCurrent(current)); assertEquals(0, f.creates[0])
            } finally { release.countDown(); blocker.close(); publicationTail.close(); pool.shutdownNow(); screen.controller.pause().stop().destroy() }
        }
    }

    private class NewFormScreen(val controller: org.robolectric.android.controller.ActivityController<NewActivity>) {
        val activity get() = controller.get()
        fun input(name: String) = NewActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as EditText
        val launch get() = NewActivity::class.java.getDeclaredField("launchButton").apply { isAccessible = true }.get(activity) as com.google.android.material.button.MaterialButton
        val toggle get() = NewActivity::class.java.getDeclaredField("worktreeToggle").apply { isAccessible = true }.get(activity) as com.google.android.material.switchmaterial.SwitchMaterial
    }
    private fun newFormScreen(f: Fixture, repository: CollieRepository, saved: android.os.Bundle? = null): NewFormScreen {
        val controller = Robolectric.buildActivity(NewActivity::class.java, NewActivity.intent(f.app, Scope()))
        NewActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(controller.get(), repository)
        controller.create(saved).start().resume(); shadowOf(Looper.getMainLooper()).idle()
        return NewFormScreen(controller)
    }

    private suspend fun withMain(action: suspend (Fixture) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val original = app.container.repository
        val connection = Connection(CollieOrigin("https://worktree-authority.fixture/"), "phone", "paired-fixture")
        saveFixtureConnection(app, connection)
        val snapshot = SnapshotResponse("collie", device = DeviceAuthorization(true, "phone", true), agents = emptyList(), shellPanes = emptyList(),
            workspaces = listOf(WorkspaceSummary("root", 1, "root", false, "tab", 1, 0, repoRoot = "/repo", isWorktree = false, folder = "/repo")), tabs = emptyList(), ts = 1)
        val counters = IntArray(4)
        val removals = IntArray(1)
        val creates = IntArray(1)
        val forcedEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val forcedReply = kotlinx.coroutines.CompletableDeferred<WorktreeRemovePlanResponse>()
        val api = object : CollieApi by app.container.api {
            override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> { counters[3]++; return ApiResult.Success(snapshot, 200) }
            // Main starts with an explicit refresh before its accepted snapshot. Delegating
            // that call to the real client would contact this deliberately non-routable origin
            // and freeze authority independently of the lifecycle scenario under test.
            override suspend fun refresh(connection: Connection, scope: Scope): ApiResult<ActionResponse> { counters[2]++; return ApiResult.Success(ActionResponse(true), 200) }
            override suspend fun config(connection: Connection, scope: Scope) = ApiResult.Success(BridgeConfigResponse(receiptContextBound = true, control = ControlCapabilityResponse(mapOf("worktreeRemove" to true)), mux = MuxConfigResponse(capabilities = mapOf("removeWorktree" to true))), 200)
            override suspend fun launchers(connection: Connection, scope: Scope) = ApiResult.Success(LaunchersResponse(emptyList(), "/home/op", items = listOf(LauncherItem("agent:codex", StartTarget(harness = "codex"), "agents", "Codex", "harness", false, true, true, harness = "codex"))), 200)
            override suspend fun folders(connection: Connection, scope: Scope) = ApiResult.Success(FoldersResponse(emptyList(), emptyList(), "/home/op"), 200)
            override suspend fun planWorktreeRemoval(connection: Connection, scope: Scope, workspaceId: String, force: Boolean): ApiResult<WorktreeRemovePlanResponse> {
                if (!force) return ApiResult.Failure(ApiFailure.Http(409, "Review force separately", "worktree.dirty_worktree_requires_force"))
                forcedEntered.complete(Unit)
                return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { ApiResult.Success(forcedReply.await(), 200) }
            }
            override suspend fun removeWorktree(connection: Connection, scope: Scope, workspaceId: String, request: WorktreeRemoveRequest): ApiResult<WorktreeRemoveResponse> { removals[0]++; return ApiResult.Failure(ApiFailure.Network("not dispatched by this fixture")) }
            override suspend fun listWorktrees(connection: Connection, scope: Scope, workspaceId: String): ApiResult<WorktreeListResponse> { counters[0]++; return ApiResult.Success(WorktreeListResponse(true), 200) }
            override suspend fun planWorktree(connection: Connection, scope: Scope, cwd: String, branch: String?, parent: String?): ApiResult<WorktreePlanResponse> { counters[1]++; return ApiResult.Success(WorktreePlanResponse(true, repoRoot = "/repo", defaultBranch = "main", branchValid = true, defaultTarget = WorktreeDefaultTarget("/repo-" + branch, false)), 200) }
            override suspend fun createWorktreeAtFrozen(connection: Connection, scope: Scope, canonicalBody: String): ApiResult<WorktreeOpenResponse> { creates[0]++; return ApiResult.Failure(ApiFailure.Network("fixture dispatch must remain deliberate")) }
        }
        val repository = CollieRepository(api, app.container.connectionStore, app.container.originValidator, launchIntentStore = app.container.launchIntentStore)
        val field = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        field.set(app.container, repository)
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java))
        assertSame("The Activity uses this sandbox's actual Application", app, controller.get().application)
        // AndroidViewModelFactory.getInstance caches its first Application across these
        // Robolectric sandboxes. Bind the actual production VM to this original repository
        // through its public factory seam; Main still owns/collects/polls its lifecycle.
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == MainViewModel::class.java)
                return modelClass.cast(MainViewModel(app, repository))
            }
        }
        val model = ViewModelProvider(controller.get(), factory)[MainViewModel::class.java]
        controller.create().start().resume()
        try {
            assertSame(model, MainActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }.get(controller.get()))
            assertSame(app, model.getApplication<CollieApplication>())
            assertSame(repository, MainViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }.get(model))
            val fixture = Fixture(app, connection, repository, controller, counters, removals, creates, forcedEntered, forcedReply)
            awaitCurrentRoot(fixture)
            assertTrue("Actual Main startup refresh reaches the supported API", counters[2] > 0)
            assertTrue("Actual Main snapshot reaches the supported API", counters[3] > 0)
            action(fixture)
        } finally { controller.pause().stop().destroy(); field.set(app.container, original) }
    }

    private fun awaitCurrentRoot(fixture: Fixture) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            val state = MainActivity::class.java.getDeclaredField("latestShellState").apply { isAccessible = true }.get(fixture.activity) as MainUiState
            if (fixture.repository.writesAllowed() && state.writeAuthorized &&
                state.originalReadContext?.let(fixture.repository::isCurrent) == true &&
                state.snapshot?.workspaces?.any { it.repoRoot != null && it.isWorktree == false } == true) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        val state = MainActivity::class.java.getDeclaredField("latestShellState").apply { isAccessible = true }.get(fixture.activity) as MainUiState
        val failure = fixture.repository.accessFailure
        throw AssertionError("Current repository snapshot supplies the real worktree form before the unchanged five-second bound; " +
            "refreshCalls=${fixture.counters[2]}, snapshotCalls=${fixture.counters[3]}, " +
            "writes=${fixture.repository.writesAllowed()}, stateWrite=${state.writeAuthorized}, " +
            "actorCurrent=${state.originalReadContext?.let(fixture.repository::isCurrent)}, " +
            "workspaces=${state.snapshot?.workspaces?.size}, failureType=${failure?.javaClass?.simpleName}")
    }

    private data class Fixture(val app: CollieApplication, val connection: Connection, val repository: CollieRepository,
        val controller: org.robolectric.android.controller.ActivityController<MainActivity>, val counters: IntArray, val removals: IntArray, val creates: IntArray,
        val forcedEntered: kotlinx.coroutines.CompletableDeferred<Unit>, val forcedReply: kotlinx.coroutines.CompletableDeferred<WorktreeRemovePlanResponse>) {
        val activity get() = controller.get()
        val lists get() = counters[0]
        val plans get() = counters[1]
    }
    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
