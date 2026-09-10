package com.lateapex.collie.ui

import com.lateapex.collie.network.AgentStatus
import com.lateapex.collie.network.PaneSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneHeaderPresentationTest {
    @Test
    fun fallbackNameGetsStablePaneDiscriminatorOnlyInMultiPaneTab() {
        val pane = pane(paneId = "w1:p3", tabLabel = "build")

        assertEquals("project › build", PaneHeaderPresenter.present(pane, 2).name)
        assertEquals("p3", PaneHeaderPresenter.present(pane, 2).discriminator)
        assertNull(PaneHeaderPresenter.present(pane, 1).discriminator)
    }

    @Test
    fun handNamedPaneSuppressesDiscriminatorAndKeepsInformativeCwd() {
        val result = PaneHeaderPresenter.present(
            pane(paneLabel = "logs", cwd = "/home/operator/project/worktrees/fix-42"),
            tabPaneCount = 3,
        )

        assertEquals("logs", result.name)
        assertNull(result.discriminator)
        assertEquals("~/project/worktrees/fix-42", result.cwd)
    }

    @Test
    fun cwdRepeatingRenderedBreadcrumbIsSuppressed() {
        val result = PaneHeaderPresenter.present(
            pane(cwd = "/home/operator/project/build"),
            tabPaneCount = 1,
        )
        assertNull(result.cwd)
    }

    private fun pane(
        paneId: String = "w1:p1",
        tabLabel: String = "build",
        paneLabel: String? = null,
        cwd: String = "/home/operator/project",
    ) = PaneSummary(
        paneId = paneId,
        workspaceId = "w1",
        workspaceLabel = "project",
        workspaceNumber = 1,
        tabId = "w1:t1",
        agent = "codex",
        status = AgentStatus.WORKING,
        cwd = cwd,
        focused = true,
        paneLabel = paneLabel,
        tabLabel = tabLabel,
    )
}
