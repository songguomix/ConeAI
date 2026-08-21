package com.cone.agent.web

import java.net.URLEncoder

/** The search engines the built-in browser can use. [AUTO] is not a real engine — see resolver. */
enum class SearchEngine(
    val id: String,
    val displayName: String,
    val homeUrl: String,
    private val queryTemplate: String,
) {
    BAIDU("baidu", "百度", "https://www.baidu.com/", "https://www.baidu.com/s?wd=%s"),
    GOOGLE("google", "Google", "https://www.google.com/", "https://www.google.com/search?q=%s"),
    BING("bing", "Bing", "https://www.bing.com/", "https://www.bing.com/search?q=%s");

    /** Full results URL for [query]. */
    fun searchUrl(query: String): String =
        queryTemplate.format(URLEncoder.encode(query.trim(), "UTF-8"))

    /** Opens [input] as a URL if it looks like one, otherwise searches for it. Blank → home page. */
    fun urlFor(input: String?): String {
        val t = input?.trim().orEmpty()
        return when {
            t.isBlank() -> homeUrl
            t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> searchUrl(t)
        }
    }

    companion object {
        /** Preference sentinel meaning "pick by IP region (mainland China → 百度, else Google)". */
        const val AUTO = "auto"

        fun from(id: String?): SearchEngine? = entries.firstOrNull { it.id == id }
    }
}
