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
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.lateapex.collie.R
import com.lateapex.collie.network.OperatorFontRow

/** Builds the device-local cards in the same compact, flat stack as the web Settings route. */
internal class SettingsLocalPreferences(
    private val activity: AppCompatActivity,
    private val preferences: NativePreferences,
) {
    private var handsFreeCard: View? = null
    private var typefaceButton: MaterialButton? = null
    private var typefaceStatus: TextView? = null
    private var operatorFonts: List<OperatorFontRow> = emptyList()
    private var operatorFontOwner: String? = null
    private var operatorFontLoading = false
    private var operatorFontError: String? = null
    private var onOperatorTypefaceSelected: (OperatorFontRow) -> Unit = {}
    private var onTypefaceSelectionInvalidated: () -> Unit = {}

    fun bindDisplay(parent: LinearLayout) {
        parent.removeAllViews()
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
        parent.addView(
            (switchCard(
                title = text(R.string.settings_diagnostics_title),
                description = text(R.string.settings_diagnostics_description),
                id = R.id.settings_diagnostics_switch,
                checked = preferences.diagnosticsEnabled,
                iconRes = R.drawable.ic_history_wrench,
            ) { preferences.diagnosticsEnabled = it } as LinearLayout).apply {
                id = R.id.settings_diagnostics_card
                addView(divider())
                addView(
                    MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        id = R.id.settings_diagnostics_send_button
                        text = text(R.string.settings_diagnostics_send_title)
                        isAllCaps = false
                        minHeight = dp(48)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        val margin = dp(16)
                        leftMargin = margin
                        rightMargin = margin
                        bottomMargin = dp(14)
                    },
                )
            },
        )
    }

    fun setHandsFreeCapability(available: Boolean) {
        handsFreeCard?.visibility = if (available) View.VISIBLE else View.GONE
    }

    fun setOnOperatorTypefaceSelected(listener: (OperatorFontRow) -> Unit) {
        onOperatorTypefaceSelected = listener
    }

    fun setOnTypefaceSelectionInvalidated(listener: () -> Unit) {
        onTypefaceSelectionInvalidated = listener
    }

    fun updateOperatorFonts(rows: List<OperatorFontRow>, ownerFingerprint: String?, loading: Boolean, error: String? = null) {
        operatorFonts = rows.filter(OperatorFontRow::valid).distinctBy { it.basename }
        operatorFontOwner = ownerFingerprint
        operatorFontLoading = loading
        operatorFontError = error
        renderTypefaceSelection()
    }

    fun operatorFontLoading(family: String) {
        operatorFontLoading = true
        operatorFontError = null
        typefaceStatus?.text = text(R.string.settings_ui_typeface_loading, family)
        typefaceStatus?.isVisible = true
    }

    fun operatorFontLoaded() {
        operatorFontLoading = false
        operatorFontError = null
        renderTypefaceSelection()
    }

    fun operatorFontFailed(message: String) {
        operatorFontLoading = false
        operatorFontError = message
        renderTypefaceSelection()
    }

    fun operatorFontUnavailable() {
        operatorFontLoading = false
        operatorFontError = text(R.string.settings_ui_typeface_unavailable)
        renderTypefaceSelection()
    }

    private fun terminalCard(): View {
        val card = card()
        card.addView(header(
            text(R.string.settings_terminal_font_title),
            text(R.string.settings_terminal_font_description),
            R.drawable.ic_composer_terminal,
        ))
        card.addView(divider())
        card.addView(horizontalRow(text(R.string.settings_ui_typeface)).apply {
            addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = R.id.settings_ui_typeface_value
                isAllCaps = false
                setOnClickListener { showTypefaceChoices() }
                typefaceButton = this
            })
        })
        typefaceStatus = TextView(activity).apply {
            textSize = 12f
            setTextColor(color(R.color.collie_muted))
            setPadding(dp(16), dp(2), dp(16), dp(8))
            isVisible = false
        }.also(card::addView)
        renderTypefaceSelection()
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
        return card
    }

    private fun showTypefaceChoices() {
        val selectedOperator = preferences.operatorTypefaceSelection?.takeIf { selected ->
            selected.ownerFingerprint == operatorFontOwner && operatorFonts.any { it.family == selected.family && it.basename == selected.basename }
        }
        val selectedLabel = selectedOperator?.family ?: shippedTypefaceLabel(preferences.uiTypeface)
        val sheet = CollieBottomSheetDialog(activity, text(R.string.settings_ui_typeface))
        val entries = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun entry(label: String, selected: Boolean, onClick: () -> Unit) {
            entries.addView(MaterialButton(activity, null, android.R.attr.borderlessButtonStyle).apply {
                text = if (selected) text(R.string.settings_parity_selected_choice, label) else label
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener {
                    onClick()
                    sheet.dismiss()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
        entry(text(R.string.settings_ui_typeface_system), selectedLabel == text(R.string.settings_ui_typeface_system)) {
            selectShippedTypeface(NativePreferences.UiTypeface.SYSTEM)
        }
        entry(text(R.string.settings_ui_typeface_grotesk), selectedLabel == text(R.string.settings_ui_typeface_grotesk)) {
            selectShippedTypeface(NativePreferences.UiTypeface.GROTESK)
        }
        entry(text(R.string.settings_ui_typeface_aldrich), selectedLabel == text(R.string.settings_ui_typeface_aldrich)) {
            selectShippedTypeface(NativePreferences.UiTypeface.ALDRICH)
        }
        operatorFonts.forEach { row ->
            entry(row.family, selectedOperator?.let { it.family == row.family && it.basename == row.basename } == true) {
                typefaceStatus?.text = text(R.string.settings_ui_typeface_loading, row.family)
                typefaceStatus?.isVisible = true
                onOperatorTypefaceSelected(row)
            }
        }
        sheet.setSheetContent(entries)
        sheet.show()
    }

    private fun selectShippedTypeface(value: NativePreferences.UiTypeface) {
        onTypefaceSelectionInvalidated()
        preferences.clearOperatorTypeface()
        PreferredTypeface.clearLoadedOperator()
        preferences.uiTypeface = value
        activity.window.decorView.applyAppTypeface()
        operatorFontLoading = false
        operatorFontError = null
        renderTypefaceSelection()
    }

    private fun renderTypefaceSelection() {
        val selection = preferences.operatorTypefaceSelection?.takeIf { selected ->
            selected.ownerFingerprint == operatorFontOwner && operatorFonts.any { it.family == selected.family && it.basename == selected.basename }
        }
        typefaceButton?.text = selection?.family ?: shippedTypefaceLabel(preferences.uiTypeface)
        val status = when {
            operatorFontError != null -> operatorFontError
            operatorFontLoading -> text(R.string.settings_ui_typeface_fetching)
            else -> null
        }
        typefaceStatus?.text = status
        typefaceStatus?.isVisible = status != null
    }

    private fun shippedTypefaceLabel(value: NativePreferences.UiTypeface): String = when (value) {
        NativePreferences.UiTypeface.SYSTEM -> text(R.string.settings_ui_typeface_system)
        NativePreferences.UiTypeface.GROTESK -> text(R.string.settings_ui_typeface_grotesk)
        NativePreferences.UiTypeface.ALDRICH -> text(R.string.settings_ui_typeface_aldrich)
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
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
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
                    minHeight = dp(48)
                    setOnClickListener {
                        button.text = entry
                        current = index
                        dialog.dismiss()
                        onSelected(index)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
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
            minWidth = dp(48)
            minimumWidth = dp(48)
            minHeight = dp(48)
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
