package com.lateapex.collie.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxKeyGrammarTest {
    @Test
    fun acceptsTheCompleteNeutralKeyboardContract() {
        listOf(
            "Escape", "Space", "PageDown", "F1", "F12", "a", "/", "+", "👋",
            "ctrl+c", "shift+Tab", "alt+Up", "ctrl+alt+Delete", "ctrl++",
        ).forEach { assertTrue(it, MuxKeyGrammar.isValid(it)) }
    }

    @Test
    fun rejectsUnknownDuplicateAndNonCanonicalChords() {
        listOf("", "Nope", "ctrl", "ctrl+", "ctrl+ctrl+c", "shift+ctrl+c", "hyper+c", "Ctrl c")
            .forEach { assertFalse(it, MuxKeyGrammar.isValid(it)) }
    }
}
