package com.lateapex.collie.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.lateapex.collie.R

/** Builds the device-local cards in the same compact, flat stack as the web Settings route. */
internal class SettingsLocalPreferences(
    private val activity: AppCompatActivity,
    private val preferences: NativePreferences,
) {
    private var handsFreeCard: View? = null

    fun bindDisplay(parent: LinearLayout) {
        parent.removeAllViews()
        parent.addView(typefaceCard().apply { id = R.id.settings_parity_typeface_card })
        parent.addView(terminalCard().apply { id = R.id.settings_parity_terminal_font_card })
    }

    fun bindBehavior(parent: LinearLayout) {
        parent.removeAllViews()
        handsFreeCard = switchCard(
            title = text(R.string.settings_hands_free_title),
            description = text(R.string.settings_hands_free_description),
            id = R.id.settings_hands_free_switch,
            checked = preferences.handsFreeEnabled,
            iconRes = R.drawable.ic_pane_mic,
        ) { preferences.handsFreeEnabled = it }.apply {
            id = R.id.settings_parity_hands_free_card
            visibility = View.GONE
        }
        handsFreeCard?.let(parent::addView)
        parent.addView(switchCard(
            title = text(R.string.settings_zen_title),
            description = text(R.string.settings_zen_description),
            id = R.id.settings_zen_switch,
            checked = preferences.zenAvailable,
            iconRes = R.drawable.ic_collie_layers,
        ) { preferences.zenAvailable = it }.apply { id = R.id.settings_parity_zen_card })
    }

    fun setHandsFreeCapability(available: Boolean) {
        handsFreeCard?.visibility = if (available) View.VISIBLE else View.GONE
    }

    private fun terminalCard(): View {
        val card = card()
        card.addView(header(
            text(R.string.settings_terminal_font_title),
            text(R.string.settings_terminal_font_description),
            R.drawable.ic_composer_terminal,
        ))
        card.addView(divider())
        val family = horizontalRow(text(R.string.settings_font_family))
        val sample = TextView(activity).apply {
            id = R.id.settings_terminal_sample
            setText(R.string.settings_terminal_sample)
            textSize = preferences.terminalFontSize.toFloat()
            setTextColor(color(R.color.collie_terminal_foreground))
            setBackgroundColor(color(R.color.collie_terminal))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            isSingleLine = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            typeface = Typeface.create(preferences.terminalFont.androidFamily, Typeface.NORMAL)
        }
        val fontControl = choiceButton(
            R.id.settings_terminal_font_spinner,
            text(R.string.settings_terminal_font_title),
            NativePreferences.TerminalFont.entries.map { text(it.nameRes) },
            NativePreferences.TerminalFont.entries.indexOf(preferences.terminalFont),
        ) { index ->
            preferences.terminalFont = NativePreferences.TerminalFont.entries[index]
            sample.typeface = Typeface.create(preferences.terminalFont.androidFamily, Typeface.NORMAL)
        }
        family.addView(fontControl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        card.addView(family)
        card.addView(divider())
        card.addView(stepperRow(
            label = text(R.string.settings_terminal_size),
            valueId = R.id.settings_terminal_size_value,
            decreaseId = R.id.settings_terminal_size_decrease,
            increaseId = R.id.settings_terminal_size_increase,
            minimum = 9,
            maximum = 16,
            read = { preferences.terminalFontSize },
            write = { preferences.terminalFontSize = it },
            onRendered = { sample.textSize = it.toFloat() },
        ))
        card.addView(divider())
        card.addView(stepperRow(
            label = text(R.string.settings_draft_size),
            valueId = R.id.settings_draft_size_value,
            decreaseId = R.id.settings_draft_size_decrease,
            increaseId = R.id.settings_draft_size_increase,
            minimum = 13,
            maximum = 16,
            read = { preferences.draftFontSize },
            write = { preferences.draftFontSize = it },
        ))
        card.addView(divider())
        card.addView(sample, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        return card
    }

    private fun typefaceCard(): View = card().apply {
        val selected = preferences.appTypeface
        addView(header(
            text(R.string.settings_typeface_title),
            text(R.string.settings_typeface_description),
            R.drawable.ic_composer_agent,
        ))
        addView(divider())
        addView(horizontalRow(text(R.string.settings_font_family)).apply {
            addView(choiceButton(
                R.id.settings_typeface_spinner,
                text(R.string.settings_typeface_title),
                NativePreferences.AppTypeface.entries.map { text(it.nameRes) },
                NativePreferences.AppTypeface.entries.indexOf(selected),
            ) { index ->
                val next = NativePreferences.AppTypeface.entries[index]
                if (next != preferences.appTypeface) {
                    preferences.appTypeface = next
                    activity.recreate()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        })
        addView(divider())
        addView(TextView(activity).apply {
            id = R.id.settings_typeface_note
            setText(when (selected) {
                NativePreferences.AppTypeface.SYSTEM -> R.string.settings_typeface_note_system
                NativePreferences.AppTypeface.SPACE_GROTESK -> R.string.settings_typeface_note_space_grotesk
                NativePreferences.AppTypeface.ALDRICH -> R.string.settings_typeface_note_aldrich
            })
            textSize = 12f
            setTextColor(color(R.color.collie_muted))
            setPadding(dp(16), dp(10), dp(16), dp(10))
        })
    }

    private fun switchCard(
        title: String,
        description: String,
        @IdRes id: Int,
        checked: Boolean,
        iconRes: Int,
        onChange: (Boolean) -> Unit,
    ): View = card().apply {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(0, 0, dp(12), 0)
        }
        row.addView(header(title, description, iconRes), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialSwitch(activity).apply {
            this.id = id
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        addView(row)
    }

    private fun stepperRow(
        label: String,
        @IdRes valueId: Int,
        @IdRes decreaseId: Int,
        @IdRes increaseId: Int,
        minimum: Int,
        maximum: Int,
        read: () -> Int,
        write: (Int) -> Unit,
        onRendered: (Int) -> Unit = {},
    ): View {
        val row = horizontalRow(label)
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = TextView(activity).apply {
            id = valueId
            width = dp(40)
            gravity = Gravity.CENTER
            setTextColor(color(R.color.collie_muted))
            typeface = Typeface.MONOSPACE
        }
        lateinit var minus: MaterialButton
        lateinit var plus: MaterialButton
        fun render() {
            val current = read()
            value.text = current.toString()
            minus.isEnabled = current > minimum
            plus.isEnabled = current < maximum
            onRendered(current)
        }
        minus = smallButton(decreaseId, text(R.string.symbol_minus), text(R.string.settings_decrease, label)) {
            write((read() - 1).coerceAtLeast(minimum))
            render()
        }
        plus = smallButton(increaseId, text(R.string.symbol_plus), text(R.string.settings_increase, label)) {
            write((read() + 1).coerceAtMost(maximum))
            render()
        }
        controls.addView(minus)
        controls.addView(value)
        controls.addView(plus)
        row.addView(controls)
        render()
        return row
    }

    private fun choiceButton(
        @IdRes id: Int,
        title: String,
        entries: List<String>,
        selected: Int,
        onSelected: (Int) -> Unit,
    ): MaterialButton {
        var current = selected
        val button = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.id = id
            text = entries.getOrNull(selected).orEmpty()
            isAllCaps = false
        }
        button.setOnClickListener {
            val dialog = CollieBottomSheetDialog(activity, title)
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            entries.forEachIndexed { index, entry ->
                list.addView(MaterialButton(activity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = if (index == current) text(R.string.settings_parity_selected_choice, entry) else entry
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    isAllCaps = false
                    minHeight = dp(44)
                    setOnClickListener {
                        button.text = entry
                        current = index
                        dialog.dismiss()
                        onSelected(index)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            }
            dialog.setSheetContent(list)
            dialog.show()
        }
        return button
    }

    private fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_pane_card)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.resources.getDimensionPixelSize(R.dimen.collie_card_gap)
        }
    }

    private fun header(title: String, description: String, iconRes: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(AppCompatImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(color(R.color.collie_muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(CollieHeadingTextView(activity).apply {
                text = title
                textSize = 16f
                setTextColor(color(R.color.collie_foreground))
            })
            addView(TextView(activity).apply {
                text = description
                textSize = 14f
                setTextColor(color(R.color.collie_muted))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun horizontalRow(label: String?) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        if (label != null) addView(TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(color(R.color.collie_foreground))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun smallButton(@IdRes id: Int, label: String, description: String, action: () -> Unit) =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.id = id
            text = label
            contentDescription = description
            minWidth = dp(44)
            minimumWidth = dp(44)
            minHeight = dp(44)
            setOnClickListener { action() }
        }

    private fun divider() = View(activity).apply {
        setBackgroundColor(color(R.color.collie_border))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun text(@androidx.annotation.StringRes resource: Int, vararg args: Any): String =
        activity.getString(resource, *args)
}
