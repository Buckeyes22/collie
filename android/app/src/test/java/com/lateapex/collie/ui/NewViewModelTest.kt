package com.lateapex.collie.ui

import com.lateapex.collie.network.FoldersResponse
import com.lateapex.collie.network.Launcher
import com.lateapex.collie.network.LauncherItem
import com.lateapex.collie.network.LaunchersResponse
import com.lateapex.collie.network.StartTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NewViewModelTest {
    @Test
    fun richCatalogPreservesUnavailableReasonsAndExactDeclarativeTargets() {
        val response = LaunchersResponse(
            launchers = emptyList(),
            home = "/home/operator",
            items = listOf(
                LauncherItem("agent:codex", StartTarget(harness = "codex"), "agents", "Codex", "harness", false, false, true, harness = "codex"),
                LauncherItem("agent:missing", StartTarget(harness = "claude"), "agents", "Claude", "harness", true, false, false, harness = "claude", reason = "not installed"),
                LauncherItem("shell", StartTarget(shell = true), "shell", "Shell", "shell", false, false, true),
            ),
        )

        val choices = NewLaunchModel.choices(response)
        assertEquals(listOf("agent:codex", "agent:missing", "shell"), choices.map { it.key })
        assertEquals(StartTarget(harness = "codex"), choices[0].target)
        assertFalse(choices[1].available)
        assertEquals("not installed", choices[1].reason)
        assertTrue(choices[2].available)
        assertEquals(StartTarget(shell = true), choices[2].target)
    }

    @Test
    fun olderBridgeCatalogMapsOnlyItsDeclaredCommands() {
        val choices = NewLaunchModel.choices(
            LaunchersResponse(
                launchers = listOf(Launcher("codex --resume", "Codex", cwd = "/srv/app", noPrompts = true)),
                home = "/home/operator",
            ),
        )
        assertEquals(1, choices.size)
        assertEquals(StartTarget(command = "codex --resume"), choices.single().target)
        assertEquals("/srv/app", choices.single().cwd)
        assertTrue(choices.single().noPrompts)
        assertFalse(choices.single().ownerIntentSupported)
    }

    @Test
    fun targetRejectsZeroOrMultipleStartKinds() {
        assertThrows(IllegalArgumentException::class.java) { StartTarget() }
        assertThrows(IllegalArgumentException::class.java) { StartTarget(command = "known-row", shell = true) }
    }

    @Test
    fun folderChoicesKeepHomeStarredAndRecentValuesFromOneHostResponse() {
        val choices = FolderPickerModel.choices(
            FoldersResponse(recent = listOf("/srv/peer/recent"), favourites = listOf("/srv/peer/favourite"), home = "/srv/peer"),
        )
        assertEquals(listOf(null, "/srv/peer/favourite", "/srv/peer/recent"), choices.map { it.value })
        assertTrue(choices[1].favourite)
        assertFalse(choices[2].favourite)
        assertEquals("/srv/peer", choices[0].label)
    }

}
