package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production Keystore cipher and encrypted SharedPreferences on API 26+. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class LaunchIntentStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearJournal() {
        context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun encryptedPendingIntentSurvivesStoreRecreationAsUnknown() {
        val requestId = "11111111-1111-4111-8111-111111111111"
        val secretBody = "{\"run\":\"private command text\"}"
        val intent = FrozenLaunchIntent(
            requestId = requestId,
            origin = "https://collie.example",
            mount = "https://collie.example",
            connectionGeneration = 1L,
            pairedDeviceIdentity = "paired-phone",
            leadEpoch = 1L,
            host = "build-host",
            session = "default",
            subjectIdentity = "workspace:repo",
            operation = "launch",
            confirmedNoPrompts = false,
            canonicalBody = secretBody,
        )

        val firstProcess = LaunchIntentStore(context)
        assertTrue(firstProcess.reserve(intent) is ReserveIntentResult.Reserved)
        val raw = context.getSharedPreferences("collie_launch_intents", Context.MODE_PRIVATE)
            .getString("encrypted_records", null).orEmpty()
        assertFalse(raw.contains(secretBody))
        assertFalse(raw.contains("collie.example"))

        val restartedProcess = LaunchIntentStore(context)
        assertEquals(LaunchIntentState.unknown, restartedProcess.get(requestId)?.state)
        assertEquals(intent.bodySha256, restartedProcess.get(requestId)?.intent?.bodySha256)
    }
}
