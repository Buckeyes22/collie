package com.lateapex.collie.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A listed pane or workspace. IDs stay opaque and are always path-segment encoded by the API. */
sealed interface ChangesTarget {
    val id: String
    data class Pane(override val id: String) : ChangesTarget
    data class Workspace(override val id: String) : ChangesTarget
}

/** Matches the server's bounded lookup controls. The bridge clamps depth; clients keep it in range. */
data class ChangesLookup(val depth: Int = DEFAULT_CHANGES_DEPTH, val nested: Boolean = true) {
    init { require(depth in 1..MAX_CHANGES_DEPTH) }
}

enum class ChangeStatus(val wire: String) {
    MODIFIED("M"), ADDED("A"), DELETED("D"), RENAMED("R"), UNTRACKED("?");

    companion object {
        fun fromWire(value: String): ChangeStatus? = entries.firstOrNull { it.wire == value }
    }
}

data class ChangedFile(
    val path: String,
    val oldPath: String?,
    val status: ChangeStatus,
    val added: Long,
    val removed: Long,
    val binary: Boolean,
)

data class ChangedRepo(val relPath: String, val name: String, val files: List<ChangedFile>)
data class CleanRepo(val relPath: String, val name: String)

data class ChangeCommitInfo(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val timeSeconds: Long,
)

data class ChangeCommitHistoryPage(
    val repo: String,
    val name: String,
    val headHash: String,
    val offset: Int,
    val limit: Int,
    val commits: List<ChangeCommitInfo>,
    val nextOffset: Int?,
    val truncated: Boolean,
)

sealed interface ChangeCommitHistoryState {
    data class Unavailable(val reason: String) : ChangeCommitHistoryState
    data class Available(val page: ChangeCommitHistoryPage) : ChangeCommitHistoryState
}

data class ChangeCommitHistoryAnswer(val target: ChangesTarget, val workspaceLabel: String?, val state: ChangeCommitHistoryState)

sealed interface ChangesListState {
    data class Unavailable(val reason: String) : ChangesListState
    data class Available(
        val root: String,
        val repos: List<ChangedRepo>,
        val truncated: Boolean,
        val depthLimited: Boolean,
        val clean: List<CleanRepo>,
    ) : ChangesListState
}

data class ChangesListAnswer(
    val target: ChangesTarget,
    val workspaceLabel: String?,
    val paneRepo: String?,
    val state: ChangesListState,
)

sealed interface ChangeDiffState {
    data class Unavailable(val reason: String) : ChangeDiffState
    data class Available(
        val repo: String,
        val path: String,
        val oldPath: String?,
        val status: ChangeStatus,
        val binary: Boolean,
        val directory: Boolean,
        val truncated: Boolean,
        val diff: String,
        val commitHash: String? = null,
    ) : ChangeDiffState
}

data class ChangeDiffAnswer(val target: ChangesTarget, val workspaceLabel: String?, val state: ChangeDiffState)

sealed interface ChangeCommitState {
    data class Unavailable(val reason: String) : ChangeCommitState
    data class Available(
        val repo: String,
        val name: String,
        val commit: ChangeCommitInfo,
        val files: List<ChangedFile>,
        val truncated: Boolean,
    ) : ChangeCommitState
}

data class ChangeCommitAnswer(val target: ChangesTarget, val workspaceLabel: String?, val state: ChangeCommitState)

data class ChangeCommitDiffAnswer(val target: ChangesTarget, val workspaceLabel: String?, val state: ChangeDiffState)

internal object ChangesWire {
    private const val MAX_REPOS = 20
    private const val MAX_FILES_PER_REPO = 1_000
    private const val MAX_PATH_CHARS = 8_192
    private const val MAX_LABEL_CHARS = 512
    private const val MAX_SUBJECT_CHARS = 500
    private const val MAX_AUTHOR_CHARS = 1_024
    private const val MAX_DIFF_BYTES = 512 * 1024
    private const val MAX_DIFF_LINES = 5_000
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val objectId = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")

