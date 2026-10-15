package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Typeface
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OperatorFontRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Loads only a row the captured Collie actor still advertises, then installs it for that owner. */
internal class OperatorTypefaceLoader(
    context: Context,
    private val repository: CollieRepository,
    private val actorIsCurrent: (ScopedReadContext) -> Boolean = repository::isCurrent,
    private val fetch: suspend (OperatorFontRow, ScopedReadContext) -> ApiResult<ByteArray> = repository::operatorFont,
    private val parse: ((OperatorFontRow, ByteArray) -> Typeface)? = null,
    private val publish: (OperatorFontRow, ScopedReadContext, Typeface, () -> Boolean) -> Boolean =
        { row, actor, face, current -> PreferredTypeface.publishOperator(context.applicationContext, row, actor, face, current) },
) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data object Stale : Result
        data class Failed(val detail: String) : Result
        data class Loaded(val row: OperatorFontRow) : Result
    }

    suspend fun select(
        row: OperatorFontRow,
        actor: ScopedReadContext,
        isCurrentSelection: () -> Boolean = { true },
    ): Result {
        if (!row.valid()) return Result.Failed("Invalid font declaration")
        if (!isCurrentSelection() || !actorIsCurrent(actor)) return Result.Stale
        return when (val response = fetch(row, actor)) {
            is ApiResult.Failure -> Result.Failed(response.error.displayText())
            is ApiResult.NotModified -> Result.Failed("Font response was not available")
            is ApiResult.Success -> {
                if (!isCurrentSelection() || !actorIsCurrent(actor)) return Result.Stale
                val typeface = try {
                    withContext(Dispatchers.IO) {
                        parseMutex.withLock {
                            if (!isCurrentSelection() || !actorIsCurrent(actor)) null
                            else parse?.invoke(row, response.value) ?: buildTypeface(row, response.value)
                        }
                    } ?: return Result.Stale
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return Result.Failed(error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
                }
                // The selection fence is checked after synchronous platform parsing, immediately
                // before publication; an older held response can never overwrite a newer choice.
                if (!isCurrentSelection() || !actorIsCurrent(actor)) return Result.Stale
                if (!publish(row, actor, typeface, isCurrentSelection)) return Result.Stale
                Result.Loaded(row)
            }
        }
    }

    internal fun buildTypeface(row: OperatorFontRow, bytes: ByteArray): Typeface = OperatorWoff2Codec.build(appContext, row, bytes)

    private fun ApiFailure.displayText(): String = when (this) {
        is ApiFailure.Http -> "HTTP $status"
        is ApiFailure.Network -> "Network unavailable"
        is ApiFailure.Protocol -> message
        is ApiFailure.Redirect -> "Redirect refused (HTTP $status)"
        ApiFailure.Timeout -> "Request timed out"
    }

    private companion object {
        // Font parsing uses a direct buffer and platform/native memory. Serialize builders so
        // cancellation during a native parse cannot let repeated taps accumulate buffers.
        val parseMutex = Mutex()
        val WOFF2_MAGIC = byteArrayOf(0x77, 0x4f, 0x46, 0x32)
    }
}
