package com.lateapex.collie.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DirectTypingEditTextTest {
    @Test
    fun codecNormalizesLineEndingsAndPreservesUnicodeCodePoints() {
        assertEquals(
            listOf("a", "Space", "Tab", "Enter", "b", "Enter", "👋"),
            DirectTypingEditText.textToKeys("a \t\r\nb\r👋"),
        )
    }

    @Test
    fun compositionStaysLocalUntilOneCommit() {
        val field = DirectTypingEditText(ApplicationProvider.getApplicationContext())
        val emitted = mutableListOf<List<String>>()
        field.onDirectKeys = emitted::add
        field.beginDirectTyping()
        val input = field.onCreateInputConnection(EditorInfo())!!

        input.setComposingText("h", 1)
        input.setComposingText("hello", 1)
        assertTrue(emitted.isEmpty())
        input.commitText("hello", 1)

        assertEquals(listOf(listOf("h", "e", "l", "l", "o")), emitted)
        assertEquals("", field.text?.toString())
    }

    @Test
    fun composingBackspaceEditsCandidateAndBlankBackspaceTargetsTerminal() {
        val field = DirectTypingEditText(ApplicationProvider.getApplicationContext())
        val emitted = mutableListOf<List<String>>()
        field.onDirectKeys = emitted::add
        field.beginDirectTyping()
        val input = field.onCreateInputConnection(EditorInfo())!!

        input.setComposingText("hi", 1)
        input.deleteSurroundingTextInCodePoints(1, 0)
        assertTrue(emitted.isEmpty())
        input.finishComposingText()
        field.text?.clear()
        input.deleteSurroundingText(1, 0)

        assertEquals(listOf(listOf("Backspace")), emitted)
    }

    @Test
    fun editorAndHardwareEnterEmitOnceAndDisarmDropsTransientText() {
        val field = DirectTypingEditText(ApplicationProvider.getApplicationContext())
        val emitted = mutableListOf<List<String>>()
        field.onDirectKeys = emitted::add
        field.beginDirectTyping()
        val input = field.onCreateInputConnection(EditorInfo())!!

        input.performEditorAction(EditorInfo.IME_ACTION_SEND)
        input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        input.setComposingText("unfinished", 1)
        field.endDirectTyping()

        assertEquals(listOf(listOf("Enter"), listOf("Enter")), emitted)
        assertEquals("", field.text?.toString())
    }

    @Test
    fun physicalEscapeTabArrowsBackspaceAndEnterUseTerminalWireNames() {
        val field = DirectTypingEditText(ApplicationProvider.getApplicationContext())
        val emitted = mutableListOf<List<String>>()
        field.onDirectKeys = emitted::add
        field.beginDirectTyping()

        listOf(
            KeyEvent.KEYCODE_ESCAPE to "Escape",
            KeyEvent.KEYCODE_TAB to "Tab",
            KeyEvent.KEYCODE_DPAD_UP to "Up",
            KeyEvent.KEYCODE_DPAD_DOWN to "Down",
            KeyEvent.KEYCODE_DPAD_LEFT to "Left",
            KeyEvent.KEYCODE_DPAD_RIGHT to "Right",
            KeyEvent.KEYCODE_DEL to "Backspace",
            KeyEvent.KEYCODE_ENTER to "Enter",
        ).forEach { (keyCode, _) ->
            assertTrue(field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)))
            assertTrue(field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)))
        }

        assertEquals(
            listOf("Escape", "Tab", "Up", "Down", "Left", "Right", "Backspace", "Enter")
                .map(::listOf),
            emitted,
        )
        assertEquals("", field.text?.toString())
    }

    @Test
    fun physicalPrintableKeysReachTheTerminalInsteadOfAccumulatingInTheLocalField() {
        val field = DirectTypingEditText(ApplicationProvider.getApplicationContext())
        val emitted = mutableListOf<List<String>>()
        field.onDirectKeys = emitted::add
        field.beginDirectTyping()

        assertTrue(field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H)))
        assertTrue(field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_H)))
        assertTrue(field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)))
        assertTrue(field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)))

        assertEquals(listOf(listOf("h"), listOf("Space")), emitted)
        assertEquals("", field.text?.toString())
    }

    @Test
    fun modifiedKeysUseTheSameGrammarThroughHardwareAndImeEvents() {
        val field = DirectTypingEditText(ApplicationProvider.getApplicationContext())
        val emitted = mutableListOf<List<String>>()
        field.onDirectKeys = emitted::add
        field.beginDirectTyping()
        val input = field.onCreateInputConnection(EditorInfo())!!
        val cases = listOf(
            Triple(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON, "ctrl+a"),
            Triple(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON, "shift+Tab"),
            Triple(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_ALT_ON, "alt+Left"),
            Triple(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, "ctrl+shift+Enter"),
            Triple(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, "A"),
            Triple(KeyEvent.KEYCODE_MOVE_HOME, 0, "Home"),
            Triple(KeyEvent.KEYCODE_F12, 0, "F12"),
        )
        cases.forEach { (code, meta, _) ->
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                field.dispatchKeyEvent(KeyEvent(0, 0, action, code, 0, meta))
            }
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                input.sendKeyEvent(KeyEvent(0, 0, action, code, 0, meta))
            }
        }
        assertEquals(cases.flatMap { listOf(listOf(it.third), listOf(it.third)) }, emitted)
        assertEquals("", field.text?.toString())
    }

    @Test
    fun ctrlOrCommandEnterRequestsOneNormalReplyWithoutEatingOtherEnterVariants() {
        val field = DirectTypingEditText(ApplicationProvider.getApplicationContext())
        var sends = 0
        field.onSendShortcut = { sends += 1 }

        fun enter(action: Int, repeat: Int = 0, modifiers: Int = 0): KeyEvent =
            KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_ENTER, repeat, modifiers)

        assertTrue(field.dispatchKeyEvent(enter(KeyEvent.ACTION_DOWN, modifiers = KeyEvent.META_CTRL_ON)))
        assertTrue(field.dispatchKeyEvent(enter(KeyEvent.ACTION_DOWN, repeat = 1, modifiers = KeyEvent.META_CTRL_ON)))
        assertTrue(field.dispatchKeyEvent(enter(KeyEvent.ACTION_UP, modifiers = KeyEvent.META_CTRL_ON)))
        assertEquals(1, sends)

        assertTrue(field.dispatchKeyEvent(enter(KeyEvent.ACTION_DOWN, modifiers = KeyEvent.META_META_ON)))
        assertTrue(field.dispatchKeyEvent(enter(KeyEvent.ACTION_UP, modifiers = KeyEvent.META_META_ON)))
        assertEquals(2, sends)

        field.dispatchKeyEvent(enter(KeyEvent.ACTION_DOWN))
        field.dispatchKeyEvent(enter(KeyEvent.ACTION_UP))
        field.dispatchKeyEvent(enter(KeyEvent.ACTION_DOWN, modifiers = KeyEvent.META_SHIFT_ON))
        field.dispatchKeyEvent(enter(KeyEvent.ACTION_UP, modifiers = KeyEvent.META_SHIFT_ON))
        assertEquals(2, sends)
        assertFalse(field.directTypingEnabled)
    }
}
