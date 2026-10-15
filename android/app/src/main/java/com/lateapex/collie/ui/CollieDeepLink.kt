package com.lateapex.collie.ui

import android.net.Uri
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.domain.CollieOrigin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

sealed interface CollieDeepLink {
    data class Pane(val paneId: String, val scope: Scope, val history: Boolean) : CollieDeepLink
    data class Space(val workspaceId: String, val scope: Scope) : CollieDeepLink
    data class Settings(
        val updates: Boolean,
        val scope: Scope,
        val pairCode: String? = null,
        val focusDevices: Boolean = false,
    ) : CollieDeepLink
    data class Pack(val scope: Scope) : CollieDeepLink
    data class Home(val scope: Scope) : CollieDeepLink

    companion object {
        fun parse(uri: Uri?, allowedHost: String): CollieDeepLink? =
            parse(uri, CollieOrigin("https://$allowedHost/"))

        fun parse(uri: Uri?, origin: CollieOrigin): CollieDeepLink? {
            val base = origin.value.toHttpUrlOrNull() ?: return null
            val link = uri?.toString()?.toHttpUrlOrNull() ?: return null
            if (link.scheme != base.scheme || link.host != base.host || link.port != base.port ||
                link.username.isNotEmpty() || link.password.isNotEmpty()
            ) return null
            val mount = base.encodedPath.trimEnd('/')
            if (mount.isNotEmpty() && link.encodedPath != mount && !link.encodedPath.startsWith("$mount/")) return null
            val segments = uri.pathSegments.drop(base.pathSegments.count { it.isNotEmpty() })
            val scope = runCatching {
                Scope(
                    host = uri.getQueryParameter("h")?.takeIf(String::isNotBlank),
                    session = uri.getQueryParameter("s")?.takeIf(String::isNotBlank),
                    viewAll = uri.getQueryParameter("all") == "1",
                )
            }.getOrNull() ?: return null
            return when {
                segments.isEmpty() -> Home(scope)
                segments.size == 2 && segments[0] == "pane" && segments[1].isNotBlank() ->
                    Pane(segments[1], scope.copy(viewAll = false), history = false)
                segments.size == 3 && segments[0] == "pane" && segments[1].isNotBlank() && segments[2] == "history" ->
                    Pane(segments[1], scope.copy(viewAll = false), history = true)
                segments.size == 2 && segments[0] == "space" && segments[1].isNotBlank() ->
                    Space(segments[1], scope.copy(viewAll = false))
                segments == listOf("settings") -> Settings(
                    updates = false,
                    scope = scope,
                    pairCode = uri.getQueryParameter("pair")
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.uppercase(Locale.ENGLISH),
                    focusDevices = uri.fragment == PAIRED_DEVICES_FRAGMENT,
                )
                segments == listOf("settings", "updates") -> Settings(updates = true, scope = scope)
                segments == listOf("pack") -> Pack(scope)
                else -> null
            }
        }

        private const val PAIRED_DEVICES_FRAGMENT = "paired-devices"
    }
}
