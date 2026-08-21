package com.cone.agent.code

/** One tool invocation parsed out of a model reply. */
data class ToolCall(
    val tool: String,
    val path: String = "",
    val query: String = "",
    val content: String = "",
    val to: String = "",
    val regex: Boolean = false,
    /** Every `<old>`/`<new>` pair of an edit, in order — one entry for an ordinary single edit. */
    val edits: List<Pair<String, String>> = emptyList(),
)

/** A model reply, split into the prose the user reads and the calls the workspace runs. */
sealed interface CodeSegment {
    data class Prose(val text: String) : CodeSegment
    data class Call(val call: ToolCall) : CodeSegment
}

/**
 * The wire format between the model and the workspace.
 *
 * Tag-shaped rather than the JSON the rest of the app uses, and that is the whole point: a code tool
 * carries entire files as arguments. In JSON that means every newline, quote and backslash of the
 * payload has to survive escaping intact, and models routinely fail at that for anything longer than
 * a few lines — a single unescaped quote loses the whole call. Tags let the payload sit verbatim
 * between an opener and a closer, so the failure mode is a stray tag rather than a corrupted file.
 *
 * Everything outside a tag is prose and reaches the user unchanged.
 */
object CodeProtocol {

    val TOOLS = setOf(
        "read", "list", "search", "glob", "write", "edit", "delete", "rename",
        "todo", "web", "fetch", "remember", "ask", "plan", "done",
    )

    private const val NAMES = "read|list|search|glob|write|edit|delete|rename|todo|web|fetch|remember|ask|plan|done"

    private val OPEN = Regex("<($NAMES)\\b")
    private val ATTR = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s/>]+))""")

    /**
     * Some models insist on wrapping tool calls in a markdown fence. Unwrapping the fence is far
     * kinder than letting the call render as decorative code and the run stall with nothing done.
     */
    private val FENCED = Regex(
        "```[a-zA-Z]*[ \\t]*\\r?\\n(\\s*<(?:$NAMES)[\\s\\S]*?)\\r?\\n```",
    )

    /**
     * A tag name still being typed (`<wri`), only ever seen at the very end of a live buffer. Matched
     * as prose otherwise, it would flash on screen for a frame before turning into a tool card.
     */
    private val PARTIAL_TAG = Regex("<[a-z]{0,7}$")

    /**
     * Splits [reply] into prose and calls. A tag left unterminated — the normal state mid-stream —
     * is dropped rather than half-executed, so the same function is safe to call on every delta.
     *
     * [streaming] additionally hides a half-typed opening tag at the end of the buffer. It is off for
     * a finished reply, where trailing `<` is real text the user wrote about and must survive.
     */
    fun parse(reply: String, streaming: Boolean = false): List<CodeSegment> {
        // Re-parsed on every frame while a reply streams, so skip the scan entirely when there is
        // no fence to unwrap — which is the overwhelmingly common case.
        val text = if (reply.contains("```")) FENCED.replace(reply) { it.groupValues[1] } else reply
        val out = ArrayList<CodeSegment>()
        var cursor = 0
        while (cursor < text.length) {
            val match = OPEN.find(text, cursor)
            if (match == null) {
                val tail = text.substring(cursor)
                addProse(out, if (streaming) PARTIAL_TAG.replace(tail, "") else tail)
                break
            }
            addProse(out, text.substring(cursor, match.range.first))
            val tool = match.groupValues[1]
            val tagEnd = text.indexOf('>', match.range.last)
            if (tagEnd == -1) break // opener still streaming
            val header = text.substring(match.range.last + 1, tagEnd)
            val attrs = ATTR.findAll(header).associate { m ->
                val v = m.groupValues
                m.groupValues[1] to (v[2].ifEmpty { v[3].ifEmpty { v[4] } })
            }
            if (header.trimEnd().endsWith("/")) {
                out += CodeSegment.Call(build(tool, attrs, ""))
                cursor = tagEnd + 1
                continue
            }
            val close = text.indexOf("</$tool>", tagEnd)
            if (close == -1) break // body still streaming
            out += CodeSegment.Call(build(tool, attrs, strip(text.substring(tagEnd + 1, close))))
            cursor = close + tool.length + 3
        }
        return out
    }

    /** Only the calls, in order — what [CodeAgent] executes once a round has finished streaming. */
    fun calls(reply: String): List<ToolCall> =
        parse(reply).filterIsInstance<CodeSegment.Call>().map { it.call }

    private fun build(tool: String, attrs: Map<String, String>, body: String): ToolCall {
        // One <edit> may carry several <old>/<new> pairs; they are applied in order, so a rename
        // across a file is one call instead of one round trip per occurrence.
        val edits = if (tool == "edit") {
            val olds = Regex("<old>([\\s\\S]*?)</old>").findAll(body).map { strip(it.groupValues[1]) }.toList()
            val news = Regex("<new>([\\s\\S]*?)</new>").findAll(body).map { strip(it.groupValues[1]) }.toList()
            olds.zip(news)
        } else {
            emptyList()
        }
        val bodyQuery = if (tool == "search" || tool == "glob" || tool == "web") body else ""
        return ToolCall(
            tool = tool,
            path = attrs["path"] ?: attrs["file"] ?: attrs["from"].orEmpty(),
            query = attrs["q"] ?: attrs["query"] ?: attrs["pattern"] ?: attrs["url"] ?: bodyQuery,
            content = if (tool in BODY_TOOLS) body else "",
            to = attrs["to"].orEmpty(),
            regex = attrs["re"] == "1" || attrs["regex"] == "1" || attrs["regex"] == "true",
            edits = edits,
        )
    }

    /**
     * Drops exactly one newline after the opener and before the closer — the formatting a model adds
     * to keep the tag readable — while leaving the file's own leading/trailing blank lines alone.
     */
    /** Tools whose payload is the tag body rather than an attribute. */
    private val BODY_TOOLS = setOf("write", "done", "todo", "remember", "ask", "plan")

    private fun strip(body: String): String {
        var s = body
        if (s.startsWith("\r\n")) s = s.substring(2) else if (s.startsWith("\n")) s = s.substring(1)
        if (s.endsWith("\r\n")) s = s.dropLast(2) else if (s.endsWith("\n")) s = s.dropLast(1)
        return s
    }

    private fun addProse(out: MutableList<CodeSegment>, text: String) {
        if (text.isBlank()) return
        out += CodeSegment.Prose(text.trim())
    }
}
