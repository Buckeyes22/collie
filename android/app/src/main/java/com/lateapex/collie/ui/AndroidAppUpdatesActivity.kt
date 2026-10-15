package com.lateapex.collie.ui

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lateapex.collie.R
import com.lateapex.collie.data.AndroidReleaseLookup
import com.lateapex.collie.data.AndroidReleaseRepository
import com.lateapex.collie.data.ApkDownloadWorker
import java.io.File
import kotlinx.coroutines.launch

/** Native sideload updater. This is intentionally distinct from server-side UpdatesActivity. */
class AndroidAppUpdatesActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var offerDetails: TextView
    private lateinit var progress: ProgressBar
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private lateinit var installButton: Button
    private lateinit var cancelButton: Button
    // Tests may supply one original trusted repository before creation; production
    // always constructs the same anonymous pinned release repository.
    internal var originalRepositoryForTests: AndroidReleaseRepository? = null
    private val repository by lazy { originalRepositoryForTests ?: AndroidReleaseRepository(applicationContext) }
    private class InstallReview(
        val screen: Long,
        val action: Long,
        val operation: com.lateapex.collie.data.AndroidReleaseOperationWitness,
        val apk: File,
        var consumed: Boolean = false,
        var retired: Boolean = false,
    )
    private var installReview: InstallReview? = null
    private var installConfirmation: AlertDialog? = null
    private var candidateTag: String? = null
    private var reviewedOffer: com.lateapex.collie.data.AndroidReleaseCandidate? = null
    internal var sideloadInstallForTests: Boolean? = null
    private fun canOfferInstaller(): Boolean = sideloadInstallForTests ?: com.lateapex.collie.BuildConfig.SIDELOAD_INSTALL
    private var stagedApk: File? = null
    private var operationId: String? = null
    private var started = false
    private var screenGeneration = 0L
    private var userActionGeneration = 0L
    private class CheckLookup(val screen: Long, val action: Long)
    private var pendingCheck: CheckLookup? = null
    internal var latestLookupForTests: (suspend (Long) -> AndroidReleaseLookup)? = null
    private val installListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "install_attempt" && started) publishCurrentInstall()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        userActionGeneration = savedInstanceState?.getLong(STATE_USER_ACTION_GENERATION) ?: 0L
        title = getString(R.string.android_updates_title)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        window.applySafeContentInsets(scroll)
        content.addView(button(R.string.android_updates_back).apply {
            setOnClickListener { finish() }
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.android_updates_description)
            textSize = 16f
            contentDescription = text
        })
        status = object : AppCompatTextView(this) {
            override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
                super.onTextChanged(text, start, lengthBefore, lengthAfter)
                // A read-only status must keep TextView's normal buffer. TextWatchers
                // coerce it to Editable even though users cannot edit this view.
                val label = context.getString(R.string.android_updates_status)
                contentDescription = if (text?.toString() == context.getString(R.string.android_updates_idle)) {
                    label
                } else {
                    "$label: ${text?.toString().orEmpty()}"
                }
            }
        }.apply {
            id = R.id.android_updates_status_view
            textSize = 16f
            setPadding(0, dp(18), 0, dp(12))
            contentDescription = getString(R.string.android_updates_status)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        offerDetails = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        content.addView(offerDetails)
        content.addView(button(R.string.android_updates_release_page).apply {
            setOnClickListener {
                val source = reviewedOffer?.sourceUrl ?: "https://github.com/Buckeyes22/collie/releases"
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source))) }
                catch (_: ActivityNotFoundException) { status.setText(R.string.android_updates_browser_missing) }
            }
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            contentDescription = getString(R.string.android_updates_progress)
        }
        content.addView(progress)
        checkButton = button(R.string.android_updates_check).apply { id = R.id.android_updates_check_button }.also(content::addView)
        downloadButton = button(R.string.android_updates_download).apply { id = R.id.android_updates_download_button }.also(content::addView)
        installButton = button(R.string.android_updates_install).apply { id = R.id.android_updates_install_button }.also(content::addView)
        cancelButton = button(R.string.android_updates_cancel).apply { id = R.id.android_updates_cancel_button }.also(content::addView)
        downloadButton.isEnabled = false
        installButton.isEnabled = false
        cancelButton.visibility = View.GONE
        checkButton.setOnClickListener(::checkForUpdate)
        downloadButton.setOnClickListener { beginDownload() }
        installButton.visibility = if (canOfferInstaller()) View.VISIBLE else View.GONE
        installButton.setOnClickListener {
            if (canOfferInstaller() && started) {
                val confirmation = repository.pendingInstallerConfirmation()
                if (confirmation != null) {
                    retireInstallReview()
                    userActionGeneration++
                    try {
                        installButton.isEnabled = false
                        confirmation.send()
                    } catch (_: android.app.PendingIntent.CanceledException) {
                        status.setText(R.string.android_updates_installer_missing)
                    } catch (_: SecurityException) {
                        status.setText(R.string.android_updates_installer_missing)
                    }
                } else confirmInstall()
            }
        }
        cancelButton.setOnClickListener {
            retireInstallReview()
            userActionGeneration++
            val install = repository.currentInstallAttempt()
            if (install?.installerSessionId != null && install.phase in setOf("prepared", "dispatched", "pending", "unknown")) {
                val generation = screenGeneration
                val action = userActionGeneration
                lifecycleScope.launch {
                    repository.cancelPendingInstaller(install.attemptId)
                    if (isCurrentAction(generation, action)) publishCurrentInstall()
                }
            } else {
                operationId?.let { ApkDownloadWorker.cancel(this, it, repository) }
                status.setText(R.string.android_updates_cancel_requested)
            }
        }
        observeDownloads()
        status.setText(R.string.android_updates_idle)
        scroll.applyAppTypeface()
    }

    override fun onStart() {
        super.onStart()
        started = true
        screenGeneration++
        // Restart never replays lookup/download/install; only recover controls for a fresh tap.
        checkButton.isEnabled = true
        installButton.visibility = if (canOfferInstaller()) View.VISIBLE else View.GONE
        repository.addInstallListener(installListener)
        val generation = screenGeneration
        val action = userActionGeneration
        lifecycleScope.launch {
            val attempt = repository.reconcileInstallAttempt()
            if (isCurrentAction(generation, action) && attempt != null) publishCurrentInstall()
        }
    }

    override fun onStop() {
        started = false
        repository.removeInstallListener(installListener)
        screenGeneration++
        pendingCheck = null
        retireInstallReview()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_USER_ACTION_GENERATION, userActionGeneration)
        super.onSaveInstanceState(outState)
    }

    private fun checkForUpdate(@Suppress("UNUSED_PARAMETER") view: View) {
        retireInstallReview()
        val action = ++userActionGeneration
        val generation = screenGeneration
        val originalCheck = CheckLookup(generation, action).also { pendingCheck = it }
        checkButton.isEnabled = false
        downloadButton.isEnabled = false
        installButton.isEnabled = false
        stagedApk = null
        reviewedOffer = null
        offerDetails.text = ""
        status.setText(R.string.android_updates_checking)
        lifecycleScope.launch {
            when (val result = latestLookup(installedVersionCode())) {
                is AndroidReleaseLookup.Available -> {
                    if (!isCurrentAction(generation, action)) return@launch
                    candidateTag = result.candidate.tag
                    reviewedOffer = result.candidate.copy(
                        metadata = result.candidate.metadata.copy(metadata = result.candidate.metadata.metadata.copy(metadataKeyChain = result.candidate.metadata.metadata.metadataKeyChain.toList()), rawMetadata = result.candidate.metadata.rawMetadata.copyOf(), rawSignature = result.candidate.metadata.rawSignature.copyOf()),
                        installedSignerDigests = result.candidate.installedSignerDigests.toList(),
                    )
                    offerDetails.text = getString(
                        R.string.android_updates_available,
                        result.candidate.installedVersionCode,
                        result.candidate.versionName,
                        result.candidate.byteSize,
                        result.candidate.sourceUrl,
                        result.candidate.notes,
                    )
                    status.setText(R.string.android_updates_offer_available)
                    downloadButton.isEnabled = true
                }
                AndroidReleaseLookup.UpToDate -> if (isCurrentAction(generation, action)) status.setText(R.string.android_updates_current)
                is AndroidReleaseLookup.Unavailable -> if (isCurrentAction(generation, action)) status.setText(
                    when (result.reason) {
                        AndroidReleaseLookup.Reason.NETWORK -> R.string.android_updates_network_error
                        AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID -> R.string.android_updates_invalid_release
                        AndroidReleaseLookup.Reason.UNSUPPORTED_RELEASE,
                        AndroidReleaseLookup.Reason.NO_COMPATIBLE_RELEASE -> R.string.android_updates_no_release
                        AndroidReleaseLookup.Reason.STORAGE -> R.string.android_updates_storage_error
                    },
                )
            }
            // A retired lookup cannot clear a replacement screen/action's admission.
            if (pendingCheck === originalCheck) pendingCheck = null
            if (isCurrentAction(generation, action)) checkButton.isEnabled = true
        }
    }

    private suspend fun latestLookup(installedVersionCode: Long): AndroidReleaseLookup =
        latestLookupForTests?.invoke(installedVersionCode) ?: repository.latest(installedVersionCode)

    private fun beginDownload() {
        retireInstallReview()
        val action = ++userActionGeneration
        val generation = screenGeneration
        val originalOffer = reviewedOffer ?: return
        downloadButton.isEnabled = false
        lifecycleScope.launch {
            val current = latestLookup(installedVersionCode())
            val candidate = (current as? AndroidReleaseLookup.Available)?.candidate
            if (candidate == null || !sameReviewedOffer(originalOffer, candidate)) {
                if (isCurrentAction(generation, action)) {
                    reviewedOffer = null
                    status.setText(R.string.android_updates_offer_changed)
                    checkButton.isEnabled = true
                }
                return@launch
            }
            if (!isCurrentAction(generation, action)) return@launch
            val witness = repository.beginDownload(originalOffer)
            if (witness == null) {
                if (isCurrentAction(generation, action)) status.setText(R.string.android_updates_invalid_release)
                return@launch
            }
            if (!isCurrentAction(generation, action)) {
                ApkDownloadWorker.cancel(this@AndroidAppUpdatesActivity, witness.operationId, repository)
                return@launch
            }
            operationId = witness.operationId
            ApkDownloadWorker.enqueue(this@AndroidAppUpdatesActivity, witness.operationId)
            if (isCurrentAction(generation, action)) status.setText(R.string.android_updates_preparing)
        }
    }

    private fun observeDownloads() {
        WorkManager.getInstance(applicationContext)
            .getWorkInfosByTagLiveData(ApkDownloadWorker.DOWNLOAD_TAG)
            .observe(this) { infos ->
                val canceled = infos.orEmpty().filter { it.state == WorkInfo.State.CANCELLED }
                canceled.forEach { work ->
                    work.tags.firstOrNull { it.matches(OPERATION_ID) }?.let(repository::markDownloadCanceled)
                }
                val currentOperation = repository.currentOperation()
                installReview?.let { original ->
                    val phases = if (original.consumed) setOf("ready", "installPrepared", "installDispatched", "installPending") else setOf("ready")
                    if (!sameInstallOperation(original, currentOperation, phases)) retireInstallReview()
                }
                operationId = currentOperation?.operationId
                // Cancellation bookkeeping above remains live, but a delayed download-table
                // emission cannot replace a newer explicit Check's controls or offer status.
                val checking = pendingCheck
                if (checking != null && isCurrentAction(checking.screen, checking.action)) return@observe
                val active = currentOperation?.let { current ->
                    infos?.firstOrNull { !it.state.isFinished && current.operationId in it.tags }
                }
                if (active != null) {
                    operationId = active.progress.getString(ApkDownloadWorker.INPUT_OPERATION_ID) ?: active.tags.firstOrNull { it.matches(OPERATION_ID) } ?: operationId
                    progress.visibility = View.VISIBLE
                    val done = active.progress.getLong(ApkDownloadWorker.PROGRESS_COMPLETE, 0L)
                    val total = active.progress.getLong(ApkDownloadWorker.PROGRESS_TOTAL, 0L)
                    progress.progress = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                    status.text = if (total > 0) getString(R.string.android_updates_downloading, done, total)
                        else getString(R.string.android_updates_preparing)
                    downloadButton.isEnabled = false
                    installButton.isEnabled = false
                    cancelButton.setText(R.string.android_updates_cancel)
                    cancelButton.visibility = View.VISIBLE
                    return@observe
                }
                progress.visibility = View.GONE
                cancelButton.visibility = View.GONE
                val ready = infos.orEmpty().lastOrNull {
                    it.state == WorkInfo.State.SUCCEEDED && it.outputData.getString(ApkDownloadWorker.OUTPUT_OPERATION_ID) == currentOperation?.operationId
                }
                val tag = ready?.outputData?.getString(ApkDownloadWorker.OUTPUT_TAG)
                val op = ready?.outputData?.getString(ApkDownloadWorker.OUTPUT_OPERATION_ID)
                val file = ready?.outputData?.getString(ApkDownloadWorker.OUTPUT_APK_PATH)?.let(::File)
                val currentState = currentOperation?.lifecycleState
                if (tag != null && op != null && OPERATION_ID.matches(op) && currentState == "ready" && file != null && file.isFile) {
                    candidateTag = tag
                    operationId = op
                    stagedApk = file
                    status.setText(if (!canOfferInstaller()) R.string.android_updates_manual_verified else R.string.android_updates_ready)
                    installButton.isEnabled = canOfferInstaller()
                } else if (infos.orEmpty().any { it.state == WorkInfo.State.FAILED && currentOperation?.operationId in it.tags }) {
                    status.setText(R.string.android_updates_download_failed)
                } else if (currentOperation?.lifecycleState == "canceled") {
                    status.setText(R.string.android_updates_cancel_requested)
                } else if (currentOperation?.lifecycleState == "failed") {
                    status.setText(R.string.android_updates_download_failed)
                } else if (ready != null) {
                    status.setText(R.string.android_updates_staged_file_missing)
                }
                checkButton.isEnabled = true
                if (currentState in setOf("installPrepared", "installDispatched", "installPending", "unknown", "installed", "refused")) publishCurrentInstall()
            }
    }

    private fun confirmInstall() {
        if (!canOfferInstaller()) { status.setText(R.string.android_updates_manual_verified); return }
        if (!started) return
        retireInstallReview()
        val apk = stagedApk?.canonicalFile ?: return
        val originalOperationId = operationId ?: return
        val operation = repository.currentOperation()?.takeIf {
            it.operationId == originalOperationId && it.lifecycleState == "ready"
        } ?: return
        val original = InstallReview(screenGeneration, ++userActionGeneration, operation, apk)
        installReview = original
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.android_updates_confirm_title)
            .setMessage(R.string.android_updates_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.android_updates_install) { _, _ ->
                // Consume the review captured when the dialog was constructed. A late
                // callback can never mint a new screen/action or a replacement review.
                if (original.consumed || !isCurrentInstallReview(original, setOf("ready"))) return@setPositiveButton
                original.consumed = true
                installConfirmation?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                verifyAndInstall(original)
            }
            .create()
        installConfirmation = dialog
        dialog.setOnDismissListener {
            if (installConfirmation === dialog) installConfirmation = null
            if (!original.consumed && installReview === original) {
                original.retired = true
                installReview = null
            }
        }
        dialog.show()
    }

    private fun retireInstallReview() {
        val original = installReview
        original?.retired = true
        installReview = null
        val dialog = installConfirmation
        installConfirmation = null
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        dialog?.dismiss()
    }

    private fun sameInstallOperation(
        original: InstallReview,
        current: com.lateapex.collie.data.AndroidReleaseOperationWitness?,
        phases: Set<String>,
    ): Boolean = current != null && current.lifecycleState in phases &&
        current.copy(lifecycleState = original.operation.lifecycleState) == original.operation

    private fun isCurrentInstallReview(original: InstallReview, phases: Set<String>): Boolean =
        !original.retired && installReview === original &&
            isCurrentAction(original.screen, original.action) &&
            operationId == original.operation.operationId && stagedApk?.canonicalFile == original.apk &&
            sameInstallOperation(original, repository.currentOperation(), phases)

    private fun verifyAndInstall(original: InstallReview) {
        if (!isCurrentInstallReview(original, setOf("ready"))) return
        val op = original.operation.operationId
        val apk = original.apk
        val action = original.action
        val generation = original.screen
        if (!canOfferInstaller()) { status.setText(R.string.android_updates_manual_verified); return }
        installButton.isEnabled = false
        status.setText(R.string.android_updates_verifying)
        lifecycleScope.launch {
            if (!isCurrentInstallReview(original, setOf("ready"))) return@launch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                if (!isCurrentInstallReview(original, setOf("ready"))) return@launch
                status.setText(R.string.android_updates_permission)
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:$packageName")))
                installButton.isEnabled = true
                return@launch
            }
            if (!isCurrentInstallReview(original, setOf("ready"))) return@launch
            val admission = repository.reserveInstallerFor(op, apk)
            val phases = if (admission is com.lateapex.collie.data.AndroidInstallAdmission.Reserved) setOf("installPrepared") else setOf("ready")
            if (!isCurrentInstallReview(original, phases)) {
                if (admission is com.lateapex.collie.data.AndroidInstallAdmission.Reserved) repository.failInstallerDispatch(admission.attempt.attemptId)
                return@launch
            }
            if (admission !is com.lateapex.collie.data.AndroidInstallAdmission.Reserved) {
                if (isCurrentAction(generation, action)) status.setText(R.string.android_updates_invalid_release)
                installButton.isEnabled = false
                return@launch
            }
            if (!isCurrentInstallReview(original, setOf("installPrepared"))) {
                repository.failInstallerDispatch(admission.attempt.attemptId)
                return@launch
            }
            val sessionId = repository.prepareInstallerSession(admission.attempt.attemptId, apk)
            if (sessionId == null) {
                if (isCurrentAction(generation, action)) status.setText(R.string.android_updates_install_refused)
                return@launch
            }
            if (!isCurrentInstallReview(original, setOf("installPrepared"))) {
                repository.abandonPreparedInstaller(admission.attempt.attemptId)
                return@launch
            }
            if (!repository.commitInstallerSession(admission.attempt.attemptId, sessionId)) {
                if (isCurrentAction(generation, action)) status.setText(R.string.android_updates_install_unknown)
                return@launch
            }
            if (isCurrentAction(generation, action)) {
                retireInstallReview()
                publishCurrentInstall()
            }
        }
    }

    private fun sameReviewedOffer(a: com.lateapex.collie.data.AndroidReleaseCandidate, b: com.lateapex.collie.data.AndroidReleaseCandidate): Boolean =
        a.tag == b.tag && a.versionCode == b.versionCode && a.manifestDigest == b.manifestDigest &&
            a.acceptedRevision == b.acceptedRevision && a.byteSize == b.byteSize &&
            a.versionName == b.versionName && a.sourceUrl == b.sourceUrl && a.notes == b.notes &&
            a.installedVersionCode == b.installedVersionCode && a.installedSignerDigests == b.installedSignerDigests &&
            a.metadata.rawMetadata.contentEquals(b.metadata.rawMetadata)

    private fun installedVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    private fun isCurrentScreen(generation: Long): Boolean = started && screenGeneration == generation

    private fun isCurrentAction(generation: Long, action: Long): Boolean =
        isCurrentScreen(generation) && userActionGeneration == action

    private fun publishCurrentInstall() {
        if (!started) return
        val attempt = repository.currentInstallAttempt() ?: return
        val continuation = repository.pendingInstallerConfirmation()
        installButton.isEnabled = canOfferInstaller() && continuation != null
        val sessionPending = attempt.installerSessionId != null && attempt.phase in setOf("prepared", "dispatched", "pending", "unknown")
        cancelButton.setText(R.string.android_updates_cancel_install)
        cancelButton.visibility = if (sessionPending) View.VISIBLE else View.GONE
        status.setText(when {
            attempt.phase == "installed" -> R.string.android_updates_installed
            attempt.phase == "canceled" -> R.string.android_updates_install_canceled
            attempt.phase == "refused" -> R.string.android_updates_install_refused
            continuation != null -> R.string.android_updates_installer_confirmation_ready
            attempt.phase in setOf("prepared", "dispatched", "pending") -> R.string.android_updates_preparing_installer
            else -> R.string.android_updates_install_unknown
        })
    }

    companion object {
        private const val STATE_USER_ACTION_GENERATION = "userActionGeneration"
        private val OPERATION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }

    private fun button(label: Int) = Button(this).apply {
        setText(label)
        minHeight = dp(48)
        isAllCaps = false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
