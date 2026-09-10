package com.lateapex.collie.ui

/** Native mirror of the web composer's reviewed destructive-input classifier. */
object DestructiveInput {
    private data class Pattern(val reason: String, val regex: Regex)

    private val patterns = listOf(
        Pattern("rm -r (recursive delete)", Regex("""\brm\b[^\n;&|]*\s(?:-[a-z]*r[a-z]*|--recursive)\b""", RegexOption.IGNORE_CASE)),
        Pattern("git push --force", Regex("""\bgit\s+push\b[^\n;&|]*\s(?:--force|-f)\b""", RegexOption.IGNORE_CASE)),
        Pattern("sudo (runs as root)", Regex("""\bsudo\b""", RegexOption.IGNORE_CASE)),
        Pattern("--force flag", Regex("""--force\b""", RegexOption.IGNORE_CASE)),
        Pattern("dd if= (raw disk write)", Regex("""\bdd\b[^\n]*\bif=""", RegexOption.IGNORE_CASE)),
        Pattern("mkfs (format a filesystem)", Regex("""\bmkfs\b""", RegexOption.IGNORE_CASE)),
        Pattern(
            "redirect to a system path",
            Regex(""":>\s*/|>\s*/(?:\s|$|(?:dev|etc|boot|proc|sys|usr|bin|sbin|lib|var|root)\b)""", RegexOption.IGNORE_CASE),
        ),
    )

    fun reason(text: String): String? = patterns.firstOrNull { it.regex.containsMatchIn(text) }?.reason
}
