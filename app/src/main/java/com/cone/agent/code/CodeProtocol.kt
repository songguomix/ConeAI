package com.cone.agent.code

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One item of the visible checklist emitted by ConeCode's `update_todos`. */
data class CodeTodo(val content: String, val status: String)

/**
 * One tool invocation parsed out of a model reply.
 *
 * The wire format is ConeCode's: a fenced ```json block holding one action object,
 * e.g. {"action": "edit_file", "path": "app.js", "old": "…", "new": "…"}.
 * The extra fields are the union of every supported action's arguments; the agent
 * reads only the ones relevant to the action it is executing. Legacy ConeAI tag
 * turns (saved by older builds) are still parsed so old transcripts keep rendering.
 */
data class ToolCall(
    val tool: String,
    val path: String = "",
    val query: String = "",
    val content: String = "",
    val to: String = "",
    val regex: Boolean = false,
    val edits: List<Pair<String, String>> = emptyList(),
    val old: String = "",
    val new: String = "",
    val url: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val todos: List<CodeTodo> = emptyList(),
    val text: String = "",
    val kind: String = "",
    val why: String = "",
    val limit: Int = 0,
    val description: String = "",
    val srcPath: String = "",
    val destPath: String = "",
    val replaceAll: Boolean = false,
)

sealed interface CodeSegment {
    data class Prose(val text: String) : CodeSegment
    data class Call(val call: ToolCall) : CodeSegment
}

/**
 * Parser for the ConeCode JSON action protocol, with the same normalizations ConeCode
 * applies before parsing (XML-style tool calls, `<tool_call>` blocks, bare action
 * objects). It also keeps the old ConeAI tag parser as a compatibility path so
 * transcripts saved by previous versions still show their tool cards.
 */
object CodeProtocol {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Actions the phone workspace can serve. Unknown ones still become calls and get an explicit error. */
    val ACTIONS = listOf(
        "read_file", "list_dir", "search", "glob",
        "create_file", "edit_file", "create_dir", "delete", "rename", "copy", "download",
        "web_search", "web_fetch", "remember", "memory_search",
        "update_todos", "ask_user", "system_info", "plan", "done",
    )

    private val ALIASES = mapOf(
        "read" to "read_file", "list" to "list_dir", "write" to "create_file",
        "edit" to "edit_file", "mkdir" to "create_dir", "web" to "web_search",
        "fetch" to "web_fetch", "todo" to "update_todos", "update_todo" to "update_todos",
        "ask" to "ask_user", "finish" to "done", "task_done" to "done",
        "read-file" to "read_file", "edit-file" to "edit_file",
        "create-file" to "create_file", "create-directory" to "create_dir",
    )

