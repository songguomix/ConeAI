package com.cone.agent.agent

import java.net.URI

/** Agent tasks may open a known page, but must not turn keywords into a web search. */
internal object AgentWebPolicy {
    fun directUrl(input: String): String? {
        val text = input.trim()
        val candidate = when {
            text.startsWith("https://", true) || text.startsWith("http://", true) -> text
            text.contains('.') && "://" !in text -> "https://$text"
            else -> return null
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.rawUserInfo != null) return null
        // Also reject direct links to the search engines supported by the in-app browser.
        if (SEARCH_HOSTS.any { host == it || host.endsWith(".$it") } &&
            uri.path.orEmpty().trimEnd('/') in SEARCH_PATHS
        ) return null
        return candidate
    }

    private val SEARCH_HOSTS = setOf("bing.com", "baidu.com", "google.com", "google.com.hk", "google.cn")
    private val SEARCH_PATHS = setOf("", "/search", "/s", "/webhp")
}
