package com.lateapex.collie.ui

import com.lateapex.collie.network.LaunchCheckResponse
import com.lateapex.collie.network.LaunchersAdding
import com.lateapex.collie.network.RecipeOptionWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchRecipeViewModelTest {
    @Test
    fun checkBindsToVerbatimCommandAndActorScopeAndInvalidatesWhenEdited() {
        val model = LaunchRecipeViewModel()
        val exact = "printf '%s' \"${'$'}HOME/a b\""
        model.runText = exact
        val answer = LaunchCheckResponse(ok = true, noPrompts = false)
        model.recordCheck(exact, "host-a\u0000session-a\u00001", answer)

        assertEquals(answer, model.checkFor(exact, "host-a\u0000session-a\u00001"))
        assertNull(model.checkFor(exact, "host-a\u0000session-b\u00001"))
        model.runText = "$exact "
        assertNull(model.checkFor(exact, "host-a\u0000session-a\u00001"))
        assertEquals("$exact ", model.runText)
    }

    @Test
    fun oldPeerWithoutPositiveAddingDeclarationKeepsEveryNewActionClosed() {
        val noDeclaration: com.lateapex.collie.network.LaunchersAdding? = null
        assertNull(noDeclaration)
        assertFalse(LaunchRecipeModel.mayAddRecipe(noDeclaration))
        assertFalse(LaunchRecipeModel.mayAddFreeText(noDeclaration))
        assertFalse(LaunchRecipeModel.mayRun(noDeclaration))
        assertFalse(LaunchRecipeModel.mayRun(adding(run = false)))
        assertTrue(LaunchRecipeModel.mayRun(adding(run = true)))
        assertFalse(LaunchRecipeModel.mayAddFreeText(adding(freeText = false)))
        assertTrue(LaunchRecipeModel.mayAddFreeText(adding(freeText = true)))
        assertFalse(LaunchRecipeModel.validOneOf(null, null, shell = false, run = null))
        assertTrue(LaunchRecipeModel.validOneOf(null, null, shell = false, run = "printf ok"))
    }

    @Test
    fun recipeOptionsRejectUnknownDuplicateAndMutuallyExclusiveSelections() {
        val options = listOf(
            RecipeOptionWire("safe", "Safe", "--safe", group = "mode"),
            RecipeOptionWire("fast", "Fast", "--fast", group = "mode"),
            RecipeOptionWire("verbose", "Verbose", "--verbose"),
        )
        assertTrue(LaunchRecipeModel.optionsAreValid(options, listOf("safe", "verbose")))
        assertFalse(LaunchRecipeModel.optionsAreValid(options, listOf("safe", "fast")))
        assertFalse(LaunchRecipeModel.optionsAreValid(options, listOf("safe", "safe")))
        assertFalse(LaunchRecipeModel.optionsAreValid(options, listOf("unknown")))
    }

    @Test
    fun invalidCheckCannotBePresentedAsAUsableCommand() {
        assertEquals("forbidden_character", LaunchRecipeModel.problemMessage("forbidden_character"))
        assertEquals("too_long", LaunchRecipeModel.problemMessage("too_long"))
        assertEquals("forbidden_character", LaunchRecipeModel.problemMessage("unknown-server-problem"))
    }

    private fun adding(
        adds: Boolean = true,
        freeText: Boolean = true,
        run: Boolean = true,
        count: Int = 0,
        max: Int = 20,
    ) = LaunchersAdding(adds, freeText, run, "launchers.toml", count, max, emptyList())
}
