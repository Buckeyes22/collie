package com.lateapex.collie.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal class ApkDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val updatesDirectory = File(applicationContext.cacheDir, UPDATE_DIRECTORY)
    private var originalRepositoryForTests: AndroidReleaseRepository? = null

    /** Instance-only fixture seam; the production two-argument constructor is unchanged. */
    internal constructor(context: Context, params: WorkerParameters, originalRepository: AndroidReleaseRepository) : this(context, params) {
        originalRepositoryForTests = originalRepository
    }

    override suspend fun doWork(): Result {
        // Capture once, before any wait. Production always uses its pinned repository.
        val repository = originalRepositoryForTests ?: AndroidReleaseRepository(applicationContext)
        val operationId = inputData.getString(INPUT_OPERATION_ID)?.takeIf(OPERATION_ID::matches)
            ?: return Result.failure(workDataOf(OUTPUT_REASON to FAILURE_INVALID_INPUT))
        return try {
            setProgress(workDataOf(INPUT_OPERATION_ID to operationId))
            when (val downloaded = repository.download(
                operationId = operationId,
                directory = updatesDirectory,
                reportProgress = { completed, total ->
                    setProgress(workDataOf(INPUT_OPERATION_ID to operationId, PROGRESS_COMPLETE to completed, PROGRESS_TOTAL to total))
                },
            )) {
                is AndroidApkDownload.Ready -> Result.success(
                    workDataOf(OUTPUT_OPERATION_ID to operationId, OUTPUT_TAG to downloaded.candidate.tag, OUTPUT_APK_PATH to downloaded.apk.absolutePath, OUTPUT_VERSION_CODE to downloaded.candidate.versionCode),
                )
                is AndroidApkDownload.Failed -> {
                    if (downloaded.reason == AndroidReleaseLookup.Reason.NETWORK && runAttemptCount < MAX_RETRIES) {
                        Result.retry()
                    } else {
                        repository.markDownloadFailed(operationId)
                        Result.failure(workDataOf(OUTPUT_REASON to downloaded.reason.name))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            repository.cancelActiveRequest(operationId)
            repository.markDownloadCanceled(operationId)
            throw cancelled
        } catch (_: Exception) {
            repository.markDownloadFailed(operationId)
            Result.failure(workDataOf(OUTPUT_REASON to FAILURE_UNEXPECTED))
        }
    }

    companion object {
        const val INPUT_OPERATION_ID = "releaseOperationId"
        const val PROGRESS_COMPLETE = "downloadedBytes"
        const val PROGRESS_TOTAL = "totalBytes"
        const val OUTPUT_APK_PATH = "verifiedApkPath"
        const val OUTPUT_VERSION_CODE = "verifiedVersionCode"
        const val OUTPUT_TAG = "releaseTag"
        const val OUTPUT_OPERATION_ID = "releaseOperationId"
        const val OUTPUT_REASON = "failureReason"
        const val FAILURE_INVALID_INPUT = "invalidInput"
        const val FAILURE_CANCELLED = "cancelled"
        const val FAILURE_UNEXPECTED = "unexpected"
        const val UPDATE_DIRECTORY = "android-updates"
        const val DOWNLOAD_TAG = "android-release-download"
        private const val UNIQUE_WORK_NAME = "android-release-download-"
        const val MAX_RETRIES = 3
        private val OPERATION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

        fun enqueue(context: Context, operationId: String) {
            require(OPERATION_ID.matches(operationId))
            val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
                .setInputData(workDataOf(INPUT_OPERATION_ID to operationId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria( androidx.work.BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .addTag(operationId)
                .addTag(DOWNLOAD_TAG)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueWorkName(operationId), ExistingWorkPolicy.KEEP, request,
            )
        }

        fun cancel(context: Context, operationId: String) {
            if (!OPERATION_ID.matches(operationId)) return
            cancel(context, operationId, AndroidReleaseRepository(context.applicationContext))
        }

        /** A caller with an existing original repository retains its trust/store witness;
         * the durable method checks this exact UUID against current persisted custody. */
        internal fun cancel(context: Context, operationId: String, originalRepository: AndroidReleaseRepository) {
            if (!OPERATION_ID.matches(operationId)) return
            // Commit exact cancellation even when a queued worker never enters doWork.
            originalRepository.markDownloadCanceled(operationId)
            cancelQueuedOnly(context, operationId)
        }

        internal fun cancelQueuedOnly(context: Context, operationId: String) {
            if (!OPERATION_ID.matches(operationId)) return
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueWorkName(operationId))
        }

        fun uniqueWorkName(operationId: String): String {
            require(OPERATION_ID.matches(operationId))
            return UNIQUE_WORK_NAME + operationId
        }
    }
}
