package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.ScopedRequestKey
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OperatorFontRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OperatorTypefaceLoaderFenceTest {
    @Test
    fun heldFontResponseCannotPublishAfterSelectionChangesToSystemOrAnotherFont() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context.applicationContext as CollieApplication).container.repository
        val actor = ScopedReadContext(
            ScopedRequestKey("https://collie.example/", 1, "phone", 0, null, null, null),
            Connection(CollieOrigin("https://collie.example/"), "phone", "fixture-token"),
            navigationGeneration = 0,
        )
        val rowA = OperatorFontRow("Font A", "font-a.woff2")
        val response = CompletableDeferred<ApiResult<ByteArray>>()
        var generation = 1
        var parseCalls = 0
        var publishCalls = 0
        val loader = OperatorTypefaceLoader(
            context,
            repository,
            actorIsCurrent = { true },
            fetch = { _, _ -> response.await() },
            parse = { _, _ -> parseCalls++; Typeface.DEFAULT },
            publish = { _, _, _, _ -> publishCalls++; true },
        )

        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            loader.select(rowA, actor) { generation == 1 }
        }
        // A user can choose System or Font B while A's network response is still outstanding.
        generation = 2
        response.complete(ApiResult.Success(byteArrayOf(0x77, 0x4f, 0x46, 0x32), 200))

        assertEquals(OperatorTypefaceLoader.Result.Stale, pending.await())
        assertEquals(0, parseCalls)
        assertEquals(0, publishCalls)
        assertFalse(generation == 1)
    }
}
