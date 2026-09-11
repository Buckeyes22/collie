package com.lateapex.collie.ui

import android.content.ContentResolver
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import com.lateapex.collie.network.AudioUpload
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.ImageUpload
import com.lateapex.collie.network.SttCapability
import com.lateapex.collie.network.SttResponse
import com.lateapex.collie.network.UploadResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface ImageSelectionResult {
    data class Ready(val upload: ImageUpload) : ImageSelectionResult
    data class Rejected(val reason: String) : ImageSelectionResult
}

internal sealed interface SpeechAvailability {
    data object Hidden : SpeechAvailability
    data class Available(val provider: String) : SpeechAvailability
    data class Unavailable(val provider: String, val reason: String) : SpeechAvailability
}

internal sealed interface ComposerMediaResult {
    data class Draft(
        val insertion: String,
        val message: String,
        val atCaret: Boolean = false,
    ) : ComposerMediaResult
    data class AutoSend(val transcript: String) : ComposerMediaResult
    data class Failure(val message: String) : ComposerMediaResult
}

internal data class HandsFreeGuard(
    val draftEmpty: Boolean,
    val composerReady: Boolean,
    val canWrite: Boolean,
    val sending: Boolean,
    val directTyping: Boolean,
    val overlayOpen: Boolean,
    val activityHasFocus: Boolean,
) {
    val allowsAutoSend: Boolean
        get() = draftEmpty && composerReady && canWrite && !sending && !directTyping && !overlayOpen && activityHasFocus
}

internal data class DraftInsertion(val text: String, val caret: Int)

internal interface ComposerMediaText {
    val hostMissingImagePath: String
    val imageAdded: String
    val imageUploadSubject: String
    val imageUploadNoResult: String
    val noSpeechDetected: String
    val transcriptAdded: String
    val transcriptionSubject: String
    val transcriptionNoResult: String
    val imageMissingMediaType: String
    val chooseImage: String
    val imageUnreadable: String
    val imageTooLarge: String
    val imageEmpty: String
    fun providerUnavailable(provider: String): String
    fun failure(subject: String, failure: ApiFailure): String
}

internal class AndroidComposerMediaText(context: Context) : ComposerMediaText {
    private val resources = context.applicationContext.resources
    override val hostMissingImagePath get() = resources.getString(com.lateapex.collie.R.string.composer_media_host_path_missing)
    override val imageAdded get() = resources.getString(com.lateapex.collie.R.string.composer_media_image_added)
    override val imageUploadSubject get() = resources.getString(com.lateapex.collie.R.string.composer_media_image_upload)
    override val imageUploadNoResult get() = resources.getString(com.lateapex.collie.R.string.composer_media_image_no_result)
    override val noSpeechDetected get() = resources.getString(com.lateapex.collie.R.string.composer_media_no_speech)
    override val transcriptAdded get() = resources.getString(com.lateapex.collie.R.string.composer_media_transcript_added)
    override val transcriptionSubject get() = resources.getString(com.lateapex.collie.R.string.composer_media_transcription)
    override val transcriptionNoResult get() = resources.getString(com.lateapex.collie.R.string.composer_media_transcription_no_result)
    override val imageMissingMediaType get() = resources.getString(com.lateapex.collie.R.string.composer_media_image_no_type)
    override val chooseImage get() = resources.getString(com.lateapex.collie.R.string.composer_media_choose_image)
    override val imageUnreadable get() = resources.getString(com.lateapex.collie.R.string.composer_media_image_unreadable)
    override val imageTooLarge get() = resources.getString(com.lateapex.collie.R.string.composer_media_image_too_large)
    override val imageEmpty get() = resources.getString(com.lateapex.collie.R.string.composer_media_image_empty)
    override fun providerUnavailable(provider: String): String =
        resources.getString(com.lateapex.collie.R.string.composer_media_provider_unavailable, provider)
    override fun failure(subject: String, failure: ApiFailure): String = resources.getString(
        com.lateapex.collie.R.string.composer_media_failure,
        subject,
        MainViewModel.describe(resources, failure),
    )
}

