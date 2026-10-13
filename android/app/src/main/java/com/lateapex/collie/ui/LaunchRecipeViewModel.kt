package com.lateapex.collie.ui

import androidx.lifecycle.ViewModel
import com.lateapex.collie.network.LaunchCheckResponse
import com.lateapex.collie.network.RecipeOptionWire

/** Bounded, non-secret form state. A check is tied to exact command text and the selected actor scope. */
class LaunchRecipeViewModel : ViewModel() {
    // Unsubmitted shell text is intentionally process-local and never enters Bundle-backed state.
    var runText: String = ""
        set(value) { if (value != field) { field = value.take(MAX_RUN_CHARS); invalidateCheck() } }

    var cwd: String = ""
        set(value) { field = value.take(MAX_CWD_CHARS) }

    private var checkedText: String? = null
    private var checkedScope: String? = null
    private var checkedResponse: LaunchCheckResponse? = null

    fun recordCheck(text: String, scopeKey: String, response: LaunchCheckResponse) {
        if (text == runText) {
            checkedText = text
            checkedScope = scopeKey
            checkedResponse = response
        }
    }

    fun checkFor(text: String, scopeKey: String): LaunchCheckResponse? =
        checkedResponse?.takeIf { checkedText == text && checkedScope == scopeKey && text == runText }

    private fun invalidateCheck() {
        checkedText = null
        checkedScope = null
        checkedResponse = null
    }

    companion object {
        const val MAX_RUN_CHARS = 200
        const val MAX_CWD_CHARS = 4096
    }
}

/** Local display/input invariants mirror the server conservatively; the server remains authoritative. */
internal object LaunchRecipeModel {
    fun mayAddRecipe(adding: com.lateapex.collie.network.LaunchersAdding?): Boolean =
        adding?.let { it.adds && it.max in 1..20 && it.count in 0 until it.max && it.recipes.isNotEmpty() } == true

    fun mayAddFreeText(adding: com.lateapex.collie.network.LaunchersAdding?): Boolean =
        adding?.let { it.adds && it.freeText && it.max in 1..20 && it.count in 0 until it.max } == true

    fun mayRun(adding: com.lateapex.collie.network.LaunchersAdding?): Boolean = adding?.run == true

    fun validOneOf(harness: String?, command: String?, shell: Boolean, run: String?): Boolean =
        listOf(harness?.takeIf(String::isNotBlank), command?.takeIf(String::isNotBlank), shell.takeIf { it }?.let { "shell" }, run?.takeIf(String::isNotBlank)).count { it != null } == 1

    fun optionsAreValid(options: List<RecipeOptionWire>, selectedIds: List<String>): Boolean {
        if (selectedIds.size != selectedIds.toSet().size) return false
        val selected = selectedIds.map { id -> options.firstOrNull { it.id == id } ?: return false }
        val groups = selected.mapNotNull { it.group }
        return groups.size == groups.toSet().size
    }

    fun problemMessage(problem: String?): String? = when (problem) {
        null -> null
        "empty", "too_long", "forbidden_character" -> problem
        else -> "forbidden_character"
    }
}
