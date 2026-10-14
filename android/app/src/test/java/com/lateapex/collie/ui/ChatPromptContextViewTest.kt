package com.lateapex.collie.ui

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.R
import com.lateapex.collie.network.ChatPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
class ChatPromptContextViewTest {
    @Test
    fun rendersServerTextFaithfullyAndAllowsExpansionOfShortenedPrompt() {
        val context = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val view = ChatPromptContextView(context)
        val source = "First line 😀\nSecond line e\u0301\nthird line"

        view.showPrompt(ChatPrompt(uuid = "prompt-1", ts = "2026-10-13T12:00:00Z", text = source, truncated = true))

        val prompt = findDescendant(view) { it is TextView && it.text.toString() == source } as TextView
        assertEquals(source, prompt.text.toString())
        assertEquals(2, prompt.maxLines)
        assertTrue(view.isVisible)

        val button = findButton(view)
        assertTrue(button.isShown || button.visibility == View.VISIBLE)
        assertEquals(context.getString(R.string.chat_prompt_expand), button.text.toString())
        button.performClick()
        assertEquals(Int.MAX_VALUE, prompt.maxLines)
        assertEquals(context.getString(R.string.chat_prompt_collapse), button.text.toString())
        assertTrue(findShortenedNotice(view).isVisible)
        assertFalse(prompt.text.toString().contains('\uFFFD'))
    }

    @Test
    fun missingPromptHidesContextAndResetsExpansion() {
        val context = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val view = ChatPromptContextView(context)
        view.showPrompt(ChatPrompt("p", "ts", "one\ntwo\nthree"))
        findButton(view).performClick()

        view.showPrompt(null)

        assertEquals(View.GONE, view.visibility)
        view.showPrompt(ChatPrompt("p2", "ts", "replacement"))
        assertEquals(2, findPromptText(view).maxLines)
    }

    private fun findButton(view: ChatPromptContextView): MaterialButton =
        findDescendant(view) { it is MaterialButton } as MaterialButton

    private fun findShortenedNotice(view: ChatPromptContextView): TextView =
        findDescendant(view) { it is TextView && it.text == view.context.getString(R.string.chat_prompt_shortened) } as TextView

    private fun findPromptText(view: ChatPromptContextView): TextView =
        findDescendant(view) { it is TextView && it.text.toString().contains("replacement") } as TextView

    private fun findDescendant(root: android.view.ViewGroup, predicate: (View) -> Boolean): View {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (predicate(child)) return child
            if (child is android.view.ViewGroup) runCatching { findDescendant(child, predicate) }.getOrNull()?.let { return it }
        }
        error("Matching view was not found")
    }
}
