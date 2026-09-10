package com.lateapex.collie.ui.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeSemanticDeriverTest {
    @Test
    fun representativeWebFixturesProduceTypedNativeModels() {
        val plan = model("claude--plan-approval.txt") as NativeSemanticModel.Prompt
        assertEquals("Plan approval", plan.family)
        assertEquals("4", plan.feedback?.key)
        assertTrue(plan.feedback?.offered == true)

        val wizard = model("claude--wizard-q1.txt") as NativeSemanticModel.Wizard
        assertFalse(wizard.review)
        assertTrue(wizard.steps.size >= 3)
        assertEquals("Work on parsing logic and input handling.", wizard.options.first().description)

        val review = model("claude--wizard-submit.txt") as NativeSemanticModel.Wizard
        assertTrue(review.review)
        assertEquals("UI", review.answers.first().answer)

        val preview = model("claude--select-preview.txt") as NativeSemanticModel.Preview
        assertEquals(listOf("Boxy", "Rounded", "Minimal"), preview.options.map(Option::label))
        assertTrue(preview.preview.isNotEmpty())
        assertEquals(NoteState.NONE, preview.note.state)

        val multi = model("claude--select-multiselect-checked.txt") as NativeSemanticModel.Multi
        assertEquals(listOf(false, true, true, false), multi.options.map(Option::checked))
        assertEquals("Sliced mushrooms.", multi.options[1].description)
        assertEquals("Submit", multi.advanceLabel)
        assertTrue(multi.steps.isEmpty())

        val wizardMultiSurface = surface("claude--wizard-multiselect-q1.txt")
        val wizardMulti = wizardMultiSurface.nativeModel as NativeSemanticModel.Multi
        assertEquals(2, wizardMulti.steps.size)
        assertTrue(wizardMultiSurface.actions.any { it.id == "previous" && it.keys == listOf("Left") })
        assertTrue(wizardMultiSurface.actions.any { it.id == "next" && it.keys == listOf("Right") })

        val menu = model("claude--menu-model-picker.txt") as NativeSemanticModel.Menu
        assertTrue(menu.upDown)
        assertTrue(menu.leftRight)
        assertTrue(menu.rawLines.any { it.contains("Select model") })

        val autocomplete = model("claude--autocomplete-slash-short.txt") as NativeSemanticModel.Autocomplete
        assertTrue(autocomplete.entries.isNotEmpty())
        assertTrue(autocomplete.entries.first().name.startsWith("/"))
    }

    @Test
    fun focusedAndAttachedEditorStatesAreNotFlattenedIntoButtons() {
        val focused = model("claude--plan-approval--feedback-focused.txt") as NativeSemanticModel.Prompt
        assertTrue(focused.feedback?.focused == true)
        assertEquals("", focused.feedback?.text)

        val typedFocused = model("claude--plan-approval--three-row-typed-focused.txt") as NativeSemanticModel.Prompt
        assertTrue(typedFocused.feedback?.focused == true)
        assertTrue(typedFocused.feedback?.text?.contains("guard clause") == true)

        val attached = model("claude--select-preview-note-attached.txt") as NativeSemanticModel.Preview
        assertEquals(NoteState.ATTACHED, attached.note.state)
        assertTrue(attached.note.text.contains("prefer subtle shadows"))

        val editing = model("claude--select-preview-note-input.txt") as NativeSemanticModel.Preview
        assertEquals(NoteState.EDITING, editing.note.state)
    }

    private fun model(name: String): NativeSemanticModel {
        return surface(name).nativeModel ?: error("No native model for $name")
    }

    private fun surface(name: String): SemanticSurface {
        val file = File(fixtureDirectory(), name)
        return AgentSemanticParser.detect("claude", file.readText(), 41)
            ?: error("No semantic surface for $name")
    }

    private fun fixtureDirectory(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "web/src/fixtures/panes") }
        .first(File::isDirectory)
}