/** Network-facing composer actions, dependency-injected so draft-only behavior is unit-testable. */
internal class ComposerMediaActions(
    private val config: suspend () -> ApiResult<BridgeConfigResponse>,
    private val upload: suspend (ImageUpload) -> ApiResult<UploadResponse>,
    transcribe: suspend (AudioUpload) -> ApiResult<SttResponse>,
    private val handsFreeEnabled: () -> Boolean,
    private val text: ComposerMediaText,
) {
    private val transcribeRequest = transcribe

    suspend fun speechAvailability(): SpeechAvailability = when (val result = config()) {
        is ApiResult.Success -> result.value.stt.toAvailability()
        is ApiResult.Failure -> SpeechAvailability.Hidden
        is ApiResult.NotModified -> SpeechAvailability.Hidden
    }

    suspend fun uploadImage(selected: ImageUpload): ComposerMediaResult =
        when (val result = upload(selected)) {
            is ApiResult.Success -> {
                val path = result.value.path
                if (path.isNullOrBlank()) {
                    ComposerMediaResult.Failure(text.hostMissingImagePath)
                } else {
                    ComposerMediaResult.Draft(
                        path,
                        text.imageAdded,
                    )
                }
            }
            is ApiResult.Failure -> ComposerMediaResult.Failure(failureMessage(text.imageUploadSubject, result.error))
            is ApiResult.NotModified -> ComposerMediaResult.Failure(text.imageUploadNoResult)
        }

    suspend fun transcribe(audio: AudioUpload): ComposerMediaResult =
        when (val result = transcribeRequest(audio)) {
            is ApiResult.Success -> {
                val transcript = result.value.text
                if (transcript.isNullOrBlank()) {
                    ComposerMediaResult.Failure(text.noSpeechDetected)
                } else {
                    if (handsFreeEnabled()) {
                        ComposerMediaResult.AutoSend(transcript)
                    } else {
                        ComposerMediaResult.Draft(transcript, text.transcriptAdded, atCaret = true)
                    }
                }
            }
            is ApiResult.Failure -> ComposerMediaResult.Failure(failureMessage(text.transcriptionSubject, result.error))
            is ApiResult.NotModified -> ComposerMediaResult.Failure(text.transcriptionNoResult)
        }

    private fun SttCapability?.toAvailability(): SpeechAvailability = when {
        this == null -> SpeechAvailability.Hidden
        available -> SpeechAvailability.Available(provider)
        else -> SpeechAvailability.Unavailable(provider, reason ?: text.providerUnavailable(provider))
    }

    private fun failureMessage(subject: String, failure: ApiFailure): String =
        text.failure(subject, failure)
}

/** Phone-owned media preparation. Nothing crosses the network from this helper. */
internal object ComposerMedia {
    /** A square thumbnail of an image's bytes, or null when they do not decode. */
    fun thumbnail(bytes: ByteArray, sizePx: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return android.media.ThumbnailUtils.extractThumbnail(decoded, sizePx, sizePx)
    }

    suspend fun imageUpload(
        resolver: ContentResolver,
        uri: Uri,
        text: ComposerMediaText,
    ): ImageSelectionResult =
        withContext(Dispatchers.IO) {
            val contentType = try {
                resolver.getType(uri)?.lowercase()
            } catch (_: RuntimeException) {
                null
            }
                ?: return@withContext ImageSelectionResult.Rejected(text.imageMissingMediaType)
            if (!contentType.startsWith("image/")) {
                return@withContext ImageSelectionResult.Rejected(text.chooseImage)
            }
            val displayName = queryDisplayName(resolver, uri) ?: "collie-image"
            val stream = try {
                resolver.openInputStream(uri)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } ?: return@withContext ImageSelectionResult.Rejected(text.imageUnreadable)
            val bytes = try {
                stream.use { readBounded(it, ImageUpload.MAX_BYTES) }
            } catch (_: IOException) {
                return@withContext ImageSelectionResult.Rejected(text.imageUnreadable)
            } catch (_: SecurityException) {
                return@withContext ImageSelectionResult.Rejected(text.imageUnreadable)
            } ?: return@withContext ImageSelectionResult.Rejected(text.imageTooLarge)
            if (bytes.isEmpty()) {
                return@withContext ImageSelectionResult.Rejected(text.imageEmpty)
            }
            ImageSelectionResult.Ready(ImageUpload(displayName, contentType, bytes))
        }

