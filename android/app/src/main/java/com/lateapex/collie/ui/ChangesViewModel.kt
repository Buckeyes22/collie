package com.lateapex.collie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ChangeCommitState
import com.lateapex.collie.network.ChangeCommitInfo
import com.lateapex.collie.network.ChangeCommitHistoryPage
import com.lateapex.collie.network.ChangeCommitHistoryState
import com.lateapex.collie.network.ChangeDiffAnswer
import com.lateapex.collie.network.ChangeDiffState
import com.lateapex.collie.network.ChangedFile
import com.lateapex.collie.network.ChangesListAnswer
import com.lateapex.collie.network.ChangesLookup
import com.lateapex.collie.network.ChangesTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ChangesScreenState(
    val loading: Boolean = false,
    val list: ChangesListAnswer? = null,
    val lookup: ChangesLookup = ChangesLookup(),
    val selection: ChangesSelection? = null,
    val history: ChangeHistoryView? = null,
    val detail: ChangeDetail? = null,
    val error: ChangesMessage? = null,
)

internal data class ChangesSelection(val repo: String, val path: String?, val commit: Boolean, val commitHash: String? = null)
internal data class ChangeHistoryView(val repo: String, val pages: List<ChangeCommitHistoryPage>, val pageIndex: Int = 0) {
    val page: ChangeCommitHistoryPage get() = pages[pageIndex]
}
internal data class ChangeDetail(
    val title: String,
    val subtitle: String,
    val body: String,
    val binary: Boolean = false,
    val isDiff: Boolean = false,
    val commitFiles: List<ChangedFile> = emptyList(),
    val commitFilesTruncated: Boolean = false,
)
internal enum class ChangesMessage { NOT_CONNECTED, UNSUPPORTED, UNAUTHORIZED, UNKNOWN_REPO, UNKNOWN_PATH, NO_COMMIT, UNAVAILABLE, MALFORMED }

