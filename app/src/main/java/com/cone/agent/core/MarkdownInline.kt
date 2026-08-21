package com.cone.agent.core

/**
 * Tiny Markdown helpers shared by the Compose chat renderer and the View-based assistant pill, so both
 * parse images/links the same way:
 *  - URLs may contain balanced parentheses (e.g. Wikipedia `/wiki/Foo_(bar)`), and
 *  - `![alt](url)` images are found anywhere in a line, not only on a line of their own.
 */
object MarkdownInline {

    data class ImageRef(val start: Int, val end: Int, val url: String, val alt: String)

    /** Index of the ')' closing the '(' at [openIndex], honoring nested parens; -1 if unbalanced. */
    fun matchClosingParen(text: String, openIndex: Int): Int {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** Strips an optional Markdown title and surrounding space from a captured `(url "title")` body. */
    fun cleanUrl(raw: String): String = raw.trim().substringBefore(' ').trim()

    /** Every `![alt](url)` image in [line], in order. URLs may contain balanced parens. */
    fun findImages(line: String): List<ImageRef> {
        val out = ArrayList<ImageRef>()
        var i = 0
        while (i < line.length) {
            if (line[i] == '!' && i + 1 < line.length && line[i + 1] == '[') {
                val close = line.indexOf(']', i + 2)
                if (close > 0 && close + 1 < line.length && line[close + 1] == '(') {
                    val end = matchClosingParen(line, close + 1)
                    if (end > 0) {
                        val url = cleanUrl(line.substring(close + 2, end))
                        if (url.isNotEmpty()) {
                            out.add(ImageRef(i, end + 1, url, line.substring(i + 2, close).trim()))
                            i = end + 1
                            continue
                        }
                    }
                }
            }
            i++
        }
        return out
    }
}