    internal fun readBounded(input: InputStream, maximumBytes: Int): ByteArray? {
        require(maximumBytes > 0)
        val output = ByteArrayOutputStream(minOf(maximumBytes, BUFFER_BYTES))
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > maximumBytes - count) return null
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    internal fun appendToDraft(draft: String, value: String): String = when {
        draft.isBlank() -> value
        draft.last().isWhitespace() -> draft + value
        else -> "$draft $value"
    }

    internal fun insertAtCaret(
        draft: String,
        selectionStart: Int,
        selectionEnd: Int,
        value: String,
    ): DraftInsertion {
        val start = selectionStart.coerceIn(0, draft.length)
        val end = selectionEnd.coerceIn(start, draft.length)
        val before = draft.substring(0, start)
        val after = draft.substring(end)
        val inserted = if (before.isNotEmpty() && !before.last().isWhitespace()) " $value" else value
        return DraftInsertion(before + inserted + after, start + inserted.length)
    }

    internal fun enforceHandsFreeGuard(
        result: ComposerMediaResult,
        guard: HandsFreeGuard,
        text: ComposerMediaText,
    ): ComposerMediaResult = if (result is ComposerMediaResult.AutoSend && !guard.allowsAutoSend) {
        ComposerMediaResult.Draft(result.transcript, text.transcriptAdded, atCaret = true)
    } else {
        result
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else cursor.getString(index)
        }?.sanitizeFileName()
    } catch (_: RuntimeException) {
        null
    }

    private fun String.sanitizeFileName(): String? =
        substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl)
            .trim().take(MAX_FILE_NAME_CHARS).takeIf(String::isNotEmpty)

    private const val BUFFER_BYTES = 16 * 1024
    private const val MAX_FILE_NAME_CHARS = 120
}

/** One explicit tap-to-record session backed by a private cache file. */
internal class ComposerSpeechRecorder(
    context: Context,
    private val onHardLimit: () -> Unit,
) {
    private val cacheDirectory = context.applicationContext.cacheDir
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null

    val isRecording: Boolean get() = recorder != null

    @Suppress("DEPRECATION")
    fun start(): Result<Unit> = runCatching {
        check(recorder == null) { "A recording is already in progress" }
        val file = File.createTempFile("collie-speech-", ".m4a", cacheDirectory)
        recordingFile = file
        val candidate = MediaRecorder()
        recorder = candidate
        candidate.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(AUDIO_BIT_RATE)
            setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            setMaxDuration(MAX_DURATION_MS)
            setMaxFileSize(AudioUpload.MAX_BYTES.toLong())
            setOutputFile(file.absolutePath)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    onHardLimit()
                }
            }
            prepare()
            start()
        }
        Unit
    }.onFailure { cancel() }

    fun stop(): Result<AudioUpload> {
        val active = recorder ?: return Result.failure(IllegalStateException("No recording is in progress"))
        val file = recordingFile
        recorder = null
        recordingFile = null
        return runCatching {
            active.stop()
            val bytes = file?.inputStream()?.use { ComposerMedia.readBounded(it, AudioUpload.MAX_BYTES) }
                ?: throw IOException("The recording could not be read")
            AudioUpload("audio/mp4", bytes)
        }.also {
            runCatching { active.reset() }
            runCatching { active.release() }
            file?.delete()
        }
    }

    fun cancel() {
        val active = recorder
        recorder = null
        val file = recordingFile
        recordingFile = null
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        file?.delete()
    }

    private companion object {
        const val MAX_DURATION_MS = 5 * 60 * 1000
        const val AUDIO_BIT_RATE = 64_000
        const val AUDIO_SAMPLE_RATE = 44_100
    }
}
