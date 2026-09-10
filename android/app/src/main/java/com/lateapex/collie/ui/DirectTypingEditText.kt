package com.lateapex.collie.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * Keeps Android IME composition local, then emits only committed text while explicit Type mode is
 * armed. Backspace and Enter are named terminal keys even while the field itself remains empty.
 */
class DirectTypingEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {
    var directTypingEnabled: Boolean = false
        private set
    var onDirectKeys: ((List<String>) -> Unit)? = null
    var onSendShortcut: (() -> Unit)? = null
    private var sendShortcutKeyDown = false

    fun beginDirectTyping() {
        sendShortcutKeyDown = false
        text?.let(BaseInputConnection::removeComposingSpans)
        text?.clear()
        directTypingEnabled = true
    }

    fun endDirectTyping() {
        sendShortcutKeyDown = false
        directTypingEnabled = false
        text?.let(BaseInputConnection::removeComposingSpans)
        text?.clear()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!directTypingEnabled) return super.commitText(text, newCursorPosition)
                val committed = text?.toString().orEmpty()
                if (committed.isNotEmpty()) onDirectKeys?.invoke(textToKeys(committed))
                this@DirectTypingEditText.text?.let(BaseInputConnection::removeComposingSpans)
                this@DirectTypingEditText.text?.clear()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!directTypingEnabled) return super.deleteSurroundingText(beforeLength, afterLength)
                val editable = this@DirectTypingEditText.text
                if (editable != null && BaseInputConnection.getComposingSpanStart(editable) >= 0) {
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }
                if (beforeLength > 0) onDirectKeys?.invoke(List(beforeLength) { "Backspace" })
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (!directTypingEnabled) return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                val editable = this@DirectTypingEditText.text
                if (editable != null && BaseInputConnection.getComposingSpanStart(editable) >= 0) {
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }
                if (beforeLength > 0) onDirectKeys?.invoke(List(beforeLength) { "Backspace" })
                return true
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                if (!directTypingEnabled) return super.performEditorAction(editorAction)
                if (editorAction !in TERMINAL_EDITOR_ACTIONS) return super.performEditorAction(editorAction)
                onDirectKeys?.invoke(listOf("Enter"))
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (!directTypingEnabled) return super.sendKeyEvent(event)
                val key = terminalKey(event) ?: return super.sendKeyEvent(event)
                if (event.action == KeyEvent.ACTION_DOWN) onDirectKeys?.invoke(listOf(key))
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (directTypingEnabled) {
            terminalKey(event)?.let { key ->
                onDirectKeys?.invoke(listOf(key))
                return true
            }
        } else if (keyCode == KeyEvent.KEYCODE_ENTER && event.hasSendModifier() && onSendShortcut != null) {
            sendShortcutKeyDown = true
            if (event.repeatCount == 0) onSendShortcut?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (directTypingEnabled && terminalKey(event) != null) return true
        if (keyCode == KeyEvent.KEYCODE_ENTER && sendShortcutKeyDown) {
            sendShortcutKeyDown = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private val TERMINAL_EDITOR_ACTIONS = setOf(
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_UNSPECIFIED,
        )

        internal fun textToKeys(value: String): List<String> = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .codePoints()
            .toArray()
            .map {
                when (it) {
                    ' '.code -> "Space"
                    '\t'.code -> "Tab"
                    '\n'.code -> "Enter"
                    else -> String(Character.toChars(it))
                }
            }

        // Ctrl/Alt/Meta can turn unicodeChar into a control code or zero. Resolve the
        // base through this event's keyboard layout, then carry the modifiers on the wire.
        private fun terminalKey(event: KeyEvent): String? {
            val named = physicalTerminalKey(event.keyCode)
            val chord = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
            val codePoint = event.getUnicodeChar(if (chord) 0 else event.metaState)
            val base = named ?: if (codePoint in 0x20..Character.MAX_CODE_POINT) {
                textToKeys(String(Character.toChars(codePoint))).single()
            } else return null
            val modifiers = buildList {
                if (event.isCtrlPressed) add("ctrl")
                if (event.isAltPressed) add("alt")
                if (event.isShiftPressed && (named != null || chord)) add("shift")
                if (event.isMetaPressed) add("meta")
            }
            return (modifiers + base).joinToString("+")
        }

        private fun physicalTerminalKey(keyCode: Int): String? = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            KeyEvent.KEYCODE_INSERT -> "Insert"
            KeyEvent.KEYCODE_MOVE_HOME -> "Home"
            KeyEvent.KEYCODE_MOVE_END -> "End"
            KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
            KeyEvent.KEYCODE_ESCAPE -> "Escape"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_DPAD_UP -> "Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
            else -> null
        }

        private fun KeyEvent.hasSendModifier(): Boolean = isCtrlPressed || isMetaPressed
    }
}