    fun list(element: JsonElement, target: ChangesTarget): ChangesListAnswer? {
        val obj = element as? JsonObject ?: return null
        val metadata = targetMetadata(obj, target) ?: return null
        val available = obj.bool("available") ?: return null
        val state = if (!available) {
            val reason = obj.string("reason", 64)?.takeIf { it in LIST_UNAVAILABLE } ?: return null
            ChangesListState.Unavailable(reason)
        } else {
            val root = obj.string("root", MAX_PATH_CHARS)?.takeIf(String::isNotBlank) ?: return null
            val repos = obj.array("repos", MAX_REPOS)?.map { parseRepo(it) ?: return null } ?: return null
            val truncated = obj.bool("truncated") ?: return null
            val depthLimited = obj.optionalBool("depthLimited") ?: return null
            val clean = if ("clean" in obj) obj.array("clean", MAX_REPOS)?.map { parseCleanRepo(it) ?: return null } ?: return null else emptyList()
            ChangesListState.Available(root, repos, truncated, depthLimited, clean)
        }
        return ChangesListAnswer(target, metadata.workspaceLabel, metadata.paneRepo, state)
    }

    fun diff(element: JsonElement, target: ChangesTarget, repo: String, path: String, commit: Boolean, expectedHash: String? = null): ChangeDiffAnswer? {
        val obj = element as? JsonObject ?: return null
        val metadata = targetMetadata(obj, target) ?: return null
        val available = obj.bool("available") ?: return null
        val state = if (!available) {
            val reasons = if (commit) COMMIT_DIFF_UNAVAILABLE else DIFF_UNAVAILABLE
            val reason = obj.string("reason", 64)?.takeIf { it in reasons } ?: return null
            ChangeDiffState.Unavailable(reason)
        } else {
            val returnedRepo = obj.string("repo", MAX_PATH_CHARS)?.takeIf { it == repo } ?: return null
            val returnedPath = obj.string("path", MAX_PATH_CHARS)?.takeIf { it == path } ?: return null
            if (!obj.validOptionalString("oldPath", MAX_PATH_CHARS)) return null
            val oldPath = obj.optionalString("oldPath", MAX_PATH_CHARS)
            val status = ChangeStatus.fromWire(obj.string("status", 1) ?: return null) ?: return null
            val binary = obj.bool("binary") ?: return null
            val directory = obj.bool("directory") ?: return null
            val truncated = obj.bool("truncated") ?: return null
            val diff = obj.string("diff", MAX_DIFF_BYTES)?.takeIf { validDiff(it) } ?: return null
            val hash = if (commit) obj.string("hash", 64)?.takeIf { objectId.matches(it) && (expectedHash == null || it == expectedHash) } ?: return null else null
            ChangeDiffState.Available(returnedRepo, returnedPath, oldPath, status, binary, directory, truncated, diff, hash)
        }
        return ChangeDiffAnswer(target, metadata.workspaceLabel, state)
    }

    fun commit(element: JsonElement, target: ChangesTarget, repo: String, expectedHash: String? = null): ChangeCommitAnswer? {
        val obj = element as? JsonObject ?: return null
        val metadata = targetMetadata(obj, target) ?: return null
        val available = obj.bool("available") ?: return null
        val state = if (!available) {
            val reason = obj.string("reason", 64)?.takeIf { it in COMMIT_UNAVAILABLE } ?: return null
            ChangeCommitState.Unavailable(reason)
        } else {
            val returnedRepo = obj.string("repo", MAX_PATH_CHARS)?.takeIf { it == repo } ?: return null
            val name = obj.string("name", MAX_LABEL_CHARS)?.takeIf(String::isNotBlank) ?: return null
            val commit = parseCommit(obj.obj("commit") ?: return null) ?: return null
            if (expectedHash != null && commit.hash != expectedHash) return null
            val files = obj.array("files", MAX_FILES_PER_REPO)?.map { parseFile(it) ?: return null } ?: return null
            val truncated = obj.bool("truncated") ?: return null
            ChangeCommitState.Available(returnedRepo, name, commit, files, truncated)
        }
        return ChangeCommitAnswer(target, metadata.workspaceLabel, state)
    }

