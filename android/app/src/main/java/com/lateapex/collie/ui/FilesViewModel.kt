package com.lateapex.collie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.FilesAnswer
import com.lateapex.collie.network.FilesTarget
import com.lateapex.collie.network.RelativeFilePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class FilesMessage { NOT_CONNECTED, UNSUPPORTED, UNAUTHORIZED, PROXY_REQUIRED, NOT_FOUND, NO_FOLDER, TOO_LARGE, NOT_IMAGE, UNAVAILABLE, MALFORMED }
internal data class FilesScreenState(
    val loading: Boolean = true,
    val answer: FilesAnswer? = null,
    val path: String? = null,
    val directory: String = "",
    val ignored: Boolean = false,
    val error: FilesMessage? = null,
    val acceptedContext: ScopedReadContext? = null,
)

internal class FilesViewModel(
    application: Application,
    private val repository: CollieRepository,
    private val target: FilesTarget,
    private val scope: Scope,
    initialDirectory: String,
    initialPath: String?,
    initialIgnored: Boolean,
    initialReference: String? = null,
    private val handoffRequired: Boolean = false,
    initialContext: ScopedReadContext? = null,
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(FilesScreenState(directory = initialDirectory, path = initialPath, ignored = initialIgnored))
    val state: StateFlow<FilesScreenState> = mutableState.asStateFlow()
    private var generation = 0L
    private var navigationContext = initialContext
    private val invalidation = repository.onReadContextInvalidated { reason ->
        if (reason in setOf(
                AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.PAIRING_RETIRED,
                AuthorityInvalidation.DISCONNECTED, AuthorityInvalidation.SAVED_COPY,
                AuthorityInvalidation.SCOPE_CHANGED, AuthorityInvalidation.LEAD_CHANGED,
            ) || (reason == AuthorityInvalidation.ACCESS_FROZEN && repository.accessFailure is ApiFailure.Http)
        ) {
            generation++
            navigationContext = null
            val previous = mutableState.value
            val currentFailure = repository.accessFailure
            val refusal = if (reason == AuthorityInvalidation.ACCESS_FROZEN && currentFailure != null) failure(currentFailure) else when (reason) {
                AuthorityInvalidation.PAIRING_RETIRED -> FilesMessage.UNAUTHORIZED
                AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.DISCONNECTED,
                AuthorityInvalidation.SCOPE_CHANGED, AuthorityInvalidation.LEAD_CHANGED,
                AuthorityInvalidation.SAVED_COPY -> FilesMessage.NOT_CONNECTED
                AuthorityInvalidation.ACCESS_FROZEN -> FilesMessage.UNAVAILABLE
            }
            mutableState.value = FilesScreenState(
                loading = false,
                path = previous.path,
                directory = previous.directory,
                ignored = previous.ignored,
                error = refusal,
            )
        }
    }

    init {
        if (!handoffRequired) repository.selectSubject(scope, target.id)
        val path = initialPath?.let { RelativeFilePath.parse(it) }
        when {
            initialReference != null -> openReference(initialReference)
            initialPath != null && path == null -> mutableState.value = FilesScreenState(loading = false, error = FilesMessage.NOT_FOUND)
            path != null -> read(path)
            RelativeFilePath.parse(initialDirectory, allowRoot = true) == null -> mutableState.value = FilesScreenState(loading = false, error = FilesMessage.NOT_FOUND)
            else -> loadDirectory(initialDirectory, initialIgnored)
        }
    }

    fun refresh() {
        val current = mutableState.value
        val path = current.path?.let(RelativeFilePath::parse)
        if (path != null) read(path) else loadDirectory(current.directory, current.ignored)
    }

    fun openDirectory(path: String) {
        val relative = RelativeFilePath.parse(path, allowRoot = true) ?: run {
            generation++
            mutableState.value = FilesScreenState(loading = false, directory = mutableState.value.directory, ignored = mutableState.value.ignored, error = FilesMessage.NOT_FOUND)
            return
        }
        loadDirectory(relative.value, mutableState.value.ignored)
    }

    fun openFile(path: String) {
        val relative = RelativeFilePath.parse(path) ?: run {
            generation++
            mutableState.value = FilesScreenState(loading = false, directory = mutableState.value.directory, ignored = mutableState.value.ignored, error = FilesMessage.NOT_FOUND)
            return
        }
        read(relative)
    }

    /** A printed/document link is classified by the server's scoped read routes, never extension. */
    fun openReference(path: String) {
        val relative = RelativeFilePath.parse(path) ?: return
        val parent = relative.value.substringBeforeLast('/', "")
        request(FilesScreenState(loading = true, directory = parent, ignored = mutableState.value.ignored)) { context ->
            when (val directory = repository.files(target, scope, dir = relative, ignored = mutableState.value.ignored, expectedContext = context)) {
                is ApiResult.Success -> if (directory.value is FilesAnswer.Listing) directory else repository.files(target, scope, path = relative, expectedContext = context)
                else -> repository.files(target, scope, path = relative, expectedContext = context)
            }
        }
    }

    fun backToDirectory() {
        val current = mutableState.value
        val parent = current.path?.substringBeforeLast('/', "") ?: current.directory.substringBeforeLast('/', "")
        loadDirectory(parent, current.ignored)
    }

    fun setIgnored(value: Boolean) {
        if (mutableState.value.ignored == value) return
        loadDirectory(mutableState.value.directory, value)
    }

    private fun loadDirectory(directory: String, ignored: Boolean) {
        val relative = RelativeFilePath.parse(directory, allowRoot = true) ?: run {
            generation++
            mutableState.value = FilesScreenState(loading = false, error = FilesMessage.NOT_FOUND)
            return
        }
        request(FilesScreenState(loading = true, directory = relative.value, ignored = ignored)) { context ->
            repository.files(target, scope, dir = relative, ignored = ignored, expectedContext = context)
        }
    }

    private fun read(path: RelativeFilePath) {
        val parent = path.value.substringBeforeLast('/', "")
        request(FilesScreenState(loading = true, path = path.value, directory = parent, ignored = mutableState.value.ignored)) { context ->
            repository.files(target, scope, path = path, expectedContext = context)
        }
    }

    private fun request(loading: FilesScreenState, call: suspend (ScopedReadContext) -> ApiResult<FilesAnswer>) {
        val request = ++generation
        val context = if (handoffRequired) navigationContext?.takeIf {
            it.key.scope == scope && it.key.subjectIdentity == target.id && repository.isCurrent(it)
        } else repository.readContext(scope, target.id)
        if (context == null) {
            if (handoffRequired) navigationContext = null
            mutableState.value = loading.copy(loading = false, error = FilesMessage.NOT_CONNECTED)
            return
        }
        mutableState.value = loading.copy(error = null)
        viewModelScope.launch {
            val result = call(context)
            if (request != generation) return@launch
            if (!repository.isCurrent(context)) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val unavailable = result.value as? FilesAnswer.Unavailable
                    val error = when (unavailable?.reason) {
                        "no-folder" -> FilesMessage.NO_FOLDER
                        "no-pane", "no-workspace" -> FilesMessage.NOT_FOUND
                        else -> null
                    }
                    val accepted = result.status == 200 && result.value !is FilesAnswer.Unavailable && repository.isCurrent(context)
                    val navigation = when (val answer = result.value) {
                        is FilesAnswer.Source -> loading.copy(path = answer.path, directory = answer.path.substringBeforeLast('/', ""))
                        is FilesAnswer.Listing -> loading.copy(path = null, directory = answer.directory)
                        is FilesAnswer.Unavailable -> loading
                    }
                    mutableState.value = if (accepted) {
                        navigation.copy(loading = false, answer = result.value, error = error, acceptedContext = context)
                    } else {
                        loading.copy(loading = false, answer = null, error = error ?: FilesMessage.MALFORMED, acceptedContext = null)
                    }
                }
                is ApiResult.Failure -> mutableState.value = loading.copy(loading = false, error = failure(result.error))
                is ApiResult.NotModified -> mutableState.value = loading.copy(loading = false, error = FilesMessage.MALFORMED)
            }
        }
    }

    private fun failure(error: ApiFailure): FilesMessage = when (error) {
        is ApiFailure.Http -> when {
            error.code == "device.not_authorised" -> FilesMessage.PROXY_REQUIRED
            error.status == 404 && (error.code == "capability.unsupported" || (error.code == null && error.message != "unknown-path")) -> FilesMessage.UNSUPPORTED
            error.status == 404 -> FilesMessage.NOT_FOUND
            error.status == 401 || error.status == 403 -> FilesMessage.UNAUTHORIZED
            error.status == 413 -> FilesMessage.TOO_LARGE
            error.status == 415 -> FilesMessage.NOT_IMAGE
            else -> FilesMessage.UNAVAILABLE
        }
        is ApiFailure.Protocol -> if (error.message.contains("Malformed")) FilesMessage.MALFORMED else FilesMessage.UNAVAILABLE
        else -> FilesMessage.UNAVAILABLE
    }

    override fun onCleared() {
        navigationContext = null
        generation++
        invalidation.close()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val target: FilesTarget,
        private val scope: Scope,
        private val directory: String,
        private val path: String?,
        private val ignored: Boolean,
        private val reference: String? = null,
        private val handoffRequired: Boolean = false,
        private val handoffToken: String? = null,
        private val handoffPath: String? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = (application as CollieApplication).container.repository
            val context = if (handoffRequired) FilesReadHandoff.take(
                handoffToken, repository, target, scope, path?.takeIf { it == handoffPath && reference == null },
            ) else null
            return FilesViewModel(application, repository, target, scope, directory, path, ignored, reference, handoffRequired, context) as T
        }
    }
}
