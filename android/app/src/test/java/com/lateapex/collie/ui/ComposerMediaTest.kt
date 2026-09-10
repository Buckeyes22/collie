package com.lateapex.collie.ui

import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.BridgeConfigResponse
import com.lateapex.collie.network.SttCapability
import com.lateapex.collie.network.SttResponse
import com.lateapex.collie.network.UploadResponse
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerMediaTest {
    private val text = object : ComposerMediaText {
        override val hostMissingImagePath = "The host did not return an image path."
        override val imageAdded = "Image added — path in message."
        override val imageUploadSubject = "Image upload"
        override val imageUploadNoResult = "Image upload returned no result."
        override val noSpeechDetected = "No speech was detected."
        override val transcriptAdded = "Transcript added for review."
        override val transcriptionSubject = "Transcription"
        override val transcriptionNoResult = "Transcription returned no result."
        override val imageMissingMediaType = "The selected image has no media type."
        override val chooseImage = "Choose an image file."
        override val imageUnreadable = "The selected image could not be read."
        override val imageTooLarge = "That image is too large — 10 MB is the limit."
        override val imageEmpty = "The selected image is empty."
        override fun providerUnavailable(provider: String) = "$provider is unavailable."
        override fun failure(subject: String, failure: com.lateapex.collie.network.ApiFailure) = subject
    }
    @Test
    fun boundedReadAcceptsTheLimitAndRejectsOneByteMore() {
        val exact = ByteArray(16) { it.toByte() }

        assertArrayEquals(exact, ComposerMedia.readBounded(ByteArrayInputStream(exact), 16))
        assertNull(ComposerMedia.readBounded(ByteArrayInputStream(ByteArray(17)), 16))
    }

    @Test
    fun hostPathsAndTranscriptsAppendWithoutSubmittingOrDamagingWhitespace() {
        assertEquals("/host/upload.png", ComposerMedia.appendToDraft("", "/host/upload.png"))
        assertEquals("please inspect /host/upload.png", ComposerMedia.appendToDraft("please inspect", "/host/upload.png"))
        assertEquals("line one\ntranscript", ComposerMedia.appendToDraft("line one\n", "transcript"))
    }

    @Test
    fun providerReasonIsExposedAndAbsentCapabilityHidesSpeech() = runBlocking {
        val unavailable = actions(
            BridgeConfigResponse(stt = SttCapability("local", false, "model is offline")),
        ).speechAvailability()
        val hidden = actions(BridgeConfigResponse()).speechAvailability()

        assertEquals(SpeechAvailability.Unavailable("local", "model is offline"), unavailable)
        assertEquals(SpeechAvailability.Hidden, hidden)
    }

    @Test
    fun successfulImageUploadReturnsOnlyTheHostPathForLateDraftRebasing() = runBlocking {
        val upload = com.lateapex.collie.network.ImageUpload("shot.png", "image/png", byteArrayOf(1))

        assertEquals(
            ComposerMediaResult.Draft("/host/image.png", "Image added — path in message."),
            actions(BridgeConfigResponse()).uploadImage(upload),
        )
    }

    @Test
    fun handsFreeSpeechRequestsGuardedAutoSend() = runBlocking {
        var transcriptCalls = 0
        val actions = ComposerMediaActions(
            config = { ApiResult.Success(BridgeConfigResponse(), 200) },
            upload = { ApiResult.Success(UploadResponse(ok = true, path = "/host/image.png"), 200) },
            transcribe = {
                transcriptCalls += 1
                ApiResult.Success(SttResponse(ok = true, text = "hello collie"), 200)
            },
            handsFreeEnabled = { true },
            text = text,
        )

        val result = actions.transcribe(com.lateapex.collie.network.AudioUpload("audio/mp4", byteArrayOf(1)))

        assertEquals(1, transcriptCalls)
        assertEquals(
            ComposerMediaResult.AutoSend("hello collie"),
            result,
        )
    }

    @Test
    fun everyUnsafeHandsFreeStateFallsBackToCaretInsertion() {
        val safe = HandsFreeGuard(
            draftEmpty = true,
            composerReady = true,
            canWrite = true,
            sending = false,
            directTyping = false,
            overlayOpen = false,
            activityHasFocus = true,
        )
        val unsafe = listOf(
            safe.copy(draftEmpty = false),
            safe.copy(composerReady = false),
            safe.copy(canWrite = false),
            safe.copy(sending = true),
            safe.copy(directTyping = true),
            safe.copy(overlayOpen = true),
            safe.copy(activityHasFocus = false),
        )
        val transcript = ComposerMediaResult.AutoSend("spoken words")

        assertEquals(transcript, ComposerMedia.enforceHandsFreeGuard(transcript, safe, text))
        unsafe.forEach { guard ->
            assertEquals(
                ComposerMediaResult.Draft("spoken words", "Transcript added for review.", atCaret = true),
                ComposerMedia.enforceHandsFreeGuard(transcript, guard, text),
            )
        }
    }

    @Test
    fun transcriptInsertionMatchesWebCaretAndSpacingBehavior() {
        assertEquals(DraftInsertion("alpha spoken beta", 13), ComposerMedia.insertAtCaret("alphabeta", 5, 5, "spoken "))
        assertEquals(DraftInsertion("alpha spoken", 12), ComposerMedia.insertAtCaret("alpha old", 5, 9, "spoken"))
        assertEquals(DraftInsertion("spokenalpha", 6), ComposerMedia.insertAtCaret("alpha", 0, 0, "spoken"))
    }

    private fun actions(config: BridgeConfigResponse) = ComposerMediaActions(
        config = { ApiResult.Success(config, 200) },
        upload = { ApiResult.Success(UploadResponse(ok = true, path = "/host/image.png"), 200) },
        transcribe = { ApiResult.Success(SttResponse(ok = true, text = "draft"), 200) },
        handsFreeEnabled = { false },
        text = text,
    )
}