    private val LEGACY_FENCED = Regex(
        "```(?:xml|text)?\\s*\\n?(<(?:read|write|edit|delete|rename|glob|search|todo|web|fetch|remember|ask|plan|done)\\b[\\s\\S]*?)```",
    )
    private val LEGACY_CALL = Regex(
        "<(read|write|edit|delete|rename|glob|search|web|fetch|remember|ask|todo|plan|done)\\b" +
            "([^>]*?)(?:/>|>([\\s\\S]*?)</\\1>)",
    )
    private val LEGACY_ATTR = Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"")
    private val LEGACY_EDIT = Regex("<old>([\\s\\S]*?)</old>\\s*<new>([\\s\\S]*?)</new>")

    private val XML_TOOL = Regex(
        "<(" + ACTIONS.joinToString("|") + ")\\b([^>]*?)(?:/>|>([\\s\\S]*?)</\\1>)",
        RegexOption.IGNORE_CASE,
    )
    private val XML_CHILD = Regex("<(\\w+)>([\\s\\S]*?)</\\1>")
    private val TOOL_CALL_BLOCK = Regex("<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>")
    private val TRAILING_COMMA = Regex(",\\s*([}\\]])")
    private val BARE_ACTION_HEAD = Regex("^\\{\\s*\"action\"\\s*:")
    private val OPTIONS_ARRAY = Regex("\"options\"\\s*:\\s*\\[([\\s\\S]*?)]")
    private val STRING_FIELD = Regex("\"(\\w+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    private val LOOSE_FIELDS = listOf(
        "path", "query", "pattern", "q", "content", "text", "plan", "summary",
        "old", "old_string", "new", "new_string", "url", "question", "newPath", "destPath",
        "to", "srcPath", "kind", "why", "limit", "description", "isRegex", "regex",
        "replace_all", "replaceAll",
    )

    fun parse(reply: String, streaming: Boolean = false): List<CodeSegment> {
        if (reply.isBlank()) return emptyList()
        // Old transcripts are detected by their tags; if any survived, render them with the
        // legacy parser so the recorded outcomes still line up one-to-one.
        val legacy = parseLegacy(reply, streaming)
        if (legacy.any { it is CodeSegment.Call }) return legacy
        return parseJsonActions(reply, streaming)
    }

    fun calls(reply: String): List<ToolCall> =
        parse(reply).filterIsInstance<CodeSegment.Call>().map { it.call }

    /* ---------------- ConeCode JSON action protocol ---------------- */

    private fun parseJsonActions(reply: String, streaming: Boolean): List<CodeSegment> {
        val text = normalize(reply)
        val out = ArrayList<CodeSegment>()
        var cursor = 0
        while (cursor < text.length) {
            val open = text.indexOf("```", cursor)
            if (open < 0) {
                addProse(out, text.substring(cursor))
                break
            }
            if (open > cursor) addProse(out, text.substring(cursor, open))
            val close = text.indexOf("```", open + 3)
            if (close < 0) {
                // Fence still being streamed: hold it back instead of flashing raw JSON at the user.
                if (!streaming) addProse(out, text.substring(open))
                break
            }
            val lineEnd = text.indexOf('\n', open + 3)
            val bodyStart = if (lineEnd in (open + 3) until close) lineEnd + 1 else open + 3
            var body = text.substring(bodyStart, close).trim()
            // Tolerate "```json{…}```" written without a newline after the language tag.
            if (!body.startsWith("{")) {
                val brace = body.indexOf('{')
                if (brace in 1..8) body = body.substring(brace)
            }
            val call = parseAction(body)
            if (call != null) out += CodeSegment.Call(call)
            else addProse(out, text.substring(open, close + 3))
            cursor = close + 3
        }
        return out
    }

    private fun normalize(reply: String): String {
        var text = outsideFences(reply) { chunk -> XML_TOOL.replace(chunk) { xmlToFence(it) } }
        text = outsideFences(text) { chunk -> TOOL_CALL_BLOCK.replace(chunk) { toolCallBlockToFence(it) } }
        return outsideFences(text) { chunk -> fenceBareActions(chunk) }
    }

    /** Apply [transform] only to prose outside complete ``` fences; fenced bodies stay verbatim. */
    private fun outsideFences(text: String, transform: (String) -> String): String {
        val out = StringBuilder()
        var cursor = 0
        while (cursor < text.length) {
            val open = text.indexOf("```", cursor)
            if (open < 0) {
                out.append(transform(text.substring(cursor)))
                break
            }
            out.append(transform(text.substring(cursor, open)))
            val close = text.indexOf("```", open + 3)
            if (close < 0) {
                // Incomplete fence — leave the tail untouched for the action scanner to hold back.
                out.append(text.substring(open))
                break
            }
            out.append(text.substring(open, close + 3))
            cursor = close + 3
        }
        return out.toString()
    }

    /** `<read_file path="a.txt"/>` and `<read_file><path>a.txt</path></read_file>` → fenced JSON. */
    private fun xmlToFence(match: MatchResult): String {
        val action = match.groupValues[1].lowercase()
        val fields = LinkedHashMap<String, String>()
        fields["action"] = action
        LEGACY_ATTR.findAll(match.groupValues[2]).forEach {
            fields[it.groupValues[1]] = unescape(it.groupValues[2])
        }
        XML_CHILD.findAll(match.groupValues[3]).forEach {
            fields[it.groupValues[1]] = unescape(it.groupValues[2])
        }
        return renderAction(fields)
    }

    /** `<tool_call>{"name":"read_file","arguments":{…}}</tool_call>` → fenced JSON. */
    private fun toolCallBlockToFence(match: MatchResult): String {
        val obj = runCatching {
            json.parseToJsonElement(repairJson(match.groupValues[1].trim())).jsonObject
        }.getOrNull() ?: return match.value
        val name = obj.str("name", "action") ?: return match.value
        val fields = LinkedHashMap<String, String>()
        fields["action"] = name
        obj["arguments"]?.jsonObject?.forEach { (key, value) ->
            runCatching { value.jsonPrimitive.content }.getOrNull()?.let { fields[key] = it }
        }
        return renderAction(fields)
    }

    private fun renderAction(fields: Map<String, String>): String {
        val body = fields.entries.joinToString(",") { (key, value) -> "\"$key\":\"${escape(value)}\"" }
        return "```json\n{$body}\n```"
    }

    /** Any `{"action": …}` object sitting in prose is promoted to a fenced action block. */
    private fun fenceBareActions(chunk: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < chunk.length) {
            if (chunk[i] == '{' && BARE_ACTION_HEAD.containsMatchIn(chunk.substring(i, minOf(i + 96, chunk.length)))) {
                val end = jsonObjectEnd(chunk, i)
                if (end != null) {
                    out.append("```json\n").append(chunk.substring(i, end)).append("\n```")
                    i = end
                    continue
                }
            }
            out.append(chunk[i])
            i++
        }
        return out.toString()
    }

    /** Index just past the matching `}` of the object starting at [start], honoring strings. */
    private fun jsonObjectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
        }
        return null
    }

    private fun parseAction(body: String): ToolCall? {
        if (!body.startsWith("{")) return null
        val obj = runCatching { json.parseToJsonElement(repairJson(body)).jsonObject }
            .getOrNull()
            ?: return callFromLooseJson(body)
        return toCall(obj)
    }

    private fun repairJson(text: String): String = TRAILING_COMMA.replace(text.trim(), "$1")

    /** Salvage a malformed action object (usually a trailing comma or an unescaped quote). */
    private fun callFromLooseJson(text: String): ToolCall? {
        val action = stringField(text, "action") ?: return null
        val values = LOOSE_FIELDS.associateWith { key -> stringField(text, key).orEmpty() }
        val options = OPTIONS_ARRAY.find(text)?.groupValues?.get(1)
            ?.let { STRING_FIELD.findAll(it).map { m -> unescape(m.groupValues[2]) }.toList() }
            ?: emptyList()
        return buildCall(action, values, emptyList(), options)
    }

    private fun stringField(text: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(text)?.groupValues?.get(1)?.let(::unescape)

    private fun toCall(obj: JsonObject): ToolCall? {
        val raw = obj.str("action", "tool", "name") ?: return null
        val values = obj.entries.associate { (key, value) ->
            key to runCatching { value.jsonPrimitive.content }.getOrDefault("")
        }
        val todos = runCatching {
            obj["todos"]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                ?.map { CodeTodo(it.str("content") ?: "", it.str("status") ?: "pending") }
                ?: emptyList()
        }.getOrDefault(emptyList())
        val options = runCatching {
            obj["options"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: emptyList()
        }.getOrDefault(emptyList())
        return buildCall(raw, values, todos, options)
    }

    private fun buildCall(
        raw: String,
        values: Map<String, String>,
        todos: List<CodeTodo>,
        options: List<String>,
    ): ToolCall {
        val tool = ALIASES[raw] ?: raw
        val old = values["old"].orEmpty().ifBlank { values["old_string"].orEmpty() }
        val new = values["new"].orEmpty().ifBlank { values["new_string"].orEmpty() }
        val url = values["url"].orEmpty()
        val query = values["query"].orEmpty().ifBlank { values["pattern"].orEmpty() }
            .ifBlank { values["q"].orEmpty() }
        val rawContent = values["content"].orEmpty()
            .ifBlank { values["text"].orEmpty() }
            .ifBlank { values["plan"].orEmpty() }
            .ifBlank { values["summary"].orEmpty() }
        val todoText = if (todos.isNotEmpty()) {
            todos.joinToString("\n") { todo ->
                when (todo.status) {
                    "completed" -> "[x] "
                    "in_progress" -> "[>] "
                    else -> "[ ] "
                } + todo.content
            }
        } else rawContent
        return ToolCall(
            tool = tool,
            path = values["path"].orEmpty().ifBlank { values["oldPath"].orEmpty() }
                .ifBlank { values["srcPath"].orEmpty() },
            query = query.ifBlank { url }.ifBlank { values["question"].orEmpty() },
            content = when (tool) {
                "update_todos", "plan" -> todoText
                "ask_user" -> values["question"].orEmpty().ifBlank { rawContent }
                "remember" -> values["text"].orEmpty().ifBlank { rawContent }
                "web_fetch" -> url
                "edit_file" -> if (old.isNotBlank()) "" else rawContent
                else -> rawContent
            },
            to = values["newPath"].orEmpty().ifBlank { values["destPath"].orEmpty() }
                .ifBlank { values["to"].orEmpty() },
            regex = values["isRegex"]?.toBooleanStrictOrNull()
                ?: values["regex"]?.toBooleanStrictOrNull() ?: false,
            edits = if (tool == "edit_file" && old.isNotBlank()) listOf(old to new) else emptyList(),
            old = old,
            new = new,
            url = url,
            question = values["question"].orEmpty(),
            options = options,
            todos = todos,
            text = values["text"].orEmpty(),
            kind = values["kind"].orEmpty(),
            why = values["why"].orEmpty(),
            limit = values["limit"]?.toIntOrNull() ?: 0,
            description = values["description"].orEmpty(),
            srcPath = values["srcPath"].orEmpty(),
            destPath = values["destPath"].orEmpty(),
            replaceAll = values["replace_all"]?.toBooleanStrictOrNull()
                ?: values["replaceAll"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitive?.contentOrNull }

    private fun escape(text: String) = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                i++
                out.append(
                    when (text[i]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        else -> text[i]
                    },
                )
            } else out.append(c)
            i++
        }
        return out.toString()
    }

    /* ---------------- legacy ConeAI tag protocol (saved transcripts) ---------------- */

    private fun parseLegacy(reply: String, streaming: Boolean): List<CodeSegment> {
        val text = LEGACY_FENCED.replace(reply) { it.groupValues[1] }
        val out = ArrayList<CodeSegment>()
        var last = 0
        var searchFrom = 0
        while (true) {
            val match = LEGACY_CALL.find(text, searchFrom) ?: break
            val name = match.groupValues[1]
            val attrs = match.groupValues[2]
            val body = match.groupValues[3]
            val end = match.range.last + 1
            val open = match.range.first
            if (streaming && end >= text.length - 3) break
            if (open > last) addProse(out, text.substring(last, open))
            val attrsMap = LEGACY_ATTR.findAll(attrs).associate { it.groupValues[1] to it.groupValues[2] }
            val path = attrsMap["path"].orEmpty()
            val to = attrsMap["to"].orEmpty()
            val call = when (name) {
                "write", "edit" -> {
                    val edits = if (name == "edit") {
                        LEGACY_EDIT.findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()
                    } else emptyList()
                    val content = if (name == "edit") "" else body.trim()
                    ToolCall(name, path = path, content = content, edits = edits)
                }
                "read", "delete", "glob", "search", "web", "fetch", "remember" -> ToolCall(
                    name,
                    path = path,
                    query = attrsMap["query"].orEmpty().ifBlank { path },
                    content = body.trim(),
                    to = to,
                    regex = attrsMap["regex"].equals("true", ignoreCase = true),
                )
                "rename" -> ToolCall("rename", path = path, to = to)
                "todo", "ask", "plan", "done" -> ToolCall(name, content = body.trim())
                else -> null
            }
            if (call != null) out += CodeSegment.Call(call)
            last = end
            searchFrom = end
        }
        if (last < text.length) {
            val tail = text.substring(last)
            if (!streaming || !looksLikeTagStart(tail)) addProse(out, tail)
        }
        return out
    }

    private fun looksLikeTagStart(tail: String): Boolean {
        val open = tail.lastIndexOf('<')
        if (open < 0) return false
        val fragment = tail.substring(open)
        return !fragment.contains('>') && fragment.length < 160
    }

    private fun addProse(out: MutableList<CodeSegment>, text: String) {
        if (text.isBlank()) return
        out += CodeSegment.Prose(text.trim())
    }
}
