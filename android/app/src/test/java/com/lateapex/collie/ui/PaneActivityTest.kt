package com.lateapex.collie.ui

import android.content.Intent
import android.os.Bundle
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.URLSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.time.Duration
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.R
import com.lateapex.collie.network.PaneReadResponse
import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.PaneSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class PaneActivityTest {
    @Test
    fun paneOverlayActionsKeepTheSharedTouchTargetFloor() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("touch:pane")).create().get()
        val floor = activity.resources.getDimensionPixelSize(R.dimen.collie_touch_target)

        listOf(
            activity.findViewById<View>(R.id.show_tabs_button),
            activity.findViewById<View>(R.id.pane_buffer_action),
            activity.findViewById<View>(R.id.new_output_button),
        ).forEach { action ->
            assertTrue(action.minimumHeight >= floor || action.layoutParams.height >= floor)
        }
    }

    @Test
    fun repeatedKeysCommitOnlyOnAnEngagedActionUp() {
        assertTrue(PaneActivity.shouldCommitRepeatKeys(true, MotionEvent.ACTION_UP))
        assertFalse(PaneActivity.shouldCommitRepeatKeys(true, MotionEvent.ACTION_CANCEL))
        assertFalse(PaneActivity.shouldCommitRepeatKeys(false, MotionEvent.ACTION_UP))
    }

    @Test
    fun paneActionsUseCanonicalPullUpSheetAndKeepReadOnlySafeActionsUsable() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("actions:pane")).create().get()
        render(
            activity,
            PaneUiState(
                loading = false,
                panes = listOf(paneSummary("actions:pane").copy(hasSession = true)),
                mux = MuxConfigResponse(capabilities = mapOf("agentSessionRef" to true)),
            ),
        )

        activity.findViewById<View>(R.id.refresh_button).performClick()

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog is CollieBottomSheetDialog)
        assertEquals(
            activity.getString(R.string.pane_actions),
            dialog.findViewById<TextView>(R.id.collie_sheet_title).text.toString(),
        )
        val rows = descendants(dialog.findViewById(R.id.collie_sheet_content))
            .filterIsInstance<TextView>()
            .associateBy { it.text.toString() }
        assertTrue(rows.getValue(activity.getString(R.string.pane_action_find)).isEnabled)
        assertTrue(rows.getValue(activity.getString(R.string.pane_action_history)).isEnabled)
        assertFalse(rows.containsKey("Refresh"))
        assertFalse(rows.getValue(activity.getString(R.string.pane_action_read_only)).isEnabled)
    }

    @Test
    fun currentPaneRenameStaysInTheSameSheetAndCloseArmsInPlace() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("current:pane")).create().get()
        render(
            activity,
            PaneUiState(
                loading = false,
                canWrite = true,
                panes = listOf(paneSummary("current:pane").copy(paneLabel = "Current label")),
                mux = MuxConfigResponse(
                    capabilities = mapOf("renamePane" to true, "closePane" to true),
                ),
            ),
        )
        activity.findViewById<View>(R.id.refresh_button).performClick()
        val dialog = ShadowDialog.getLatestDialog() as CollieBottomSheetDialog

        sheetButton(dialog, activity.getString(R.string.pane_action_rename)).performClick()

        assertSame(dialog, ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)
        assertEquals(
            "Current label",
            descendants(requireNotNull(dialog.findViewById<View>(R.id.collie_sheet_content)))
                .filterIsInstance<EditText>()
                .single()
                .text
                .toString(),
        )

        sheetButton(dialog, activity.getString(R.string.pane_action_cancel)).performClick()
        val close = sheetButton(dialog, activity.getString(R.string.pane_action_close))
        close.performClick()

        assertSame(dialog, ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.pane_close_again), close.text.toString())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun topOfBufferShowsHistoryOnlyForAVerifiedSessionAndOpensIt() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("history:pane")).create().get()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("history:pane", "output", false, 1),
                loading = false,
                panes = listOf(paneSummary("history:pane").copy(hasSession = true, readableLines = 4_000)),
                mux = MuxConfigResponse(
                    capabilities = mapOf("agentSessionRef" to true, "gridScrollback" to true),
                ),
                loadedLines = 600,
            ),
        )

        val action = activity.findViewById<TextView>(R.id.pane_buffer_action)
        assertEquals(View.VISIBLE, action.visibility)
        assertEquals(activity.getString(R.string.pane_scrollback_show_history), action.text.toString())

        action.performClick()

        assertEquals(
            HistoryActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun topOfBufferKeepsLoadOlderWhenTranscriptCapabilityIsUnavailableAndExplainsWhy() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("scroll:pane")).create().get()
        val note = "This multiplexer keeps no agent session log for Collie to read."
        val state = PaneUiState(
                pane = PaneReadResponse("scroll:pane", "output", false, 1),
                loading = false,
                panes = listOf(
                    paneSummary("scroll:pane").copy(hasSession = false, readableLines = 3_000),
                ),
                mux = MuxConfigResponse(
                    capabilities = mapOf("agentSessionRef" to false, "gridScrollback" to true),
                    notes = mapOf("agentSessionRef" to note),
                ),
                requestedLines = 600,
                loadedLines = 600,
            )
        render(activity, state)

        assertEquals(
            activity.getString(R.string.pane_scrollback_load_older),
            activity.findViewById<TextView>(R.id.pane_buffer_action).text.toString(),
        )
        val explanation = activity.findViewById<TextView>(R.id.pane_buffer_explanation)
        assertEquals(View.VISIBLE, explanation.visibility)
        assertEquals(note, explanation.text.toString())

        render(activity, state.copy(requestedLines = 1_000, loadingOlder = true))
        val loading = activity.findViewById<TextView>(R.id.pane_buffer_action)
        assertEquals(View.VISIBLE, loading.visibility)
        assertEquals(activity.getString(R.string.pane_scrollback_loading), loading.text.toString())
        assertFalse(loading.isEnabled)
    }

    @Test
    fun journalAgentWithoutSessionShowsTheActionableExplanation() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("no-session:pane")).create().get()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("no-session:pane", "output", false, 1),
                loading = false,
                panes = listOf(paneSummary("no-session:pane").copy(agent = "opencode", hasSession = false)),
                mux = MuxConfigResponse(capabilities = mapOf("agentSessionRef" to true)),
            ),
        )

        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_buffer_action).visibility)
        val explanation = activity.findViewById<TextView>(R.id.pane_buffer_explanation)
        assertEquals(View.VISIBLE, explanation.visibility)
        assertTrue(explanation.text.toString().startsWith("opencode has not reported a session"))
    }

    @Test
    fun paneSwitcherAndAgentPaletteUseCanonicalPullUpSheets() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().get()
        val state = PaneUiState(
            loading = false,
            canWrite = true,
            composerReady = true,
            panes = listOf(paneSummary("w1:p1"), paneSummary("w1:p2")),
            commands = listOf(AgentCommand("/review", "Review changes", common = true)),
        )
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }

        activity.findViewById<View>(R.id.switcher_handle).performClick()
        assertTrue(ShadowDialog.getLatestDialog() is CollieBottomSheetDialog)
        ShadowDialog.getLatestDialog().dismiss()

        activity.findViewById<View>(R.id.agent_mode_button).performClick()
        assertTrue(ShadowDialog.getLatestDialog() is CollieBottomSheetDialog)
    }

    @Test
    fun composerControlsAreWiredAndToggleMutuallyExclusiveInFlowDocks() {
        val intent = paneIntent("w1:p1")
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()
        val keys = activity.findViewById<View>(R.id.keys_mode_button)
        val quick = activity.findViewById<View>(R.id.quick_mode_button)
        val agent = activity.findViewById<View>(R.id.agent_mode_button)
        val display = activity.findViewById<View>(R.id.composer_settings_button)
        val dock = activity.findViewById<View>(R.id.composer_dock)

        assertTrue(keys.hasOnClickListeners())
        assertTrue(quick.hasOnClickListeners())
        assertTrue(agent.hasOnClickListeners())
        assertTrue(display.hasOnClickListeners())
        assertEquals(View.GONE, dock.visibility)

        quick.performClick()
        assertEquals(View.VISIBLE, dock.visibility)
        val quickActions = activity.findViewById<ViewGroup>(R.id.quick_actions_container)
        assertEquals(View.VISIBLE, quickActions.visibility)
        assertTrue(quickActions.childCount > 0)

        display.performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.display_prefs_container).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.quick_actions_container).visibility)

        display.performClick()
        assertEquals(View.GONE, dock.visibility)
    }

    @Test
    fun keysDrawerUsesSegmentedPrimaryAndDigitPadsWithThumbSizedTargets() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-layout:pane")).create().get()

        activity.findViewById<View>(R.id.keys_mode_button).performClick()

        val keysTab = activity.findViewById<View>(R.id.pane_keys_segment_keys)
        val digitsTab = activity.findViewById<View>(R.id.pane_keys_segment_digits)
        val primary = activity.findViewById<View>(R.id.pane_keys_primary)
        val digits = activity.findViewById<GridLayout>(R.id.pane_keys_digits)
        assertTrue(keysTab.hasOnClickListeners())
        assertTrue(digitsTab.hasOnClickListeners())
        assertEquals(View.VISIBLE, primary.visibility)
        assertEquals(View.GONE, digits.visibility)
        assertEquals(9, digits.childCount)

        digitsTab.performClick()

        assertEquals(View.GONE, primary.visibility)
        assertEquals(View.VISIBLE, digits.visibility)
        assertTrue(
            descendants(activity.findViewById(R.id.key_pad_container))
                .filter { it.hasOnClickListeners() }
                .all { it.minimumHeight >= 44 || it.layoutParams?.height ?: 0 >= 44 },
        )
    }

    @Test
    fun switchingToDigitsPreservesTheModifierQueueAndClosingResetsToKeys() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-state:pane")).create().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        val modifiers = activity.findViewById<ViewGroup>(R.id.pane_keys_modifiers)
        val ctrl = descendants(modifiers).filterIsInstance<MaterialButton>()
            .single { it.text.toString() == activity.getString(R.string.pane_key_ctrl) }
        ctrl.performClick()

        activity.findViewById<View>(R.id.pane_keys_segment_digits).performClick()
        val one = descendants(activity.findViewById(R.id.pane_keys_digits))
            .filterIsInstance<MaterialButton>()
            .single { it.text.toString() == "1" }
        one.performClick()

        assertEquals(listOf("ctrl+1"), keyQueue(activity).staged)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_queue).visibility)

        // The first close guards the staged sequence; the second confirms its discard.
        activity.findViewById<View>(R.id.composer_dock_close).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.composer_dock).visibility)
        activity.findViewById<View>(R.id.composer_dock_close).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.composer_dock).visibility)
        activity.findViewById<View>(R.id.keys_mode_button).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_primary).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_keys_digits).visibility)
        assertTrue(keyQueue(activity).staged.isEmpty())
    }

    @Test
    fun presetsAndFunctionKeysStayInlineAndCollapsedUntilRequested() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-sections:pane")).create().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        val presets = activity.findViewById<GridLayout>(R.id.pane_keys_presets)
        val functions = activity.findViewById<GridLayout>(R.id.pane_keys_functions)

        assertEquals(View.GONE, presets.visibility)
        assertEquals(View.GONE, functions.visibility)
        assertEquals(12, functions.childCount)

        activity.findViewById<View>(R.id.pane_keys_presets_toggle).performClick()
        activity.findViewById<View>(R.id.pane_keys_functions_toggle).performClick()

        assertEquals(View.VISIBLE, presets.visibility)
        assertEquals(View.VISIBLE, functions.visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.composer_dock).visibility)
    }

    @Test
    fun everyKeyInBothSegmentsKeepsTheTerminalWriteGate() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-gate:pane")).create().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("keys-gate:pane", "output", false, 1),
                loading = false,
                canWrite = false,
                panes = listOf(paneSummary("keys-gate:pane")),
            ),
        )

        val wireButtons = listOf(
            activity.findViewById<ViewGroup>(R.id.pane_keys_primary),
            activity.findViewById<ViewGroup>(R.id.pane_keys_digits),
        ).flatMap { descendants(it).filterIsInstance<MaterialButton>().toList() }
            .filterNot {
                it.id == R.id.pane_keys_presets_toggle || it.id == R.id.pane_keys_functions_toggle
            }

        assertTrue(wireButtons.isNotEmpty())
        assertTrue(wireButtons.none { it.isEnabled })
        assertFalse(activity.findViewById<View>(R.id.keys_mode_button).isEnabled)
    }

    @Test
    fun modifierBaseInputStagesAChipThatCanBeRemovedWithoutSending() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-chip:pane")).create().get()
        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("keys-chip:pane", "prompt", false, 1),
                loading = false,
                canWrite = true,
                panes = listOf(paneSummary("keys-chip:pane")),
            ),
        )
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        val ctrl = descendants(activity.findViewById(R.id.pane_keys_modifiers))
            .filterIsInstance<MaterialButton>()
            .single { it.text.toString() == activity.getString(R.string.pane_key_ctrl) }

        ctrl.performClick()

        val baseInput = activity.findViewById<EditText>(R.id.pane_keys_base_input)
        assertTrue(baseInput.isEnabled)
        assertTrue(baseInput.minimumHeight >= 44 || baseInput.layoutParams.height >= 44)
        assertTrue(
            descendants(activity.findViewById(R.id.pane_keys_queue_chips))
                .filterIsInstance<TextView>()
                .any { it.text.toString().contains("Ctrl +") },
        )

        baseInput.setText("G")

        assertEquals(listOf("ctrl+g"), keyQueue(activity).staged)
        assertEquals(null, activity.findViewById<View>(R.id.pane_keys_base_input))
        val removeChip = descendants(activity.findViewById(R.id.pane_keys_queue_chips))
            .filterIsInstance<MaterialButton>()
            .single { it.contentDescription.toString() == "Remove Ctrl G" }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_queue).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_keys_queue_send).visibility)
        assertTrue(activity.findViewById<View>(R.id.pane_keys_queue_clear).hasOnClickListeners())

        removeChip.performClick()

        assertTrue(keyQueue(activity).staged.isEmpty())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_keys_queue).visibility)
    }

    @Test
    fun immediateKeyEchoOnlyShowsAcceptedForTheMatchingViewModelResult() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-echo:pane")).create().get()
        val button = activity.findViewById<MaterialButton>(R.id.escape_button)
        val state = PaneUiState(
            pane = PaneReadResponse("keys-echo:pane", "prompt", false, 1),
            loading = false,
            canWrite = true,
            panes = listOf(paneSummary("keys-echo:pane")),
        )
        render(activity, state)
        PaneActivity::class.java.getDeclaredMethod("beginKeyEcho", MaterialButton::class.java, List::class.java).apply {
            isAccessible = true
            invoke(activity, button, listOf("Escape"))
        }

        assertEquals("Esc …", button.text.toString())
        render(activity, state.copy(status = activity.getString(R.string.pane_keys_sent, "Tab")))
        assertEquals("Esc …", button.text.toString())

        render(activity, state.copy(status = activity.getString(R.string.pane_keys_sent, "Escape")))

        assertEquals(activity.getString(R.string.check_mark), button.text.toString())
        assertEquals("Esc accepted", button.contentDescription.toString())
    }

    @Test
    fun backClosesAnOpenComposerDrawerBeforeLeavingThePane() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().start().resume()
        val activity = controller.get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.composer_dock).visibility)

        activity.onBackPressedDispatcher.onBackPressed()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.composer_dock).visibility)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun stagedKeysRequireASecondBackBeforeTheDrawerCanDiscardThem() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-back:pane"))
            .create().start().resume()
        val activity = controller.get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        keyQueue(activity).apply {
            cycle(PaneKeyModifier.CTRL)
            press(listOf("x"))
        }

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.composer_dock).visibility)
        assertEquals(listOf("ctrl+x"), keyQueue(activity).staged)
        assertTrue(activity.findViewById<TextView>(R.id.error_text).text.toString().contains("Clear"))

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.composer_dock).visibility)
        assertTrue(keyQueue(activity).staged.isEmpty())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun stagedKeysRequireASecondTapBeforeSwitchingDrawers() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("keys-switch:pane")).create().get()
        activity.findViewById<View>(R.id.keys_mode_button).performClick()
        keyQueue(activity).apply {
            cycle(PaneKeyModifier.ALT)
            press(listOf("Enter"))
        }

        activity.findViewById<View>(R.id.quick_mode_button).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.key_row).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.quick_actions_container).visibility)

        activity.findViewById<View>(R.id.quick_mode_button).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.key_row).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.quick_actions_container).visibility)
        assertTrue(keyQueue(activity).staged.isEmpty())
    }

    @Test
    fun unsentDraftRestoresOnlyForTheSamePane() {
        val first = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("draft:pane")).create().start().resume()
        first.get().findViewById<EditText>(R.id.reply_input).setText("line one\nline two")
        first.pause().stop().destroy()

        val restored = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("draft:pane")).create().get()
        val other = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("other:pane")).create().get()

        assertEquals("line one\nline two", restored.findViewById<EditText>(R.id.reply_input).text.toString())
        assertEquals("", other.findViewById<EditText>(R.id.reply_input).text.toString())
    }

    @Test
    fun activityReconstructionKeepsTheReplyDraftButDisarmsTransientDirectTyping() {
        val normal = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("recreate-reply:pane"))
            .create().start().resume()
        normal.get().findViewById<EditText>(R.id.reply_input).setText("survives recreation")
        val normalState = android.os.Bundle()

        normal.pause().saveInstanceState(normalState).stop().destroy()
        val reconstructedNormal = Robolectric.buildActivity(
            PaneActivity::class.java,
            paneIntent("recreate-reply:pane"),
        ).create(normalState).get()

        assertEquals(
            "survives recreation",
            reconstructedNormal.findViewById<EditText>(R.id.reply_input).text.toString(),
        )

        val direct = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("recreate-direct:pane"))
            .create().start().resume()
        val directInput = direct.get().findViewById<DirectTypingEditText>(R.id.reply_input)
        directInput.beginDirectTyping()
        PaneActivity::class.java.getDeclaredField("directTyping").apply {
            isAccessible = true
            setBoolean(direct.get(), true)
        }
        directInput.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            ?.setComposingText("never persist this", 1)
        val directState = android.os.Bundle()

        direct.pause().saveInstanceState(directState).stop().destroy()

        val reconstructedDirect = Robolectric.buildActivity(
            PaneActivity::class.java,
            paneIntent("recreate-direct:pane"),
        ).create(directState).get()
        val recreatedInput = reconstructedDirect.findViewById<DirectTypingEditText>(R.id.reply_input)
        assertFalse(recreatedInput.directTypingEnabled)
        assertEquals("", recreatedInput.text?.toString())
    }

    @Test
    fun ctrlOrCommandEnterUsesTheSameGuardedReplyPathAsSend() {
        listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_META_ON).forEachIndexed { index, modifier ->
            val activity = Robolectric.buildActivity(
                PaneActivity::class.java,
                paneIntent("shortcut-$index:pane"),
            ).create().get()
            val input = activity.findViewById<DirectTypingEditText>(R.id.reply_input)
            input.setText("rm -rf build")

            val down = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, modifier)
            val up = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, modifier)
            assertTrue(input.dispatchKeyEvent(down))
            assertTrue(input.dispatchKeyEvent(up))

            val status = activity.findViewById<TextView>(R.id.error_text)
            assertEquals(View.VISIBLE, status.visibility)
            assertTrue(status.text.toString().startsWith("Destructive:"))
        }
    }

    @Test
    fun switcherPullShowsTheActualNonModalPaneListBeforeRelease() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().get()
        render(
            activity,
            PaneUiState(
                loading = false,
                panes = listOf(
                    paneSummary("w1:p1"),
                    paneSummary("w1:p2").copy(workspaceLabel = "another project", tabLabel = "review"),
                ),
            ),
        )
        val handle = activity.findViewById<View>(R.id.switcher_handle)
        val chrome = activity.findViewById<View>(R.id.composer_chrome)
        val peek = activity.findViewById<View>(R.id.switcher_peek)
        val dialogsBeforePull = ShadowDialog.getShownDialogs().size
        handle.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 400f, 0))
        handle.dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, 10f, 340f, 0))
        assertTrue(chrome.translationY < 0f)
        assertEquals(View.VISIBLE, peek.visibility)
        assertTrue(peek.translationY < activity.resources.displayMetrics.density * 320f)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, peek.importantForAccessibility)
        assertEquals(dialogsBeforePull, ShadowDialog.getShownDialogs().size)

        val previewTexts = descendants(peek).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(previewTexts.contains(activity.getString(R.string.pane_switcher_title)))
        assertTrue(previewTexts.any { it.contains("project") })
        assertTrue(previewTexts.any { it.contains("another project") })
        assertFalse(previewTexts.any { it.contains("available") })
        assertFalse(descendants(peek).any(View::hasOnClickListeners))

        handle.dispatchTouchEvent(MotionEvent.obtain(0, 30, MotionEvent.ACTION_CANCEL, 10f, 340f, 0))
    }

    @Test
    fun switcherPullOnlyBecomesModalAfterAnOpenRelease() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().get()
        render(
            activity,
            PaneUiState(loading = false, panes = listOf(paneSummary("w1:p1"), paneSummary("w1:p2"))),
        )
        val handle = activity.findViewById<View>(R.id.switcher_handle)
        val dialogsBeforePull = ShadowDialog.getShownDialogs().size

        handle.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 400f, 0))
        handle.dispatchTouchEvent(MotionEvent.obtain(0, 200, MotionEvent.ACTION_MOVE, 10f, 260f, 0))
        assertEquals(dialogsBeforePull, ShadowDialog.getShownDialogs().size)

        handle.dispatchTouchEvent(MotionEvent.obtain(0, 220, MotionEvent.ACTION_UP, 10f, 260f, 0))
        assertEquals(dialogsBeforePull + 1, ShadowDialog.getShownDialogs().size)
        assertTrue(ShadowDialog.getLatestDialog() is CollieBottomSheetDialog)
    }

    @Test
    fun blankAgentQueryShowsOnlyCommonCommandsAndSearchUsesTheFullCatalog() {
        val commands = (1..12).map { index ->
            AgentCommand("/command$index", "description $index", common = index <= 3)
        }

        assertEquals(commands.take(3), PaneActivity.filterAgentCommands(commands, ""))
        assertEquals(listOf(commands.last()), PaneActivity.filterAgentCommands(commands, "12"))
        assertTrue(PaneActivity.filterAgentCommands(commands, "missing").isEmpty())
    }

    @Test
    fun findHighlightsLiteralOutputAndBackClosesFindFirst() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("find:pane"))
            .create().start().resume().get()
        PaneActivity::class.java.getDeclaredMethod("renderTerminal", String::class.java).apply {
            isAccessible = true
            invoke(activity, "A.B then a.b; not axb")
        }
        PaneActivity::class.java.getDeclaredMethod("openFind").apply {
            isAccessible = true
            invoke(activity)
        }
        activity.findViewById<EditText>(R.id.find_query).setText("a.b")

        val output = activity.findViewById<TextView>(R.id.terminal_text).text as Spanned
        assertEquals(3, output.getSpans(0, output.length, BackgroundColorSpan::class.java).size)
        assertEquals("1/2", activity.findViewById<TextView>(R.id.find_count).text.toString())

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.find_bar).visibility)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun findTakesOverTheHeaderWithoutMovingBelowTheStrips() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("find-header:pane")).create().get()
        val parent = activity.findViewById<View>(R.id.pane_header).parent as ViewGroup
        val find = activity.findViewById<View>(R.id.find_bar)
        val header = activity.findViewById<View>(R.id.pane_header)
        val strips = activity.findViewById<View>(R.id.tab_strip)

        assertTrue(parent.indexOfChild(find) < parent.indexOfChild(strips))
        PaneActivity::class.java.getDeclaredMethod("openFind").apply {
            isAccessible = true
            invoke(activity)
        }
        assertEquals(View.GONE, header.visibility)
        assertEquals(View.VISIBLE, find.visibility)
    }

    @Test
    fun frozenOutputKeepsTheDisplayedRevisionUntilJumpToLatest() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("freeze:pane")).create().get()
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        render.invoke(activity, PaneUiState(pane = PaneReadResponse("freeze:pane", "old output", false, 1), loading = false))
        PaneActivity::class.java.getDeclaredField("followingOutput").apply {
            isAccessible = true
            setBoolean(activity, false)
        }

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("freeze:pane", "new output", false, 2), loading = false))
        assertEquals("old output", activity.findViewById<TextView>(R.id.terminal_text).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.new_output_button).visibility)

        activity.findViewById<View>(R.id.new_output_button).performClick()
        assertEquals("new output", activity.findViewById<TextView>(R.id.terminal_text).text.toString())
    }

    @Test
    fun displayWrapSwitchChangesBetweenViewportWrapAndHorizontalPan() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("wrap:pane")).create().get()
        val horizontal = activity.findViewById<HorizontalScrollView>(R.id.terminal_horizontal_scroll)
        val output = activity.findViewById<TextView>(R.id.terminal_text)
        val wrap = activity.findViewById<android.widget.CompoundButton>(R.id.wrap_lines_switch)

        wrap.isChecked = false
        assertFalse(horizontal.isFillViewport)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, output.layoutParams.width)

        wrap.isChecked = true
        assertTrue(horizontal.isFillViewport)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, output.layoutParams.width)
    }

    @Test
    fun wrappedOutputPansOnlyTableRunsWhileSurroundingProseStillWraps() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("table:pane")).create().get()
        val text = "prose before\n| Name | A very wide value |\n| --- | --- |\n| row | 12345678901234567890 |\nprose after"
        PaneActivity::class.java.getDeclaredMethod("renderTerminal", String::class.java).apply {
            isAccessible = true
            invoke(activity, text)
        }

        val flat = activity.findViewById<View>(R.id.terminal_horizontal_scroll)
        val blocks = activity.findViewById<ViewGroup>(R.id.terminal_block_content)
        assertEquals(View.GONE, flat.visibility)
        assertEquals(View.VISIBLE, blocks.visibility)
        assertEquals(3, blocks.childCount)
        assertTrue(blocks.getChildAt(1) is HorizontalScrollView)

        activity.findViewById<android.widget.CompoundButton>(R.id.wrap_lines_switch).isChecked = false
        assertEquals(View.VISIBLE, flat.visibility)
        assertEquals(View.GONE, blocks.visibility)
    }

    @Test
    fun terminalAutolinksOnlyAllowlistedSchemesWithoutDroppingAnsi() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("links:pane")).create().get()
        PaneActivity::class.java.getDeclaredMethod("renderTerminal", String::class.java).apply {
            isAccessible = true
            invoke(activity, "\u001b[31mhttps://example.test/a\u001b[0m javascript:bad")
        }
        val output = activity.findViewById<TextView>(R.id.terminal_text).text as Spanned

        assertEquals(1, output.getSpans(0, output.length, URLSpan::class.java).size)
        assertTrue(output.getSpans(0, output.length, android.text.style.ForegroundColorSpan::class.java).isNotEmpty())
    }

    @Test
    fun verifiedSendShowsEchoGapPreviewUntilTheMirrorChanges() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("sent:pane")).create().get()
        val pane = PaneReadResponse("sent:pane", "prompt", false, 4)
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        render.invoke(activity, PaneUiState(pane = pane, loading = false))
        PaneActivity::class.java.getDeclaredMethod("beginReplySend", String::class.java).apply {
            isAccessible = true
            invoke(activity, "check the patch")
        }

        render.invoke(activity, PaneUiState(pane = pane, loading = false, clearReplyDraft = true))
        val preview = activity.findViewById<TextView>(R.id.pending_send_notice)
        assertEquals(View.VISIBLE, preview.visibility)
        assertTrue(preview.text.toString().contains("check the patch"))

        render.invoke(activity, PaneUiState(pane = pane.copy(text = "prompt\ncheck the patch", revision = 5), loading = false))
        assertEquals(View.GONE, preview.visibility)
    }

    @Test
    fun aMirrorRenderNeverTakesFocusFromTheComposer() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("focus:pane")).create().start().resume().get()
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        val field = activity.findViewById<View>(R.id.reply_input)
        render.invoke(activity, PaneUiState(pane = PaneReadResponse("focus:pane", "~\n❯ ", false, 0), loading = false, canWrite = true))
        field.requestFocus()
        assertTrue("composer must be focusable once a writable pane is shown", field.isFocused)

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("focus:pane", "~\n❯ a", false, 0), loading = false, canWrite = true))
        render.invoke(activity, PaneUiState(pane = PaneReadResponse("focus:pane", "~\n❯ ab", false, 0), loading = false, canWrite = true))

        assertTrue(field.isFocused)
        assertFalse(activity.findViewById<View>(R.id.terminal_text).isFocused)
    }

    @Test
    fun anAnsweredDialogClearsItsPanelEvenWhenHerdrRepeatsRevisionZero() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("dialog:pane")).create().start().resume().get()
        PaneActivity::class.java.getDeclaredField("agentName").apply { isAccessible = true; set(activity, "claude") }
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        val panel = activity.findViewById<View>(R.id.semantic_panel)
        val dialog = "Which colour?\n❯ 1. Red\n  2. Green\nEnter to select · ↑/↓ to navigate · Esc to cancel"

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("dialog:pane", dialog, false, 0), loading = false, canWrite = true))
        assertEquals(View.VISIBLE, panel.visibility)

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("dialog:pane", "● Green\n❯ ", false, 0), loading = false, canWrite = true))
        assertEquals(View.GONE, panel.visibility)
    }

    @Test
    fun mirrorFollowsChangedTextWhenHerdrRepeatsRevisionZero() {
        // Herdr 0.7.x stubs `revision` to 0 on every read (HERDR_API.md); only the text can key change.
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("rev0:pane")).create().get()
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply { isAccessible = true }
        val mirror = activity.findViewById<TextView>(R.id.terminal_text)

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("rev0:pane", "~\n❯ ", false, 0), loading = false))
        assertTrue(mirror.text.toString().contains("❯"))

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("rev0:pane", "~\n❯ echo hi\nhi\n❯ ", false, 0), loading = false))
        assertTrue(mirror.text.toString().contains("echo hi"))

        render.invoke(activity, PaneUiState(pane = PaneReadResponse("rev0:pane", "", false, 0), loading = false))
        assertEquals("", mirror.text.toString())
    }

    @Test
    fun microphoneOwnsThePrimaryActionOnlyWhileTheDraftIsEmpty() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("mic:pane")).create().get()
        PaneActivity::class.java.getDeclaredField("speechAvailability").apply {
            isAccessible = true
            set(activity, SpeechAvailability.Available("test"))
        }
        PaneActivity::class.java.getDeclaredMethod("renderComposerMediaControls").apply {
            isAccessible = true
            invoke(activity)
        }
        val microphone = activity.findViewById<View>(R.id.speech_button)
        val send = activity.findViewById<View>(R.id.send_button)
        val input = activity.findViewById<EditText>(R.id.reply_input)
        assertEquals(View.VISIBLE, microphone.visibility)
        assertEquals(View.GONE, send.visibility)

        input.setText("hello")
        assertEquals(View.GONE, microphone.visibility)
        assertEquals(View.VISIBLE, send.visibility)

        input.text.clear()
        assertEquals(View.VISIBLE, microphone.visibility)
        assertEquals(View.GONE, send.visibility)
    }

    @Test
    fun livePaneSummaryReplacesIntentHeaderAndAddsMultiPaneDiscriminator() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("w1:p1")).create().get()
        val panes = listOf(
            paneSummary("w1:p1"),
            paneSummary("w1:p2"),
        )
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, PaneUiState(loading = false, panes = panes))
        }

        assertEquals("project › build", activity.findViewById<TextView>(R.id.pane_title).text.toString())
        assertEquals("p1", activity.findViewById<TextView>(R.id.pane_discriminator).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_cwd).visibility)
    }

    @Test
    fun terminalAndDraftUseNativeTextSizes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        NativePreferences(context).apply {
            terminalFontSize = 15
            draftFontSize = 16
        }
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("font:pane")).create().get()

        val density = activity.resources.displayMetrics.scaledDensity
        assertEquals(15f, activity.findViewById<TextView>(R.id.terminal_text).textSize / density)
        assertEquals(16f, activity.findViewById<TextView>(R.id.reply_input).textSize / density)
    }

    @Test
    fun rawModeHidesSemanticReplacementAndRestoresItWithoutANewRevision() {
        val intent = paneIntent("semantic:pane").putExtra(PaneActivity.EXTRA_AGENT, "claude")
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()
        val raw = """
            prior output
            Which color?
            ❯ 1. Red
              2. Blue
            Enter to select · Esc to cancel
        """.trimIndent()
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(
                activity,
                PaneUiState(
                    pane = PaneReadResponse("semantic:pane", raw, truncated = false, revision = 7),
                    loading = false,
                ),
            )
        }
        val panel = activity.findViewById<View>(R.id.semantic_panel)
        val terminal = activity.findViewById<TextView>(R.id.terminal_text)
        val rawSwitch = activity.findViewById<android.widget.CompoundButton>(R.id.raw_terminal_switch)

        assertEquals(View.VISIBLE, panel.visibility)
        assertFalse(terminal.text.toString().contains("Which color?"))
        rawSwitch.isChecked = true
        assertEquals(View.GONE, panel.visibility)
        assertTrue(terminal.text.toString().contains("Which color?"))
        rawSwitch.isChecked = false
        assertEquals(View.VISIBLE, panel.visibility)
        assertFalse(terminal.text.toString().contains("Which color?"))
    }

    @Test
    fun passwordPromptDisablesOrdinaryAndMediaInputButOffersExplicitType() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("password:pane"))
            .create().get()
        val input = activity.findViewById<EditText>(R.id.reply_input)
        input.setText("not retained as a password")
        val state = PaneUiState(
            pane = PaneReadResponse(
                "password:pane",
                "$ sudo -v\n[sudo] password for operator:",
                truncated = false,
                revision = 9,
            ),
            loading = false,
            canWrite = true,
        )
        setViewModelState(activity, state)
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.no_echo_notice).visibility)
        assertFalse(activity.findViewById<View>(R.id.send_button).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.attach_image_button).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.quick_mode_button).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.type_mode_button).isEnabled)
        val draftKey = PaneActivity::class.java.getDeclaredField("paneDraftKey").run {
            isAccessible = true
            get(activity) as String
        }
        assertEquals("", NativePreferences(activity).paneDraft(draftKey))

        input.setText("still never persisted")
        assertEquals("", NativePreferences(activity).paneDraft(draftKey))

        activity.findViewById<View>(R.id.no_echo_type).performClick()
        assertEquals("", input.text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.no_echo_notice).visibility)
        assertTrue(input.isEnabled)
    }

    @Test
    fun passwordPromptCannotBePersistedByOnStop() {
        val controller = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("password-stop:pane"))
            .create().start().resume()
        val activity = controller.get()
        val input = activity.findViewById<EditText>(R.id.reply_input)
        input.setText("stored before no-echo detection")
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(
                activity,
                PaneUiState(
                    pane = PaneReadResponse(
                        "password-stop:pane",
                        "Password:",
                        truncated = false,
                        revision = 1,
                    ),
                    loading = false,
                    canWrite = true,
                ),
            )
        }
        input.setText("must remain memory-only")

        controller.pause().stop()

        val restored = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("password-stop:pane")).create().get()
        assertEquals("", restored.findViewById<EditText>(R.id.reply_input).text.toString())
    }

    @Test
    fun passwordPromptWarningCanBeDismissedWhenDetectionIsWrong() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("password-dismiss:pane"))
            .create().get()
        val input = activity.findViewById<EditText>(R.id.reply_input)
        val pane = PaneReadResponse("password-dismiss:pane", "Password:", false, 3)
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, PaneUiState(pane = pane, loading = false, canWrite = true))
        }

        activity.findViewById<View>(R.id.no_echo_dismiss).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.no_echo_notice).visibility)
        assertTrue(input.isEnabled)

        input.setText("this is ordinary input")
        val draftKey = PaneActivity::class.java.getDeclaredField("paneDraftKey").run {
            isAccessible = true
            get(activity) as String
        }
        assertEquals("this is ordinary input", NativePreferences(activity).paneDraft(draftKey))

        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, PaneUiState(pane = pane.copy(revision = 4), loading = false, canWrite = true))
        }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.no_echo_notice).visibility)
    }

    @Test
    fun zenKeepsAnOnScreenExitAndEscapeLeavesZen() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("zen:pane")).create().get()
        PaneActivity::class.java.getDeclaredMethod("setZenMode", java.lang.Boolean.TYPE).apply {
            isAccessible = true
            invoke(activity, true)
        }

        val exit = activity.findViewById<View>(R.id.new_output_button)
        assertEquals(View.VISIBLE, exit.visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.pane_header).visibility)

        exit.performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_header).visibility)
        assertEquals(View.GONE, exit.visibility)

        PaneActivity::class.java.getDeclaredMethod("setZenMode", java.lang.Boolean.TYPE).apply {
            isAccessible = true
            invoke(activity, true)
        }
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pane_header).visibility)
        assertEquals(View.GONE, exit.visibility)
    }

    @Test
    fun stableTerminalDraftTakeOverCopiesAndMakesTheBoundReplyAvailable() {
        val intent = paneIntent("draft-host:pane").putExtra(PaneActivity.EXTRA_AGENT, "claude")
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()
        val rule = "─".repeat(40)
        val state = PaneUiState(
            pane = PaneReadResponse(
                "draft-host:pane",
                "prior\n$rule\n❯ continue from the host\n$rule\n  Opus · /repo",
                truncated = false,
                revision = 10,
            ),
            loading = false,
            canWrite = true,
        )
        val render = PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
        }
        render.invoke(activity, state)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.terminal_draft_notice).visibility)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_500))
        render.invoke(activity, state.copy(lastSuccessAt = 1_500))

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.terminal_draft_notice).visibility)
        activity.findViewById<View>(R.id.terminal_draft_take_over).performClick()
        assertEquals("continue from the host", activity.findViewById<EditText>(R.id.reply_input).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.terminal_draft_notice).visibility)
        assertTrue(activity.findViewById<View>(R.id.send_button).isEnabled)
    }

    @Test
    fun ordinaryPaneMissingFromAHealthyTopologyReturnsToItsScopedHome() {
        val intent = paneIntent("gone:pane")
            .putExtra(PaneActivity.EXTRA_HOST, "peer-1")
            .putExtra(PaneActivity.EXTRA_SESSION, "work")
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()

        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("gone:pane", "still cached", false, 5),
                loading = false,
                canWrite = true,
                topologyKnown = true,
                panes = emptyList(),
            ),
        )

        val home = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, home.component?.className)
        assertTrue(home.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(home.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals("Pane closed", ShadowToast.getTextOfLatestToast())
        assertEquals("peer-1", activity.repositoryForTest().selectedScope.value.host)
        assertEquals("work", activity.repositoryForTest().selectedScope.value.session)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun missingPaneDoesNotRedirectBeforeTopologyIsKnownHealthy() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("pending:pane")).create().get()

        render(
            activity,
            PaneUiState(
                pane = PaneReadResponse("pending:pane", "cached output", false, 4),
                loading = false,
                topologyKnown = false,
                panes = emptyList(),
            ),
        )

        assertFalse(activity.isFinishing)
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun freshlyCreatedPaneMayRemainOpenBeforeItsFirstTopologyAppearance() {
        val intent = paneIntent("fresh:pane").putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()

        render(activity, PaneUiState(loading = false, topologyKnown = true, panes = emptyList()))

        assertFalse(activity.isFinishing)
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun freshlyCreatedPaneEvictsAfterItWasSeenAndThenDisappears() {
        val intent = paneIntent("fresh-seen:pane").putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        val activity = Robolectric.buildActivity(PaneActivity::class.java, intent).create().get()
        val present = paneSummary("fresh-seen:pane")

        render(activity, PaneUiState(loading = false, topologyKnown = true, panes = listOf(present)))
        assertFalse(activity.isFinishing)

        render(activity, PaneUiState(loading = false, topologyKnown = true, panes = emptyList()))

        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun freshlyCreatedPaneKeepsItsSeenStateAcrossActivityRecreation() {
        val intent = paneIntent("fresh-recreated:pane").putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        val controller = Robolectric.buildActivity(PaneActivity::class.java, intent).create()
        val activity = controller.get()
        val present = paneSummary("fresh-recreated:pane")
        render(activity, PaneUiState(loading = false, topologyKnown = true, panes = listOf(present)))
        val savedState = Bundle()
        controller.saveInstanceState(savedState).pause().stop().destroy()

        val recreated = Robolectric.buildActivity(PaneActivity::class.java, intent).create(savedState).get()
        render(recreated, PaneUiState(loading = false, topologyKnown = true, panes = emptyList()))

        assertEquals(MainActivity::class.java.name, shadowOf(recreated).nextStartedActivity.component?.className)
        assertTrue(recreated.isFinishing)
    }

    @Test
    fun statuslineRowRendersUnderTheMirrorForClaude() {
        val activity = Robolectric.buildActivity(PaneActivity::class.java, paneIntent("status:pane", agent = "claude")).create().start().resume().get()
        val text = listOf("⏺ hi", "─".repeat(30), "❯ ", "─".repeat(30), "  Opus 5 · 12%").joinToString("\n")
        render(activity, PaneUiState(pane = PaneReadResponse("status:pane", text, truncated = false, revision = 0), loading = false))
        val row = activity.findViewById<TextView>(R.id.terminal_statusline)
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals("Opus 5 · 12%", row.text.toString())
    }

    private fun paneIntent(paneId: String, agent: String = "opencode"): Intent =
        Intent(ApplicationProvider.getApplicationContext(), PaneActivity::class.java)
            .putExtra(PaneActivity.EXTRA_PANE_ID, paneId)
            .putExtra(PaneActivity.EXTRA_AGENT, agent)

    private fun paneSummary(paneId: String) = PaneSummary(
        paneId = paneId,
        workspaceId = "w1",
        workspaceLabel = "project",
        workspaceNumber = 1,
        tabId = "w1:t1",
        agent = "codex",
        status = AgentStatus.WORKING,
        cwd = "/home/operator/project/build",
        focused = paneId == "w1:p1",
        tabLabel = "build",
    )

    private fun keyQueue(activity: PaneActivity): PaneKeyQueue =
        PaneActivity::class.java.getDeclaredField("paneKeyQueue").run {
            isAccessible = true
            get(activity) as PaneKeyQueue
        }

    private fun render(activity: PaneActivity, state: PaneUiState) {
        PaneActivity::class.java.getDeclaredMethod("render", PaneUiState::class.java).apply {
            isAccessible = true
            invoke(activity, state)
        }
    }

    private fun PaneActivity.repositoryForTest() = (application as com.lateapex.collie.CollieApplication).container.repository

    private fun sheetButton(dialog: CollieBottomSheetDialog, label: String): MaterialButton =
        descendants(requireNotNull(dialog.findViewById<View>(R.id.collie_sheet_content)))
            .filterIsInstance<MaterialButton>()
            .single { it.text.toString() == label }

    @Suppress("UNCHECKED_CAST")
    private fun setViewModelState(activity: PaneActivity, state: PaneUiState) {
        val viewModel = PaneActivity::class.java.getDeclaredField("viewModel").run {
            isAccessible = true
            get(activity) as PaneViewModel
        }
        val mutable = PaneViewModel::class.java.getDeclaredField("mutableState").run {
            isAccessible = true
            get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PaneUiState>
        }
        mutable.value = state
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }
}