internal class ChangesViewModel(
    application: Application,
    private val repository: CollieRepository,
    private val target: ChangesTarget,
    private val scope: Scope,
) : AndroidViewModel(application) {
    private val preferences = NativePreferences(application)
    private companion object { const val HISTORY_PAGE_SIZE = 30; const val MAX_HISTORY_PAGES = 334 }
    private var lookup = ChangesLookup(preferences.changesDepth, preferences.changesNested)
    private val mutableState = MutableStateFlow(ChangesScreenState(lookup = lookup))
    val state: StateFlow<ChangesScreenState> = mutableState.asStateFlow()
    @Volatile private var generation = 0L
    private val authorityRegistration: AutoCloseable

    init {
        repository.selectSubject(scope, target.id)
        authorityRegistration = repository.onReadContextInvalidated { reason ->
            generation++
            val message = if (reason == AuthorityInvalidation.ACCESS_FROZEN || reason == AuthorityInvalidation.PAIRING_RETIRED) {
                ChangesMessage.UNAUTHORIZED
            } else ChangesMessage.UNAVAILABLE
            mutableState.value = ChangesScreenState(lookup = lookup, error = message)
        }
        refresh()
    }

    fun refresh() {
        val request = ++generation
        val context = repository.readContext(scope, target.id)
        if (context == null) {
            mutableState.value = mutableState.value.copy(loading = false, error = ChangesMessage.NOT_CONNECTED)
            return
        }
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = repository.changes(target, scope, lookup, expectedContext = context)
            if (request != generation) return@launch
            if (!repository.isCurrent(context)) return@launch
            when (result) {
                is ApiResult.Success -> mutableState.value = ChangesScreenState(list = result.value, lookup = lookup)
                is ApiResult.Failure -> mutableState.value = ChangesScreenState(lookup = lookup, error = failureMessage(result.error))
                is ApiResult.NotModified -> mutableState.value = ChangesScreenState(lookup = lookup, error = ChangesMessage.MALFORMED)
            }
        }
    }

    fun setDepth(depth: Int) {
        if (depth !in 1..4 || depth == lookup.depth) return
        updateLookup(ChangesLookup(depth, lookup.nested))
    }

    fun setNested(nested: Boolean) {
        if (nested == lookup.nested) return
        updateLookup(ChangesLookup(lookup.depth, nested))
    }

    private fun updateLookup(value: ChangesLookup) {
        lookup = value
        preferences.changesDepth = value.depth
        preferences.changesNested = value.nested
        generation++
        mutableState.value = ChangesScreenState(loading = true, lookup = lookup)
        refresh()
    }

    fun openFile(repo: String, file: ChangedFile) {
        val selection = ChangesSelection(repo, file.path, commit = false)
        loadDiff(selection)
    }

    fun openLastCommit(repo: String) = loadCommit(repo, null)

    fun openHistory(repo: String) {
        val request = ++generation
        val context = repository.readContext(scope, target.id)
        if (context == null) {
            mutableState.value = mutableState.value.copy(loading = false, history = null, error = ChangesMessage.NOT_CONNECTED)
            return
        }
        mutableState.value = mutableState.value.copy(loading = true, selection = null, detail = null, history = null, error = null)
        viewModelScope.launch {
            val result = repository.changeCommitHistory(target, scope, lookup, repo, head = null, offset = 0, limit = HISTORY_PAGE_SIZE, expectedContext = context)
            if (request != generation) return@launch
            if (!repository.isCurrent(context)) return@launch
            when (result) {
                is ApiResult.Success -> when (val page = result.value.state) {
                    is ChangeCommitHistoryState.Unavailable -> mutableState.value = mutableState.value.copy(loading = false, error = messageForReason(page.reason))
                    is ChangeCommitHistoryState.Available -> mutableState.value = mutableState.value.copy(
                        loading = false, history = ChangeHistoryView(repo, listOf(page.page)), error = null,
                    )
                }
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = failureMessage(result.error))
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(loading = false, error = ChangesMessage.MALFORMED)
            }
        }
    }

    fun nextHistoryPage() {
        val current = mutableState.value.history ?: return
        val nextOffset = current.page.nextOffset ?: return
        if (current.pages.any { it.offset == nextOffset }) {
            mutableState.value = mutableState.value.copy(history = current.copy(pageIndex = current.pages.indexOfFirst { it.offset == nextOffset }))
            return
        }
        val request = ++generation
        val context = repository.readContext(scope, target.id)
        if (context == null) {
            mutableState.value = mutableState.value.copy(loading = false, error = ChangesMessage.NOT_CONNECTED)
            return
        }
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = repository.changeCommitHistory(target, scope, lookup, current.repo, current.page.headHash, nextOffset, current.page.limit, expectedContext = context)
            if (request != generation) return@launch
            if (!repository.isCurrent(context)) {
                mutableState.value = ChangesScreenState(lookup = lookup, error = ChangesMessage.UNAVAILABLE)
                return@launch
            }
            when (result) {
                is ApiResult.Success -> when (val state = result.value.state) {
                    is ChangeCommitHistoryState.Unavailable -> mutableState.value = mutableState.value.copy(loading = false, error = messageForReason(state.reason))
                    is ChangeCommitHistoryState.Available -> {
                        val page = state.page
                        val known = current.pages.flatMap { it.commits }.mapTo(mutableSetOf()) { it.hash }
                        if (page.offset != nextOffset || page.headHash != current.page.headHash || page.commits.any { it.hash in known }) {
                            mutableState.value = mutableState.value.copy(loading = false, error = ChangesMessage.MALFORMED)
                        } else {
                            val pages = (current.pages + page).takeLast(MAX_HISTORY_PAGES)
                            mutableState.value = mutableState.value.copy(loading = false, history = ChangeHistoryView(current.repo, pages, pages.lastIndex), error = null)
                        }
                    }
                }
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = failureMessage(result.error))
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(loading = false, error = ChangesMessage.MALFORMED)
            }
        }
    }

    fun previousHistoryPage() {
        val current = mutableState.value.history ?: return
        if (current.pageIndex > 0) mutableState.value = mutableState.value.copy(history = current.copy(pageIndex = current.pageIndex - 1), error = null)
    }

    fun openHistoryCommit(repo: String, commit: ChangeCommitInfo) = loadCommit(repo, commit.hash)

    private fun loadCommit(repo: String, commitHash: String?) {
        val selection = ChangesSelection(repo, null, commit = true, commitHash = commitHash)
        val request = ++generation
        val context = repository.readContext(scope, target.id)
        if (context == null) {
            mutableState.value = mutableState.value.copy(loading = false, selection = selection, error = ChangesMessage.NOT_CONNECTED)
            return
        }
        mutableState.value = mutableState.value.copy(loading = true, selection = selection, detail = null, error = null)
        viewModelScope.launch {
            val result = repository.changeCommit(target, scope, lookup, repo, commitHash, expectedContext = context)
            if (request != generation) return@launch
            if (!repository.isCurrent(context)) return@launch
            when (result) {
                is ApiResult.Success -> when (val state = result.value.state) {
                    is ChangeCommitState.Unavailable -> mutableState.value = mutableState.value.copy(loading = false, error = messageForReason(state.reason))
                    is ChangeCommitState.Available -> {
                        val body = buildString {
                            append(state.commit.subject)
                            append("\n\n").append(state.commit.shortHash)
                            append(" · ").append(state.commit.author)
                            append(" · ").append(state.commit.timeSeconds)
                        }
                        mutableState.value = mutableState.value.copy(
                            loading = false, selection = selection,
                            detail = ChangeDetail(state.name, getApplication<Application>().getString(if (commitHash == null) com.lateapex.collie.R.string.changes_last_commit else com.lateapex.collie.R.string.changes_selected_commit), body,
                                commitFiles = state.files, commitFilesTruncated = state.truncated),
                        )
                    }
                }
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = failureMessage(result.error))
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(loading = false, error = ChangesMessage.MALFORMED)
            }
        }
    }

    fun backToHistory() {
        generation++
        mutableState.value = mutableState.value.copy(loading = false, selection = null, detail = null, error = null)
    }

    fun openCommitFile(repo: String, file: ChangedFile) {
        val selection = ChangesSelection(repo, file.path, commit = true, commitHash = mutableState.value.selection?.commitHash)
        val request = ++generation
        val context = repository.readContext(scope, target.id)
        if (context == null) {
            mutableState.value = mutableState.value.copy(loading = false, selection = selection, error = ChangesMessage.NOT_CONNECTED)
            return
        }
        mutableState.value = mutableState.value.copy(loading = true, selection = selection, error = null)
        viewModelScope.launch {
            val result = repository.changeCommitDiff(target, scope, lookup, repo, file.path, selection.commitHash, expectedContext = context)
            if (request != generation) return@launch
            if (!repository.isCurrent(context)) return@launch
            when (result) {
                is ApiResult.Success -> applyDiff(
                    ChangeDiffAnswer(result.value.target, result.value.workspaceLabel, result.value.state),
                    selection,
                )
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = failureMessage(result.error))
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(loading = false, error = ChangesMessage.MALFORMED)
            }
        }
    }

    fun backToList() {
        generation++
        mutableState.value = mutableState.value.copy(loading = false, selection = null, detail = null, history = null, error = null)
    }

    private fun loadDiff(selection: ChangesSelection) {
        val path = selection.path ?: return
        val request = ++generation
        val context = repository.readContext(scope, target.id)
        if (context == null) {
            mutableState.value = mutableState.value.copy(loading = false, selection = selection, error = ChangesMessage.NOT_CONNECTED)
            return
        }
        mutableState.value = mutableState.value.copy(loading = true, selection = selection, detail = null, error = null)
        viewModelScope.launch {
            val result = repository.changeDiff(target, scope, lookup, selection.repo, path, expectedContext = context)
            if (request != generation) return@launch
            if (!repository.isCurrent(context)) return@launch
            when (result) {
                is ApiResult.Success -> applyDiff(result.value, selection)
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = failureMessage(result.error))
                is ApiResult.NotModified -> mutableState.value = mutableState.value.copy(loading = false, error = ChangesMessage.MALFORMED)
            }
        }
    }

    private fun applyDiff(answer: ChangeDiffAnswer, selection: ChangesSelection) {
        val state = answer.state
        if (state is ChangeDiffState.Unavailable) {
            mutableState.value = mutableState.value.copy(loading = false, error = messageForReason(state.reason))
            return
        }
        state as ChangeDiffState.Available
        val body = when {
            state.binary -> getApplication<Application>().getString(com.lateapex.collie.R.string.changes_binary_file)
            state.directory -> getApplication<Application>().getString(com.lateapex.collie.R.string.changes_directory)
            state.diff.isEmpty() -> getApplication<Application>().getString(com.lateapex.collie.R.string.changes_empty_diff)
            else -> state.diff
        }
        mutableState.value = mutableState.value.copy(
            loading = false,
            selection = selection,
            detail = ChangeDetail(
                state.path,
                state.status.wire + if (state.truncated) " · " + getApplication<Application>().getString(com.lateapex.collie.R.string.changes_truncated) else "",
                body,
                binary = state.binary || state.directory || state.diff.isEmpty(),
                isDiff = !state.binary && !state.directory && state.diff.isNotEmpty(),
            ),
            error = null,
        )
    }

    private fun failureMessage(error: ApiFailure): ChangesMessage = when (error) {
        is ApiFailure.Http -> when {
            error.status == 404 -> ChangesMessage.UNSUPPORTED
            error.status == 401 || error.status == 403 -> ChangesMessage.UNAUTHORIZED
            else -> ChangesMessage.UNAVAILABLE
        }
        is ApiFailure.Protocol -> ChangesMessage.MALFORMED
        else -> ChangesMessage.UNAVAILABLE
    }

    private fun messageForReason(reason: String): ChangesMessage = when (reason) {
        "unknown-repo" -> ChangesMessage.UNKNOWN_REPO
        "unknown-path" -> ChangesMessage.UNKNOWN_PATH
        "no-commit" -> ChangesMessage.NO_COMMIT
        else -> ChangesMessage.UNAVAILABLE
    }

    override fun onCleared() {
        authorityRegistration.close()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val target: ChangesTarget,
        private val scope: Scope,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = (application as CollieApplication).container.repository
            return ChangesViewModel(application, repository, target, scope) as T
        }
    }
}
