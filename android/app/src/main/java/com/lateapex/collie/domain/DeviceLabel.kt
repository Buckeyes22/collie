package com.lateapex.collie.domain

/** The bridge's pairing.normalizeLabel contract, measured in UTF-16 code units. */
object DeviceLabel {
    const val MAX_CHARS = 48
    fun normalize(raw: String): String = raw.replace(Regex("[\\r\\n\\t]+"), " ").trim()
}
