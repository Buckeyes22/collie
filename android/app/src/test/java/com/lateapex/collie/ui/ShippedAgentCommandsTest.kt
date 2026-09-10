package com.lateapex.collie.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ShippedAgentCommandsTest {
    @Test
    fun nativeCatalogExactlyMatchesTheCurrentWebCatalog() {
        val source = findWebCatalog()
        val arrays = ARRAY.findAll(source.readText()).associate { match ->
            match.groupValues[1].lowercase() to ROW.findAll(match.groupValues[2]).map { row ->
                AgentCommand(
                    command = row.groupValues[1],
                    description = row.groupValues[2],
                    takesArgument = row.groupValues[3].toBoolean(),
                    argumentHint = row.groupValues[4],
                    common = row.groupValues[5].toBoolean(),
                    dangerous = row.groupValues[6].toBoolean(),
                )
            }.toList()
        }

        assertEquals(setOf("claude", "codex", "pi", "opencode", "omp", "grok", "agy"), arrays.keys)
        arrays.forEach { (agent, expected) ->
            assertEquals("$agent shipped command catalog drifted from web", expected, ShippedAgentCommands.forAgent(agent))
        }
        assertEquals(167, arrays.values.sumOf { it.size })
    }

    private fun findWebCatalog(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = directory?.resolve("web/src/lib/agent-commands.ts")
            if (candidate?.isFile == true) return candidate
            directory = directory?.parentFile
        }
        throw AssertionError("Could not locate web/src/lib/agent-commands.ts from the Gradle test directory")
    }

    private companion object {
        val ARRAY = Regex(
            """const (CLAUDE|CODEX|PI|OPENCODE|OMP|GROK|AGY): readonly AgentCommand\[\] = \[([\s\S]*?)\n\];""",
        )
        val ROW = Regex(
            """\{ command: "([^"]*)", description: "([^"]*)", takesArg: (true|false), argHint: "([^"]*)", common: (true|false), dangerous: (true|false) \}""",
        )
    }
}
