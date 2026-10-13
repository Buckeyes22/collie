package com.lateapex.collie.ui

import com.lateapex.collie.network.WorktreeDefaultTarget
import com.lateapex.collie.network.WorktreeParentTarget
import com.lateapex.collie.network.WorktreePlanResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreePlanPresentationTest {
    private fun plan(
        ok: Boolean = true,
        branchValid: Boolean? = true,
        defaultPath: String? = "/home/pat/repo-topic",
        exists: Boolean = false,
        parent: WorktreeParentTarget? = null,
        defaultBranch: String? = "main",
        error: String? = null,
    ) = WorktreePlanResponse(
        ok = ok,
        repoRoot = "/home/pat/repo",
        defaultBranch = defaultBranch,
        branchValid = branchValid,
        defaultTarget = defaultPath?.let { WorktreeDefaultTarget(it, exists) },
        parentTarget = parent,
        error = error,
    )

    @Test fun staleScopeAndNonLeadResponsesCannotBecomeAPlan() {
        assertEquals(
            WorktreePlanPresentation.Result.Refused(WorktreePlanPresentation.Refusal.STALE_SCOPE),
            WorktreePlanPresentation.evaluate(plan(), null, null, ownerStillCurrent = false, leadLocal = true),
        )
        assertEquals(
            WorktreePlanPresentation.Result.Refused(WorktreePlanPresentation.Refusal.NOT_LEAD),
            WorktreePlanPresentation.evaluate(plan(), null, null, ownerStillCurrent = true, leadLocal = false),
        )
    }

    @Test fun invalidRefAndForbiddenParentStayServerRefusals() {
        assertEquals(
            WorktreePlanPresentation.Result.Refused(WorktreePlanPresentation.Refusal.INVALID_BRANCH),
            WorktreePlanPresentation.evaluate(plan(branchValid = false), null, "release/2", true, true),
        )
        assertEquals(
            WorktreePlanPresentation.Result.Refused(WorktreePlanPresentation.Refusal.FORBIDDEN_PARENT, "outside home"),
            WorktreePlanPresentation.evaluate(plan(parent = WorktreeParentTarget(false, error = "outside home")), "/tmp", null, true, true),
        )
    }

    @Test fun aFolderThatAppearsAfterPlanningIsNotPresentedAsAvailable() {
        assertEquals(
            WorktreePlanPresentation.Result.Refused(WorktreePlanPresentation.Refusal.TARGET_EXISTS),
            WorktreePlanPresentation.evaluate(plan(exists = true), null, null, true, true),
        )
    }

    @Test fun currentPlanShowsOnlyServerReturnedPathsAndChosenBase() {
        assertEquals(
            WorktreePlanPresentation.Result.Ready("/home/pat/repo-topic", "main"),
            WorktreePlanPresentation.evaluate(plan(), null, null, true, true),
        )
        assertEquals(
            WorktreePlanPresentation.Result.Ready("/home/pat/branches/topic", "release/2"),
            WorktreePlanPresentation.evaluate(plan(parent = WorktreeParentTarget(true, "/home/pat/branches/topic")), "/home/pat/branches", "release/2", true, true),
        )
        assertEquals(
            WorktreePlanPresentation.Result.Refused(WorktreePlanPresentation.Refusal.TARGET_UNAVAILABLE),
            WorktreePlanPresentation.evaluate(plan(defaultPath = null), null, null, true, true),
        )
    }

    @Test fun existingLinkedWorktreesReopenRatherThanCreateAgain() {
        assertTrue(WorktreePlanPresentation.shouldReopenExisting(linked = true, alreadyOpen = false))
        assertFalse(WorktreePlanPresentation.shouldReopenExisting(linked = true, alreadyOpen = true))
        assertFalse(WorktreePlanPresentation.shouldReopenExisting(linked = false, alreadyOpen = false))
    }
}