    fun history(
        element: JsonElement,
        target: ChangesTarget,
        repo: String,
        requestedHead: String?,
        requestedOffset: Int,
        requestedLimit: Int,
    ): ChangeCommitHistoryAnswer? {
        val obj = element as? JsonObject ?: return null
        val metadata = targetMetadata(obj, target) ?: return null
        val available = obj.bool("available") ?: return null
        val state = if (!available) {
            val reason = obj.string("reason", 64)?.takeIf { it in HISTORY_UNAVAILABLE } ?: return null
            ChangeCommitHistoryState.Unavailable(reason)
        } else {
            val returnedRepo = obj.string("repo", MAX_PATH_CHARS)?.takeIf { it == repo } ?: return null
            val name = obj.string("name", MAX_LABEL_CHARS)?.takeIf(String::isNotBlank) ?: return null
            val headHash = obj.string("headHash", 64)?.takeIf { objectId.matches(it) } ?: return null
            if (requestedHead != null && headHash != requestedHead) return null
            val offset = obj.number("offset")?.takeIf { it in 0L..10_000L }?.toInt() ?: return null
            if (offset != requestedOffset) return null
            val limit = obj.number("limit")?.takeIf { it in 1L..50L }?.toInt() ?: return null
            if (limit != requestedLimit) return null
            val commits = obj.array("commits", 50)?.map { parseCommit(it as? JsonObject ?: return null) ?: return null } ?: return null
            if (commits.isEmpty() || commits.size > limit || offset + commits.size > 10_000 ||
                commits.map { it.hash }.toSet().size != commits.size) return null
            if (requestedHead == null && offset == 0 && commits.first().hash != headHash) return null
            val nextOffset = when (val next = obj["nextOffset"]) {
                null, kotlinx.serialization.json.JsonNull -> null
                else -> (next as? JsonPrimitive)?.longOrNull?.takeIf { it in (offset + commits.size).toLong()..10_000L }?.toInt() ?: return null
            }
            val truncated = obj.bool("truncated") ?: return null
            if (nextOffset != null && nextOffset != offset + commits.size) return null
            if (nextOffset != null && (commits.size != limit || nextOffset >= 10_000)) return null
            if (truncated && (nextOffset != null || offset + commits.size != 10_000)) return null
            ChangeCommitHistoryState.Available(ChangeCommitHistoryPage(returnedRepo, name, headHash, offset, limit, commits, nextOffset, truncated))
        }
        return ChangeCommitHistoryAnswer(target, metadata.workspaceLabel, state)
    }

    private data class Metadata(val workspaceLabel: String?, val paneRepo: String?)

    private fun targetMetadata(obj: JsonObject, target: ChangesTarget): Metadata? {
        if (!obj.validOptionalString("workspaceLabel", MAX_LABEL_CHARS) || !obj.validOptionalString("paneRepo", MAX_PATH_CHARS)) return null
        val workspaceLabel = obj.optionalString("workspaceLabel", MAX_LABEL_CHARS)
        val paneRepo = obj.optionalString("paneRepo", MAX_PATH_CHARS)
        return when (target) {
            is ChangesTarget.Pane -> {
                if (obj.string("paneId", MAX_PATH_CHARS) != target.id) return null
                if ("workspaceId" in obj && obj.string("workspaceId", MAX_PATH_CHARS) == null) return null
                Metadata(workspaceLabel, paneRepo)
            }
            is ChangesTarget.Workspace -> {
                if (obj.string("workspaceId", MAX_PATH_CHARS) != target.id) return null
                if ("paneId" in obj) return null
                if (paneRepo != null) return null
                Metadata(workspaceLabel, null)
            }
        }
    }

