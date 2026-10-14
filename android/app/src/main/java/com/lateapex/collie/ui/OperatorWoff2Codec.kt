package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import com.lateapex.collie.network.OperatorFontRow
import java.nio.ByteBuffer

/** The platform reads SFNT; the bounded native decoder converts the advertised WOFF2. */
internal object OperatorWoff2Codec {
    @Suppress("UNUSED_PARAMETER")
    fun build(context: Context, row: OperatorFontRow, bytes: ByteArray): Typeface {
        require(row.valid()) { "Invalid operator font declaration" }
        val decoded = BoundedWoff2Decoder.decode(bytes)
        val buffer = ByteBuffer.allocateDirect(decoded.size).apply { put(decoded); flip() }
        val builder = Font.Builder(buffer)
        row.weight?.substringBefore(' ')?.toIntOrNull()?.let(builder::setWeight)
        val font = builder.build()
        return Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).setSystemFallback("sans-serif").build()
    }
}
