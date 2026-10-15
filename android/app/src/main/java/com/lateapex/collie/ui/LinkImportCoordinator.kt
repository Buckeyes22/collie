package com.lateapex.collie.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.CommittedConnectionSnapshot
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.OriginValidation
import com.lateapex.collie.network.OriginValidator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.Locale
import java.util.UUID

/** Local parsing only. Importing a URL never configures a server or requests its contents. */
internal object LinkImportCoordinator {
    const val MAX_LINK_CHARS = 8192

    sealed interface Candidate {
        val origin: CollieOrigin
        class Navigation(override val origin: CollieOrigin, val url: String, val target: CollieDeepLink) : Candidate
        class Pairing(override val origin: CollieOrigin, val code: String) : Candidate {
            override fun toString(): String = "Pairing(<redacted>)"
        }
    }

    fun parse(raw: String, connection: Connection?): Candidate? {
        if (raw.length > MAX_LINK_CHARS) return null
        val text = raw.trim()
        if (text.isEmpty() || text.any { it <= ' ' || it == '\u007f' }) return null
        val lexical = runCatching { URI(text) }.getOrNull() ?: return null
        if (!lexical.scheme.equals("https", true) || lexical.rawUserInfo != null) return null
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        // Refuse path normalization and encoded separators before OkHttp resolves them.
        if (lexical.rawPath.orEmpty().contains(Regex("(?i)%2f|%5c")) || '\\' in lexical.rawPath.orEmpty()) return null
        val decodedSegments = Uri.parse(text).pathSegments
        if (decodedSegments.any { it == "." || it == ".." } || "//" in lexical.rawPath.orEmpty()) return null
        val names = url.queryParameterNames
        if (names.any { url.queryParameterValues(it).size != 1 }) return null
        if (url.fragment != null && url.fragment != "paired-devices") return null
        val pair = url.queryParameter("pair")
        if (pair != null) {
            if (names != setOf("pair") || url.pathSegments.lastOrNull() != "settings") return null
            val code = pair.uppercase(Locale.ROOT)
            if (!code.matches(Regex("[23456789ABCDEFGHJKMNPQRSTWXYZ]{4}-?[23456789ABCDEFGHJKMNPQRSTWXYZ]{4}"))) return null
            val originText = url.newBuilder().query(null).fragment(null)
                .removePathSegment(url.pathSegments.lastIndex).build().toString()
            val origin = (OriginValidator().validate(originText) as? OriginValidation.Valid)?.origin ?: return null
            if (connection != null && connection.origin != origin) return null
            return Candidate.Pairing(origin, code)
        }
        // Protected routes are meaningful only for the already paired exact mounted origin.
        if (connection?.isPaired != true || names.any { it !in setOf("h", "s", "all") }) return null
        if (url.queryParameter("all")?.let { it != "1" } == true) return null
        if (listOfNotNull(url.queryParameter("h"), url.queryParameter("s")).any { !boundedId(it) }) return null
        val target = CollieDeepLink.parse(Uri.parse(text), connection.origin) ?: return null
        if (url.fragment != null && target !is CollieDeepLink.Settings) return null
        when (target) {
            is CollieDeepLink.Pane -> if (!boundedId(target.paneId)) return null
            is CollieDeepLink.Space -> if (!boundedId(target.workspaceId)) return null
            else -> Unit
        }
        return Candidate.Navigation(connection.origin, url.toString(), target)
    }

    private fun boundedId(value: String) = value.matches(Regex("[A-Za-z0-9:._-]{1,128}"))
}

/** Exact local durable owner, including the empty-profile incarnation; no copied bearer. */
internal class LinkImportOwner private constructor(
    private val committed: CommittedConnectionSnapshot?,
    val connection: Connection?,
    private val actor: ScopedReadContext?,
) {
    fun current(app: CollieApplication): Boolean {
        val store = app.container.connectionStore
        val actual = store.captureCommitted()
        val sameSnapshot = if (committed == null) actual == null else actual != null &&
            actual.revision == committed.revision && actual.incarnationId == committed.incarnationId &&
            actual.connection === committed.connection
        return sameSnapshot && store.connection.value === connection &&
            (actor == null || app.container.repository.isCurrent(actor))
    }

    companion object {
        fun capture(app: CollieApplication): LinkImportOwner? {
            val store = app.container.connectionStore
            val committed = store.captureCommitted()
            // A queued StateFlow notification cannot override the durable committed owner.
            val connection = if (committed != null) committed.connection else store.connection.value
            val actor = if (connection == null) null else app.container.repository.readContext(Scope(), null)
            if (connection?.isPaired == true && actor == null) return null
            val owner = LinkImportOwner(committed, connection, actor)
            return owner.takeIf { it.current(app) }
        }
    }
}

/** Process-local, expiring, single-use handoff. Preview and user-confirmed grants are distinct. */
internal object LinkImportHandoff {
    const val EXTRA_ID = "com.lateapex.collie.LINK_IMPORT_ID"
    class Prepared(val candidate: LinkImportCoordinator.Candidate, val owner: LinkImportOwner)
    private class Entry(val prepared: Prepared, val confirmed: Boolean, val deadline: Long)
    private val pending = LinkedHashMap<String, Entry>()

    @Synchronized fun putPreview(prepared: Prepared): String = put(prepared, false)
    @Synchronized fun putConfirmed(prepared: Prepared): String = put(prepared, true)
    private fun put(prepared: Prepared, confirmed: Boolean): String {
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeAll { it.value.deadline <= now }
        while (pending.size >= 4) pending.remove(pending.keys.first())
        val id = UUID.randomUUID().toString()
        pending[id] = Entry(prepared, confirmed, now + 60_000)
        return id
    }

    @Synchronized fun consumePreview(intent: Intent, app: CollieApplication): Prepared? = consume(intent, app, false)
    @Synchronized fun consumeConfirmed(intent: Intent, app: CollieApplication): Prepared? = consume(intent, app, true)
    private fun consume(intent: Intent, app: CollieApplication, confirmed: Boolean): Prepared? {
        val id = intent.getStringExtra(EXTRA_ID)
        intent.removeExtra(EXTRA_ID)
        if (id == null || id.length != 36) return null
        val entry = pending.remove(id) ?: return null
        return entry.prepared.takeIf { entry.confirmed == confirmed && entry.deadline > SystemClock.elapsedRealtime() && it.owner.current(app) }
    }

    @Synchronized fun remove(id: String) { pending.remove(id) }
}
