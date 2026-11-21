package io.github.novakr12.soundscape

import java.net.URI

object Server {
    private val SOUNDSCAPE = Regex("\"app\"\\s*:\\s*\"soundscape\"")

    fun normalize(input: String): String? {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://$host$port$path"
    }

    fun isOwnPage(server: String, address: String): Boolean {
        val home = runCatching { URI(server.trim()) }.getOrNull() ?: return false
        val there = runCatching { URI(address.trim()) }.getOrNull() ?: return false
        if (!home.scheme.equals(there.scheme, ignoreCase = true) ||
            !home.host.equals(there.host, ignoreCase = true) ||
            portOf(home) != portOf(there)
        ) {
            return false
        }
        val base = home.rawPath.orEmpty().trimEnd('/')
        if (base.isEmpty()) return true
        val path = there.rawPath.orEmpty()
        return path == base || path.startsWith("$base/")
    }

    fun origin(server: String): String? {
        val uri = runCatching { URI(server.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port"
    }

    fun basePath(server: String): String =
        runCatching { URI(server.trim()).rawPath.orEmpty().trimEnd('/') }.getOrDefault("")

    fun looksLikeSoundscape(body: String): Boolean = SOUNDSCAPE.containsMatchIn(body)

    fun infoAddress(server: String) = "$server/api/info"

    private fun portOf(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}
