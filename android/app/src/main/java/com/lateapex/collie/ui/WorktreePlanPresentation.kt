package com.lateapex.collie.ui

import com.lateapex.collie.network.WorktreePlanResponse

/** Converts the server's advisory plan into a displayable target without inventing a path. */
internal object WorktreePlanPresentation {
    enum class Refusal { STALE_SCOPE, NOT_LEAD, PLAN_REFUSED, INVALID_BRANCH, FORBIDDEN_PARENT, TARGET_EXISTS, TARGET_UNAVAILABLE }

    sealed interface Result {
        data class Ready(val targetPath: String, val baseLabel: String) : Result
        data class Refused(val reason: Refusal, val detail: String? = null) : Result
    }

    fun evaluate(
        plan: WorktreePlanResponse,
        requestedParent: String?,
        requestedBaseRef: String?,
        ownerStillCurrent: Boolean,
        leadLocal: Boolean,
    ): Result {
        if (!ownerStillCurrent) return Result.Refused(Refusal.STALE_SCOPE)
        if (!leadLocal) return Result.Refused(Refusal.NOT_LEAD)
        if (!plan.ok) return Result.Refused(Refusal.PLAN_REFUSED, plan.error)
        val repoRoot = plan.repoRoot ?: return Result.Refused(Refusal.PLAN_REFUSED)
        if (!repoRoot.startsWith("/") || repoRoot.any(Char::isISOControl)) return Result.Refused(Refusal.PLAN_REFUSED)
        if (plan.branchValid != true) return Result.Refused(Refusal.INVALID_BRANCH)
        val target = if (requestedParent != null) {
            val parent = plan.parentTarget
                ?: return Result.Refused(Refusal.TARGET_UNAVAILABLE)
            if (!parent.ok) return Result.Refused(Refusal.FORBIDDEN_PARENT, parent.error)
            parent.path
        } else {
            val default = plan.defaultTarget
                ?: return Result.Refused(Refusal.TARGET_UNAVAILABLE)
            if (default.exists) return Result.Refused(Refusal.TARGET_EXISTS)
            default.path
        }
        if (target.isNullOrBlank()) return Result.Refused(Refusal.TARGET_UNAVAILABLE)
        val baseLabel = if (requestedBaseRef == null) plan.defaultBranch else requestedBaseRef
        if (baseLabel.isNullOrBlank()) return Result.Refused(Refusal.PLAN_REFUSED)
        return Result.Ready(target, baseLabel)
    }

    /** An existing linked checkout is reopened in place; it is never silently branched again. */
    fun shouldReopenExisting(linked: Boolean, alreadyOpen: Boolean): Boolean = linked && !alreadyOpen
}
