package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.lateapex.collie.R
import com.lateapex.collie.network.ChatPrompt

/** Shows the operator prompt carried by the live chat snapshot, even when its turn is off-page. */
internal class ChatPromptContextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val promptText = TextView(context).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = 15f
        setTextIsSelectable(true)
        setTag(R.id.collie_content_typeface, true)
        maxLines = COLLAPSED_LINES
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val shortened = TextView(context).apply {
        setText(R.string.chat_prompt_shortened)
        textSize = 12f
        isVisible = false
    }
    private val toggle = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        isAllCaps = false
        isVisible = false
    }
    private var currentPrompt: ChatPrompt? = null
    private var expanded = false

    init {
        radius = resources.getDimension(R.dimen.durable_chat_prompt_radius)
        strokeWidth = resources.getDimensionPixelSize(R.dimen.durable_chat_prompt_stroke)
        setStrokeColor(context.getColorStateList(R.color.chat_prompt_stroke))
        cardElevation = 0f
        setContentPadding(
            resources.getDimensionPixelSize(R.dimen.chat_prompt_padding_horizontal),
            resources.getDimensionPixelSize(R.dimen.chat_prompt_padding_vertical),
            resources.getDimensionPixelSize(R.dimen.chat_prompt_padding_horizontal),
            resources.getDimensionPixelSize(R.dimen.chat_prompt_padding_vertical),
        )
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(TextView(context).apply {
                setText(R.string.chat_prompt_heading)
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(promptText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(shortened, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        toggle.setOnClickListener {
            expanded = !expanded
            render()
        }
        contentDescription = context.getString(R.string.chat_prompt_heading)
        visibility = GONE
    }

    /** Null means the server has no known prompt for this snapshot. Text is kept byte-for-byte. */
    fun showPrompt(prompt: ChatPrompt?) {
        if (prompt == null) {
            currentPrompt = null
            expanded = false
        } else if (currentPrompt?.uuid != prompt.uuid) {
            expanded = false
        }
        currentPrompt = prompt
        render()
    }

    private fun render() {
        val prompt = currentPrompt
        isVisible = prompt != null
        if (prompt == null) return
        promptText.text = prompt.text
        promptText.maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES
        promptText.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
        shortened.isVisible = prompt.truncated == true
        toggle.isVisible = prompt.text.length > 80 || prompt.text.lineSequence().count() > COLLAPSED_LINES || prompt.truncated == true
        toggle.setText(if (expanded) R.string.chat_prompt_collapse else R.string.chat_prompt_expand)
        toggle.contentDescription = context.getString(
            if (expanded) R.string.chat_prompt_collapse else R.string.chat_prompt_expand,
        )
    }

    private companion object {
        const val COLLAPSED_LINES = 2
    }
}
