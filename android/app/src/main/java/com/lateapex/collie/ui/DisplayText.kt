package com.lateapex.collie.ui

internal object DisplayText {
    fun abbreviateHome(path: String, home: String = "/home/chris"): String = when {
        home.isNotBlank() && path == home.trimEnd('/') -> "~"
        home.isNotBlank() && path.startsWith("${home.trimEnd('/')}/") ->
            "~${path.removePrefix(home.trimEnd('/'))}"
        else -> path
    }
}
