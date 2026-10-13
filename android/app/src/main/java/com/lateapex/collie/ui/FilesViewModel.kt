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
import kotlinx.coroutines.Job

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
    private val custodyLock = Any()
    private var generation = 0L
    private var navigationContext = initialContext
    private var pageContext: ScopedReadContext? = null
    private var retirement: AutoCloseable? = null
    private var readJob: Job? = null
    private var pageRetired = false
    private val pageRetirements = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Activity-owned copies must also be cleared while lifecycle state collection is stopped. */
    fun onPageRetired(listener: () -> Unit): AutoCloseable {
        val notify = synchronized(custodyLock) { pageRetirements.add(listener); pageRetired }
        if (notify) listener()
        return AutoCloseable { pageRetirements.remove(listener) }
    }

    private fun retire(context: ScopedReadContext, reason: AuthorityInvalidation) {
        val work = synchronized(custodyLock) {
            if (pageContext !== context || !repository.isActorRetired(context)) return
            generation++
            pageContext = null
            navigationContext = null
            pageRetired = true
            val currentFailure = repository.accessFailure
            val previous = mutableState.value
            mutableState.value = FilesScreenState(loading = false, path = previous.path,
                directory = previous.directory, ignored = previous.ignored,
                error = if (reason == AuthorityInvalidation.ACCESS_FROZEN && currentFailure != null) failure(currentFailure)
                    else if (reason == AuthorityInvalidation.PAIRING_RETIRED) FilesMessage.UNAUTHORIZED else FilesMessage.NOT_CONNECTED)
            val retiring = readJob to retirement
            readJob = null
            retirement = null
            retiring
        }
        // Cancellation and main-thread view ACKs must never borrow custody/admission/store locks.
        work.first?.cancel()
        work.second?.close()
        pageRetirements.forEach { it() }
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
            synchronized(custodyLock) {
                generation++
                mutableState.value = FilesScreenState(loading = false, directory = mutableState.value.directory, ignored = mutableState.value.ignored, error = FilesMessage.NOT_FOUND)
            }
            return
        }
        loadDirectory(relative.value, mutableState.value.ignored)
    }

    fun openFile(path: String) {
        val relative = RelativeFilePath.parse(path) ?: run {
            synchronized(custodyLock) {
                generation++
                mutableState.value = FilesScreenState(loading = false, directory = mutableState.value.directory, ignored = mutableState.value.ignored, error = FilesMessage.NOT_FOUND)
            }
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
            synchronized(custodyLock) {
                generation++
                mutableState.value = FilesScreenState(loading = false, error = FilesMessage.NOT_FOUND)
            }
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
        val originalNavigation = synchronized(custodyLock) { navigationContext }
        val context = if (handoffRequired) originalNavigation?.takeIf {
            it.key.scope == scope && it.key.subjectIdentity == target.id && !repository.isActorRetired(it)
        } else repository.readContext(scope, target.id)
        if (context == null) {
            synchronized(custodyLock) {
                // A pending ACK pauses admission; it does not destroy an original live handoff.
                if (pageContext?.let { !repository.isActorRetired(it) } == true) return
                if (handoffRequired) navigationContext = null
                generation++
                mutableState.value = loading.copy(loading = false, error = FilesMessage.NOT_CONNECTED)
            }
            return
        }
        if (!repository.isCurrent(context)) return
        val (request, previous) = synchronized(custodyLock) {
            pageContext = context
            pageRetired = false
            mutableState.value = loading.copy(error = null)
            (++generation) to (readJob to retirement)
        }
        previous.first?.cancel()
        previous.second?.close()
        val subscription = repository.onFilesActorRetired(context) { retire(context, it) }
        val keep = synchronized(custodyLock) {
            if (pageContext === context && request == generation) { retirement = subscription; true } else false
        }
        if (!keep) { subscription.close(); return }
        val work = viewModelScope.launch {
            val result = call(context)
            synchronized(custodyLock) {
                if (request != generation || pageContext !== context || !repository.isCurrent(context)) return@launch
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
        val retainWork = synchronized(custodyLock) {
            if (pageContext === context && request == generation) { readJob = work; true } else false
        }
        if (!retainWork) work.cancel()
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
        val retiring = synchronized(custodyLock) {
            navigationContext = null
            pageContext = null
            generation++
            val owned = readJob to retirement
            readJob = null
            retirement = null
            mutableState.value = FilesScreenState(loading = false, error = FilesMessage.NOT_CONNECTED)
            owned
        }
        retiring.first?.cancel()
        retiring.second?.close()
        pageRetirements.clear()
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
