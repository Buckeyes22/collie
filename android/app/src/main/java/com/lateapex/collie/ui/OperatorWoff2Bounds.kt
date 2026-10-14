package com.lateapex.collie.ui

/**
 * Admission bounds before native decoding; this is not a complete font/Brotli validator.
 * W3C https://www.w3.org/TR/2024/REC-WOFF2-20240808/ sections 3.1–4.1, 5.3–5.4;
 * Google https://github.com/google/woff2/blob/master/src/woff2_dec.cc ReadTableDirectory.
 * Transformed glyph reconstruction can exceed origLength: the JNI decoder still needs its own
 * hard output/allocation ceiling, and its resulting file must be bounded separately.
 */
internal object OperatorWoff2Bounds {
    private const val INPUT_LIMIT = 2 * 1024 * 1024
    private const val SIZE_LIMIT = 16L * 1024 * 1024
    private const val HEADER_SIZE = 48
    private const val TTC = 0x74746366L
    private val knownTags = listOf(
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG", "EBDT",
        "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH",
        "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar", "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar",
        "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
    ).map { tag -> tag.fold(0L) { value, char -> (value shl 8) or char.code.toLong() } }
    private val glyf = knownTags[10]
    private val loca = knownTags[11]
    private val hmtx = knownTags[3]

    /** Returns the declared SFNT size only after bounded directory admission, or throws. */
    fun validate(bytes: ByteArray): Long {
        require(bytes.size in HEADER_SIZE..INPUT_LIMIT) { "Invalid WOFF2 input size" }
        require(u32(bytes, 0) == 0x774f4632L && u32(bytes, 8) == bytes.size.toLong()) { "Invalid WOFF2 header" }
        require(u32(bytes, 4) != TTC) { "WOFF2 collections are unsupported" }
        val tables = u16(bytes, 12)
        require(tables in 1..256) { "Invalid WOFF2 table count" }
        val declared = u32(bytes, 16)
        require(declared in 12..SIZE_LIMIT) { "WOFF2 SFNT estimate exceeds its limit" }
        val compressed = u32(bytes, 20)
        val cursor = Cursor(bytes)
        var sfnt = 12L + 16L * tables
        var stream = 0L
        val tags = HashSet<Long>()
        var glyfTransformed: Boolean? = null
        var locaTransformed: Boolean? = null
        var hmtxTransformed = false
        repeat(tables) {
            val flags = cursor.byte()
            val index = flags and 63
            val tag = if (index == 63) cursor.tag() else knownTags[index]
            require(tags.add(tag)) { "Duplicate WOFF2 table" }
            val version = flags ushr 6
            val transformed = when (tag) {
                glyf, loca -> {
                    require(version == 0 || version == 3) { "Unknown WOFF2 glyph transform" }
                    version != 3
                }
                hmtx -> {
                    require(version == 0 || version == 1) { "Unknown WOFF2 metric transform" }
                    version != 0
                }
                else -> {
                    require(version == 0) { "Unsupported WOFF2 table transform" }
                    false
                }
            }
            val original = cursor.base128()
            val transformedLength = if (transformed) cursor.base128() else original
            if (tag == loca && transformed) require(transformedLength == 0L) { "Invalid WOFF2 transformed loca length" }
            sfnt += (original + 3L) and -4L
            stream += transformedLength
            require(sfnt <= SIZE_LIMIT && stream <= SIZE_LIMIT) { "WOFF2 table lengths exceed their limit" }
            when (tag) {
                glyf -> glyfTransformed = transformed
                loca -> locaTransformed = transformed
                hmtx -> hmtxTransformed = transformed
            }
        }
        require(glyfTransformed == locaTransformed) { "Mismatched WOFF2 glyph tables" }
        require(!hmtxTransformed || glyfTransformed != null) { "WOFF2 metric transform needs glyphs" }
        // A stricter operator-font admission policy, not a promise about transformed glyph output.
        require(declared == sfnt) { "WOFF2 SFNT estimate disagrees with its directory" }
        require(compressed > 0L && compressed <= bytes.size.toLong() - cursor.position) { "Truncated WOFF2 compressed data" }
        return declared
    }

    private fun u16(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
    private fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[at + index].toLong() and 255L) }

    private class Cursor(private val bytes: ByteArray) {
        var position = HEADER_SIZE
            private set
        fun byte(): Int {
            require(position < bytes.size) { "Truncated WOFF2 table directory" }
            return bytes[position++].toInt() and 255
        }
        fun tag(): Long = (0..3).fold(0L) { value, _ -> (value shl 8) or byte().toLong() }
        fun base128(): Long {
            var value = 0L
            repeat(5) { index ->
                val next = byte()
                require(index != 0 || next != 0x80) { "Leading zero in WOFF2 Base128" }
                require(value <= 0x01ffffffL) { "Overflow in WOFF2 Base128" }
                value = (value shl 7) or (next and 127).toLong()
                if (next and 128 == 0) return value
            }
            throw IllegalArgumentException("Overlong WOFF2 Base128")
        }
    }
}
