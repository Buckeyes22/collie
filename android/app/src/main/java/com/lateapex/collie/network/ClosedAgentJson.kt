package com.lateapex.collie.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bounded duplicate-free syntax before typed decoding. No response text is logged or retained. */
internal object ClosedAgentJson {
    fun parse(text: String): JsonObject? = try {
        if (text.toByteArray(Charsets.UTF_8).size > AGENT_AUTOMATION_RESPONSE_BYTES) null
        else { Scanner(text).document(); Json.parseToJsonElement(text) as? JsonObject }
    } catch (_: Exception) { null }
    private class Scanner(private val text: String) {
        private var at = 0
        private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
        fun document() { value(0); whitespace(); require(at == text.length) }
        private fun value(depth: Int) {
            require(depth <= 8); whitespace(); require(at < text.length)
            when (text[at]) {
                '{' -> {
                    at++; whitespace(); val keys = mutableSetOf<String>()
                    if (take('}')) return
                    do { whitespace(); val key = string(); require(keys.add(key) && keys.size <= 64); whitespace(); require(take(':')); value(depth + 1); whitespace() } while (take(','))
                    require(take('}'))
                }
                '[' -> {
                    at++; whitespace(); if (take(']')) return; var entries = 0
                    do { require(++entries <= 64); value(depth + 1); whitespace() } while (take(','))
                    require(take(']'))
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }
        private fun take(char: Char): Boolean = if (at < text.length && text[at] == char) { at++; true } else false
        private fun literal(word: String) { require(text.startsWith(word, at)); at += word.length }
        private fun string(): String {
            val start = at; require(take('"')); var closed = false
            while (at < text.length) {
                val char = text[at++]; require(char.code >= 32)
                if (char == '"') { closed = true; break }
                if (char == '\\') {
                    require(at < text.length); val escaped = text[at++]
                    if (escaped == 'u') { require(at + 4 <= text.length && text.substring(at, at + 4).all { it in "0123456789abcdefABCDEF" }); at += 4 }
                    else require(escaped in "\"\\/bfnrt")
                }
            }
            require(closed)
            val parsed = Json.parseToJsonElement(text.substring(start, at)) as JsonPrimitive
            require(parsed.isString); val result = parsed.content
            var index = 0
            while (index < result.length) { val char = result[index++]; if (char.isHighSurrogate()) { require(index < result.length && result[index].isLowSurrogate()); index++ } else require(!char.isLowSurrogate()) }
            return result
        }
        private fun number() {
            val start = at; take('-'); require(at < text.length)
            if (!take('0')) { require(text[at] in '1'..'9'); while (at < text.length && text[at] in '0'..'9') at++ }
            if (take('.')) { val first = at; while (at < text.length && text[at] in '0'..'9') at++; require(at > first) }
            if (at < text.length && text[at] in "eE") { at++; if (at < text.length && text[at] in "+-") at++; val first = at; while (at < text.length && text[at] in '0'..'9') at++; require(at > first) }
            require(at > start && text.substring(start, at).toDouble().isFinite())
        }
    }
}
