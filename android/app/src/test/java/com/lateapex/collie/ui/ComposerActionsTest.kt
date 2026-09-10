package com.lateapex.collie.ui

import com.lateapex.collie.network.OperatorCommand
import com.lateapex.collie.network.OperatorKeyRow
import com.lateapex.collie.network.OperatorQuickReplyRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerActionsTest {
    @Test
    fun argumentCommandAlwaysAppendsAfterTheWholeDraft() {
        assertEquals("/review ", ComposerActions.appendArgumentCommand("", "/review"))
        assertEquals("abc /review ", ComposerActions.appendArgumentCommand("abc", "/review"))
    }

    @Test
    fun operatorKeyRowsReplaceShippedPresetsAtTheMostSpecificScope() {
        val rows = listOf(
            OperatorKeyRow(label = "Global", keys = listOf("ctrl+g")),
            OperatorKeyRow(agent = "codex", label = "Deploy", keys = listOf("ctrl+d"), danger = true),
            OperatorKeyRow(agent = "codex-main", label = "Deploy", keys = listOf("ctrl+x")),
        )

        assertEquals(
            listOf(
                ComposerKeyPreset("Global", listOf("ctrl+g")),
                ComposerKeyPreset("Deploy", listOf("ctrl+x")),
            ),
            ComposerActions.keyPresets("codex-main", rows),
        )
        assertEquals(
            "Ctrl C",
            ComposerActions.keyPresets("shell", rows.filter { it.agent != null }).first().label,
        )
    }

    @Test
    fun openCodeGetsItsOwnCommandPalette() {
        val commands = ComposerActions.commands("opencode-dev", emptyList())

        assertTrue(commands.any { it.command == "/models" })
        assertTrue(commands.any { it.command == "/new" && it.dangerous })
    }

    @Test
    fun lookalikeAgentNamesDoNotInheritACommandPalette() {
        assertTrue(ComposerActions.commands("opencodex", emptyList()).isEmpty())
        assertTrue(ComposerActions.commands("codexical", emptyList()).isEmpty())
        assertTrue(ComposerActions.commands("pitwall", emptyList()).isEmpty())
    }

    @Test
    fun operatorCommandsReplaceTheShippedPaletteForTheirAgent() {
        val mine = listOf(
            OperatorCommand(agent = "opencode", command = "/mine", description = "Mine", common = false),
        )

        assertEquals(listOf("/mine"), ComposerActions.commands("opencode", mine).map { it.command })
        assertEquals(false, ComposerActions.commands("opencode", mine).single().common)
        assertTrue(ComposerActions.commands("codex", mine).any { it.command == "/status" })
    }

    @Test
    fun operatorRowsUseExactThenFamilyThenGlobalSpecificityAndDedupe() {
        val rows = listOf(
            OperatorCommand(command = "/deploy", description = "global"),
            OperatorCommand(agent = "claude", command = "/deploy", description = "family"),
            OperatorCommand(agent = "claude-code", command = "/deploy", description = "exact"),
            OperatorCommand(agent = "claude-code", command = "/other", description = "first"),
            OperatorCommand(agent = "claude-code", command = "/other", description = "later"),
        )

        val resolved = ComposerActions.commands("claude-code", rows)
        assertEquals(listOf("exact", "later"), resolved.map { it.description })
    }

    @Test
    fun antigravityFamilyScopeKeepsItsNameWhileUsingTheAgyCatalog() {
        val rows = listOf(
            OperatorCommand(agent = "antigravity", command = "/safe", description = "family", confirm = true),
        )

        val resolved = ComposerActions.commands("antigravity-dev", rows)
        assertEquals(listOf("family"), resolved.map { it.description })
        assertTrue(resolved.single().dangerous)
    }

    @Test
    fun quickRepliesUseAgentAndShellDefaultsAndOperatorReplacement() {
        assertEquals(listOf("yes", "no"), ComposerActions.quickReplies("codex", false, emptyList()).first().items)
        assertEquals(listOf("y", "n"), ComposerActions.quickReplies("shell", true, emptyList()).first().items)
        val mine = listOf(OperatorQuickReplyRow(agent = "codex", title = "mine", items = listOf("ship it")))
        assertEquals(listOf("ship it"), ComposerActions.quickReplies("codex", false, mine).single().items)
    }
}
