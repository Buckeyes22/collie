package com.lateapex.collie.domain

/** Android copy of bridge/mux/keys.ts's closed neutral key grammar. */
object MuxKeyGrammar {
    private val named = setOf(
        "Up", "Down", "Left", "Right", "Tab", "Enter", "Escape", "Space", "Backspace",
        "Delete", "Insert", "Home", "End", "PageUp", "PageDown",
        "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
    )
    private val modifierOrder = listOf("ctrl", "alt", "shift", "meta")
    private val modifiers = modifierOrder.toSet()

    fun isValid(spelling: String): Boolean {
        if (spelling.isEmpty()) return false
        if (isLiteral(spelling)) return true
        if (spelling in named) return true
        val parts = spelling.split('+')
        if (parts.size < 2) return false
        val plusKey = parts.size >= 3 && parts.takeLast(2).all(String::isEmpty)
        val key = if (plusKey) "+" else parts.last()
        val heads = if (plusKey) parts.dropLast(2) else parts.dropLast(1)
        if (heads.isEmpty() || heads.any { it !in modifiers } || heads.distinct().size != heads.size) return false
        if (heads != modifierOrder.filter(heads::contains)) return false
        return key in named || isLiteral(key)
    }

    private fun isLiteral(value: String): Boolean =
        value.codePointCount(0, value.length) == 1 && value.codePointAt(0) >= 0x20
}