    private fun parseRepo(element: JsonElement): ChangedRepo? {
        val obj = element as? JsonObject ?: return null
        val relPath = obj.string("relPath", MAX_PATH_CHARS)?.takeIf(String::isNotBlank) ?: return null
        val name = obj.string("name", MAX_LABEL_CHARS)?.takeIf(String::isNotBlank) ?: return null
        val files = obj.array("files", MAX_FILES_PER_REPO)?.map { parseFile(it) ?: return null } ?: return null
        return ChangedRepo(relPath, name, files)
    }

    private fun parseCleanRepo(element: JsonElement): CleanRepo? {
        val obj = element as? JsonObject ?: return null
        val relPath = obj.string("relPath", MAX_PATH_CHARS)?.takeIf(String::isNotBlank) ?: return null
        val name = obj.string("name", MAX_LABEL_CHARS)?.takeIf(String::isNotBlank) ?: return null
        return CleanRepo(relPath, name)
    }

    private fun parseFile(element: JsonElement): ChangedFile? {
        val obj = element as? JsonObject ?: return null
        val path = obj.string("path", MAX_PATH_CHARS)?.takeIf(String::isNotBlank) ?: return null
        if (!obj.validOptionalString("oldPath", MAX_PATH_CHARS)) return null
        val oldPath = obj.optionalString("oldPath", MAX_PATH_CHARS)
        val status = ChangeStatus.fromWire(obj.string("status", 1) ?: return null) ?: return null
        val added = obj.number("added")?.takeIf { it in 0L..MAX_SAFE_INTEGER } ?: return null
        val removed = obj.number("removed")?.takeIf { it in 0L..MAX_SAFE_INTEGER } ?: return null
        val binary = obj.bool("binary") ?: return null
        return ChangedFile(path, oldPath, status, added, removed, binary)
    }

    private fun parseCommit(obj: JsonObject): ChangeCommitInfo? {
        val hash = obj.string("hash", 64)?.takeIf { objectId.matches(it) } ?: return null
        val shortHash = obj.string("shortHash", 64)?.takeIf { it.length in 7..hash.length && hash.startsWith(it) } ?: return null
        val subject = obj.string("subject", MAX_SUBJECT_CHARS) ?: return null
        val author = obj.string("author", MAX_AUTHOR_CHARS) ?: return null
        val time = obj.number("time")?.takeIf { it in 0L..MAX_SAFE_INTEGER } ?: return null
        return ChangeCommitInfo(hash, shortHash, subject, author, time)
    }

    private fun validDiff(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size <= MAX_DIFF_BYTES && value.count { it == '\n' } <= MAX_DIFF_LINES

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.optionalBool(key: String): Boolean? = if (key !in this) false else bool(key)
    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.string(key: String, max: Int): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= max }
    private fun JsonObject.optionalString(key: String, max: Int): String? = if (key !in this || this[key] == kotlinx.serialization.json.JsonNull) null else string(key, max)
    private fun JsonObject.validOptionalString(key: String, max: Int): Boolean =
        key !in this || this[key] == kotlinx.serialization.json.JsonNull || string(key, max) != null
    private fun JsonObject.array(key: String, max: Int): List<JsonElement>? = (this[key] as? JsonArray)?.takeIf { it.size <= max }

    private val LIST_UNAVAILABLE = setOf("no-pane", "no-workspace", "no-folder", "no-git")
    private val DIFF_UNAVAILABLE = LIST_UNAVAILABLE + setOf("unknown-repo", "unknown-path")
    private val COMMIT_UNAVAILABLE = LIST_UNAVAILABLE + setOf("unknown-repo", "no-commit", "stale-history")
    private val COMMIT_DIFF_UNAVAILABLE = DIFF_UNAVAILABLE + setOf("no-commit", "stale-history")
    private val HISTORY_UNAVAILABLE = LIST_UNAVAILABLE + setOf("unknown-repo", "no-commit", "stale-history")
}

const val DEFAULT_CHANGES_DEPTH = 2
const val MAX_CHANGES_DEPTH = 4
