package com.lateapex.collie.ui

import com.lateapex.collie.R
import com.lateapex.collie.data.AndroidReleaseLookup
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
class AndroidAppUpdatesActivityTest {
    private lateinit var originalWorkerFactory: com.lateapex.collie.data.OriginalUpdateWorkerFactory
    @Before fun initializeExistingWorkManagerRuntime() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        originalWorkerFactory = com.lateapex.collie.data.OriginalUpdateWorkerFactory()
        // Use WorkManager's documented integration scheduler for the JVM platform.
        // Production enqueue/worker/HTTPS/WorkInfo/output/Activity observation stay real;
        // only Android's CONNECTED constraint delivery is simulated by TestDriver.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setWorkerFactory(originalWorkerFactory).build(),
            androidx.work.testing.WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS)
        assertTrue(androidx.work.WorkManager.isInitialized())
        assertNotNull(androidx.work.WorkManager.getInstance(context))
    }

    @Test
    fun nativeUpdateScreenStartsIdleAndRequiresSeparateDownloadAndInstallActions() {
        val activity = Robolectric.buildActivity(AndroidAppUpdatesActivity::class.java).create().start().resume().get()

        assertNotNull(activity.findViewById(android.R.id.content))
        assertEquals(activity.getString(R.string.android_updates_idle),
            activity.findViewById<android.widget.TextView>(R.id.android_updates_status_view).text)
        assertFalse(activity.findViewById<android.widget.Button>(R.id.android_updates_download_button).isEnabled)
        assertFalse(activity.findViewById<android.widget.Button>(R.id.android_updates_install_button).isEnabled)
        assertEquals(activity.getString(R.string.android_updates_status),
            activity.findViewById<android.widget.TextView>(R.id.android_updates_status_view).contentDescription)
    }

    @Test
    fun stoppedScreenCannotPublishAReleaseResponseThatReturnsLater() {
        val controller = Robolectric.buildActivity(AndroidAppUpdatesActivity::class.java).create().start().resume()
        val activity = controller.get()
        val entered = CompletableDeferred<Unit>()
        val finishLookup = CompletableDeferred<Unit>()
        activity.latestLookupForTests = {
            entered.complete(Unit)
            finishLookup.await()
            AndroidReleaseLookup.UpToDate
        }

        activity.findViewById<android.widget.Button>(R.id.android_updates_check_button).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(entered.isCompleted)
        controller.pause().stop()
        finishLookup.complete(Unit)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(activity.getString(R.string.android_updates_checking),
            activity.findViewById<android.widget.TextView>(R.id.android_updates_status_view).text)
        controller.destroy()
    }

    @Test
    fun sameScreenRestartRestoresExplicitCheckWithoutPublishingHeldOldLookupOrAutoRetry() {
        val controller = Robolectric.buildActivity(AndroidAppUpdatesActivity::class.java).create().start().resume()
        val activity = controller.get()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var lookups = 0
        activity.latestLookupForTests = {
            lookups++
            entered.complete(Unit)
            release.await()
            AndroidReleaseLookup.UpToDate
        }
        val check = activity.findViewById<android.widget.Button>(R.id.android_updates_check_button)
        check.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(entered.isCompleted)
        assertFalse(check.isEnabled)
        controller.pause().stop()
        release.complete(Unit); Shadows.shadowOf(Looper.getMainLooper()).idle()
        controller.start().resume(); Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(check.isEnabled)
        assertEquals(1, lookups)
        val status = activity.findViewById<android.widget.TextView>(R.id.android_updates_status_view)
        assertEquals(activity.getString(R.string.android_updates_checking), status.text)
        assertTrue(status.contentDescription.toString().contains(status.text))
        activity.latestLookupForTests = { lookups++; AndroidReleaseLookup.UpToDate }
        check.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, lookups)
        assertEquals(activity.getString(R.string.android_updates_current), status.text)
        assertTrue(status.contentDescription.toString().contains(status.text))
        controller.pause().stop().destroy()
    }

    @Test
    fun restrictedDistributionHidesInstallerAndRetainsExplicitCheckAndDownloadReview() {
        val controller = Robolectric.buildActivity(AndroidAppUpdatesActivity::class.java).create()
        val activity = controller.get()
        activity.sideloadInstallForTests = false
        controller.start().resume()
        assertEquals(android.view.View.GONE, activity.findViewById<android.widget.Button>(R.id.android_updates_install_button).visibility)
        assertTrue(activity.findViewById<android.widget.Button>(R.id.android_updates_check_button).isEnabled)
        activity.latestLookupForTests = { AndroidReleaseLookup.Available(publicOffer()) }
        activity.findViewById<android.widget.Button>(R.id.android_updates_check_button).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.findViewById<android.widget.Button>(R.id.android_updates_download_button).isEnabled)
        assertEquals(null, Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals(android.view.View.GONE, activity.findViewById<android.widget.Button>(R.id.android_updates_install_button).visibility)
        controller.pause().stop().destroy()
    }

    @Test
    fun downloadConsentCannotBorrowChangedManifestUnderSameTagAndRequiresNewCheck() {
        val controller = Robolectric.buildActivity(AndroidAppUpdatesActivity::class.java).create().start().resume()
        val activity = controller.get()
        val original = publicOffer()
        var lookup = 0
        val changed = original.copy(manifestDigest = "b".repeat(64), acceptedRevision = 2,
            metadata = original.metadata.copy(rawMetadata = byteArrayOf(2)))
        activity.latestLookupForTests = { lookup++; AndroidReleaseLookup.Available(if (lookup == 1) original else changed) }
        val check = activity.findViewById<android.widget.Button>(R.id.android_updates_check_button)
        val download = activity.findViewById<android.widget.Button>(R.id.android_updates_download_button)
        check.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(download.isEnabled)
        download.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, lookup)
        assertEquals(activity.getString(R.string.android_updates_offer_changed),
            activity.findViewById<android.widget.TextView>(R.id.android_updates_status_view).text)
        assertFalse(download.isEnabled)
        assertTrue(check.isEnabled)
        assertEquals(null, Shadows.shadowOf(activity).nextStartedActivity)
        check.performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, lookup)
        assertTrue(download.isEnabled)
        assertEquals(activity.getString(R.string.android_updates_offer_available),
            activity.findViewById<android.widget.TextView>(R.id.android_updates_status_view).text)
        controller.pause().stop().destroy()
    }

    @Test
    fun accessibilityStatusKeepsLabelAndAnnouncesLoadingRefusalAndProgressText() {
        val controller = Robolectric.buildActivity(AndroidAppUpdatesActivity::class.java).create().start().resume()
        val activity = controller.get()
        val status = activity.findViewById<android.widget.TextView>(R.id.android_updates_status_view)
        for (id in listOf(R.string.android_updates_checking, R.string.android_updates_invalid_release, R.string.android_updates_preparing)) {
            status.setText(id)
            assertTrue(status.contentDescription.toString().startsWith(activity.getString(R.string.android_updates_status)))
            assertTrue(status.contentDescription.toString().contains(activity.getString(id)))
            assertEquals(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE, status.accessibilityLiveRegion)
        }
        controller.pause().stop().destroy()
    }

    @Test
    fun retainedInstallConfirmationAfterStopStartNeverBorrowsNewScreenAuthority() {
        for (permission in listOf(false, true)) withReadyActivity(permission) { controller, activity, fixture ->
            val button = activity.findViewById<android.widget.Button>(R.id.android_updates_install_button)
            button.performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val retained = requireNotNull(Shadows.shadowOf(positive).onClickListener)
            controller.pause().stop()
            assertFalse(dialog.isShowing)
            assertFalse(positive.isEnabled)
            controller.start().resume()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            repeat(2) { retained.onClick(positive); Shadows.shadowOf(Looper.getMainLooper()).idle() }
            assertEquals(null, Shadows.shadowOf(activity).nextStartedActivity)
            assertEquals(null, Shadows.shadowOf(activity).nextStartedActivityForResult)
            assertEquals(null, fixture.repository.currentInstallAttempt())
            assertEquals("ready", fixture.repository.currentOperation()?.lifecycleState)
        }
    }

    @Test
    fun retainedInstallConfirmationAfterActualNewCheckCannotOpenPermissionOrInstaller() {
        for (permission in listOf(false, true)) withReadyActivity(permission) { _, activity, fixture ->
            activity.findViewById<android.widget.Button>(R.id.android_updates_install_button).performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val retained = requireNotNull(Shadows.shadowOf(positive).onClickListener)
            fixture.enqueueDiscovery()
            activity.findViewById<android.widget.Button>(R.id.android_updates_check_button).performClick()
            assertFalse(dialog.isShowing)
            assertFalse(positive.isEnabled)
            awaitMain { activity.findViewById<android.widget.Button>(R.id.android_updates_check_button).isEnabled }
            repeat(2) { retained.onClick(positive); Shadows.shadowOf(Looper.getMainLooper()).idle() }
            assertEquals(null, Shadows.shadowOf(activity).nextStartedActivity)
            assertEquals(null, Shadows.shadowOf(activity).nextStartedActivityForResult)
            assertEquals(null, fixture.repository.currentInstallAttempt())
        }
    }

    @Test
    fun actualReadyInstallReviewIsConsumedOnceAndUsesNormalOsHandoff() {
        withReadyActivity(true) { _, activity, fixture ->
            activity.findViewById<android.widget.Button>(R.id.android_updates_install_button).performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val retained = requireNotNull(Shadows.shadowOf(positive).onClickListener)
            positive.performClick()
            awaitMain { fixture.repository.currentInstallAttempt()?.phase == "pending" }
            val sessionId = requireNotNull(fixture.repository.currentInstallAttempt()?.installerSessionId)
            val info = requireNotNull(activity.packageManager.packageInstaller.getSessionInfo(sessionId))
            assertEquals("com.lateapex.collie", info.appPackageName)
            assertEquals(null, Shadows.shadowOf(activity).nextStartedActivityForResult)
            assertEquals(null, Shadows.shadowOf(activity).nextStartedActivity)
            val attemptId = requireNotNull(fixture.repository.currentInstallAttempt()).attemptId
            repeat(2) { retained.onClick(positive); Shadows.shadowOf(Looper.getMainLooper()).idle() }
            assertEquals(null, Shadows.shadowOf(activity).nextStartedActivityForResult)
            assertEquals(attemptId, fixture.repository.currentInstallAttempt()?.attemptId)
            assertEquals("pending", fixture.repository.currentInstallAttempt()?.phase)
        }
    }

    @Test
    fun actualInstallReservationReturningAfterStopCannotDispatchFromOldReview() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withReadyActivity(true, { entered.complete(Unit); release.await() }) { controller, activity, fixture ->
            activity.findViewById<android.widget.Button>(R.id.android_updates_install_button).performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
            awaitMain { entered.isCompleted }
            controller.pause().stop().start().resume()
            release.complete(Unit)
            awaitMain { fixture.repository.currentInstallAttempt()?.phase == "refused" }
            assertEquals(null, Shadows.shadowOf(activity).nextStartedActivity)
            assertEquals(null, Shadows.shadowOf(activity).nextStartedActivityForResult)
        }
    }

    private fun withReadyActivity(
        permission: Boolean,
        beforeInstall: suspend () -> Unit = {},
        body: (org.robolectric.android.controller.ActivityController<AndroidAppUpdatesActivity>, AndroidAppUpdatesActivity,
            com.lateapex.collie.data.SignedUpdateUiFixture) -> Unit,
    ) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.lateapex.collie.data.SignedUpdateUiFixture(context, originalWorkerFactory, beforeInstall).use { fixture ->
            val controller = Robolectric.buildActivity(AndroidAppUpdatesActivity::class.java)
            val activity = controller.get()
            activity.originalRepositoryForTests = fixture.repository
            controller.create().start().resume()
            Shadows.shadowOf(activity.packageManager).setCanRequestPackageInstalls(permission)
            val network = FixtureWorkSchedulerDriver(context)
            try {
                val witness = kotlinx.coroutines.runBlocking { fixture.reserveDownload() }
                // Production enqueue, production worker, real signed HTTPS download,
                // real WorkManager output and the actual Activity observer producer.
                com.lateapex.collie.data.ApkDownloadWorker.enqueue(context, witness.operationId)
                awaitMain(diagnostics = { producerDiagnostics(activity, fixture, network) }, platform = network) {
                    activity.findViewById<android.widget.Button>(R.id.android_updates_install_button).isEnabled
                }
                assertEquals(witness.operationId, fixture.repository.currentOperation()?.operationId)
                assertEquals("ready", fixture.repository.currentOperation()?.lifecycleState)
                assertTrue(java.io.File(fixture.directory, "${witness.operationId}/1.5.3.apk").isFile)
                body(controller, activity, fixture)
            } finally {
                controller.pause().stop().destroy()
                try {
                    fixture.operation?.let {
                        androidx.work.WorkManager.getInstance(context).cancelUniqueWork(
                            com.lateapex.collie.data.ApkDownloadWorker.uniqueWorkName(it.operationId)).result
                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } finally {
                    network.close()
                }
            }
        }
    }

    private fun producerDiagnostics(activity: AndroidAppUpdatesActivity, fixture: com.lateapex.collie.data.SignedUpdateUiFixture, network: FixtureWorkSchedulerDriver): String {
        val manager = androidx.work.WorkManager.getInstance(activity)
        val infos = manager.getWorkInfosByTag(com.lateapex.collie.data.ApkDownloadWorker.DOWNLOAD_TAG)
            .get(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        val states = infos.filter { fixture.operation?.operationId in it.tags }
            .joinToString { "${it.state}:${it.outputData.getString(com.lateapex.collie.data.ApkDownloadWorker.OUTPUT_REASON)}" }
        return "state=$states; op=${fixture.repository.currentOperation()?.lifecycleState}; " +
            "factory=${originalWorkerFactory.createCalls.get()}/${originalWorkerFactory.matchedOriginals.get()}; " +
            "requests=${fixture.requestCount}; sdk=${android.os.Build.VERSION.SDK_INT}; constraintsDelivered=${network.deliveryCount}; " +
            "status=${activity.findViewById<android.widget.TextView>(R.id.android_updates_status_view).text}"
    }

    /** WorkManager's supported integration seam for Android scheduling on the JVM. */
    private class FixtureWorkSchedulerDriver(context: android.content.Context) : AutoCloseable {
        private val manager = androidx.work.WorkManager.getInstance(context)
        private val driver = requireNotNull(androidx.work.testing.WorkManagerTestInitHelper.getTestDriver(context))
        private val delivered = mutableSetOf<java.util.UUID>()
        val deliveryCount: Int get() = delivered.size

        fun deliver() {
            val infos = manager.getWorkInfosByTag(com.lateapex.collie.data.ApkDownloadWorker.DOWNLOAD_TAG)
                .get(1, java.util.concurrent.TimeUnit.SECONDS)
            for (work in infos) {
                if (work.state == androidx.work.WorkInfo.State.ENQUEUED && delivered.add(work.id)) {
                    driver.setAllConstraintsMet(work.id)
                }
            }
        }

        override fun close() { delivered.clear() }
    }

    private fun awaitMain(
        diagnostics: () -> String = { "" },
        platform: FixtureWorkSchedulerDriver? = null,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            platform?.deliver()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        if (!condition()) {
            val detail = runCatching(diagnostics).getOrElse { "diagnostics=${it.javaClass.simpleName}" }
            throw AssertionError("Actual original update producer timeout: $detail")
        }
        assertTrue("Actual original update producer settles within the bounded wait", condition())
    }

    // Public offer presentation fixture only: it never obtains repository admission or dispatches.
    private fun publicOffer(): com.lateapex.collie.data.AndroidReleaseCandidate {
        val metadata = com.lateapex.collie.data.AndroidReleaseMetadata(
            "android-v1.5.4", "com.lateapex.collie", "1.5.4", 100001,
            "a".repeat(40), true, "collie-android-1.5.4.apk", 12630, "a".repeat(64), "c".repeat(64),
            26, "d".repeat(64))
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("github.com")
            .addPathSegments("Buckeyes22/collie/releases/download/android-v1.5.4/collie-android-1.5.4.apk").build()
        return com.lateapex.collie.data.AndroidReleaseCandidate(
            tag = metadata.tag, versionName = metadata.versionName, versionCode = metadata.versionCode,
            notes = "Public release notes", sourceUrl = "https://github.com/Buckeyes22/collie/releases/tag/android-v1.5.4",
            byteSize = metadata.apkSizeBytes, metadata = com.lateapex.collie.data.VerifiedAndroidRelease(metadata, byteArrayOf(1), byteArrayOf(1)),
            manifestDigest = "a".repeat(64), acceptedRevision = 1, installedVersionCode = com.lateapex.collie.BuildConfig.VERSION_CODE.toLong(),
            installedSignerDigests = listOf("c".repeat(64)), apkUrl = url, metadataUrl = url, signatureUrl = url)
    }

}
