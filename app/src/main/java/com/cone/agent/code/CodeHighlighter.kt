package com.cone.agent.code

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Token colors for code. Deliberately a real syntax palette, not Material roles — code that is all
 * one color is unreadable, and the whole point of this screen is reading code on a small display. */
@Immutable
data class CodePalette(
    val text: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val type: Color,
    val punctuation: Color,
    val tag: Color,
    val attribute: Color,
) {
    companion object {
        /** Material Palenight-ish, tuned for dark surfaces. */
        val Dark = CodePalette(
            text = Color(0xFFD5DAE5),
            keyword = Color(0xFFC792EA),
            string = Color(0xFFC3E88D),
            number = Color(0xFFF78C6C),
            comment = Color(0xFF6B7A99),
            function = Color(0xFF82AAFF),
            type = Color(0xFFFFCB6B),
            punctuation = Color(0xFF8C93A8),
            tag = Color(0xFFF07178),
            attribute = Color(0xFFFFCB6B),
        )

        /** Same hues pulled down in lightness so they hold contrast on a white sheet. */
        val Light = CodePalette(
            text = Color(0xFF2B2F3A),
            keyword = Color(0xFF8B27B5),
            string = Color(0xFF227A3B),
            number = Color(0xFFB4501B),
            comment = Color(0xFF8A93A6),
            function = Color(0xFF1A5FBF),
            type = Color(0xFF9A6600),
            punctuation = Color(0xFF6C7383),
            tag = Color(0xFFB3283A),
            attribute = Color(0xFF9A6600),
        )
    }
}

@Composable
fun rememberCodePalette(): CodePalette {
    val dark = isSystemInDarkTheme()
    return remember(dark) { if (dark) CodePalette.Dark else CodePalette.Light }
}

/**
 * A single-pass, dependency-free tokenizer covering the languages in [CodeLang].
 *
 * It is intentionally lexical only — no parser, no grammar. That is enough to color comments,
 * strings, numbers, keywords, call sites and types correctly in the overwhelming majority of real
 * lines, and it degrades to plain text rather than to wrong colors when it meets something exotic.
 */
object CodeHighlighter {

    fun highlight(code: String, lang: CodeLang, palette: CodePalette): AnnotatedString {
        // Past this size the per-keystroke cost in the editor's VisualTransformation starts to show.
        if (code.length > MAX_CHARS) return AnnotatedString(code)
        return when (lang) {
            CodeLang.HTML, CodeLang.XML -> markup(code, palette)
            CodeLang.MARKDOWN -> markdown(code, palette)
            else -> generic(code, spec(lang), palette)
        }
    }

    /* ---------------- generic (curly-brace / script languages) ---------------- */

    private fun generic(code: String, spec: Spec, palette: CodePalette) = buildAnnotatedString {
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]

            // Comments
            val lineComment = spec.lineComments.firstOrNull { code.startsWith(it, i) }
            if (lineComment != null) {
                val end = code.indexOf('\n', i).let { if (it == -1) n else it }
                push(palette.comment, code, i, end)
                i = end
                continue
            }
            val block = spec.blockComment
            if (block != null && code.startsWith(block.first, i)) {
                val close = code.indexOf(block.second, i + block.first.length)
                val end = if (close == -1) n else close + block.second.length
                push(palette.comment, code, i, end)
                i = end
                continue
            }

            // Triple-quoted strings (Python, Kotlin raw strings)
            if (spec.tripleQuotes && (code.startsWith("\"\"\"", i) || code.startsWith("'''", i))) {
                val marker = code.substring(i, i + 3)
                val close = code.indexOf(marker, i + 3)
                val end = if (close == -1) n else close + 3
                push(palette.string, code, i, end)
                i = end
                continue
            }

            // Strings
            if (c in spec.quotes) {
                val end = stringEnd(code, i, c, multiline = c == '`')
                push(palette.string, code, i, end)
                i = end
                continue
            }

            // Numbers
            if (c.isDigit()) {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                push(palette.number, code, i, j)
                i = j
                continue
            }

            // Identifiers / keywords / calls / types
            if (c.isLetter() || c == '_' || c == '@' || c == '#' || c == '$') {
                var j = i
                if (code[j] == '@' || code[j] == '#' || code[j] == '$') j++
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                if (j == i) {
                    push(palette.punctuation, code, i, i + 1)
                    i++
                    continue
                }
                val word = code.substring(i, j)
                // SQL is the one language conventionally written in caps, so its keyword set is
                // matched case-insensitively; doing that everywhere would paint Java's `Class` and
                // Kotlin's `Set` as keywords.
                val isKeyword = word in spec.keywords ||
                    (spec.caseInsensitive && word.lowercase() in spec.keywords)
                val color = when {
                    word.startsWith("@") || word.startsWith("#") -> palette.type
                    isKeyword -> palette.keyword
                    nextNonSpace(code, j) == '(' -> palette.function
                    word.first().isUpperCase() -> palette.type
                    else -> palette.text
                }
                if (color == palette.keyword) {
                    withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) { append(word) }
                } else {
                    withStyle(SpanStyle(color = color)) { append(word) }
                }
                i = j
                continue
            }

            // Everything else: operators, brackets, whitespace
            if (c.isWhitespace()) {
                append(c)
            } else {
                withStyle(SpanStyle(color = palette.punctuation)) { append(c) }
            }
            i++
        }
    }

    /* ---------------- markup (HTML / XML) ---------------- */

    private fun markup(code: String, palette: CodePalette) = buildAnnotatedString {
        var i = 0
        val n = code.length
        while (i < n) {
            if (code.startsWith("<!--", i)) {
                val close = code.indexOf("-->", i)
                val end = if (close == -1) n else close + 3
                push(palette.comment, code, i, end)
                i = end
                continue
            }
            if (code[i] == '<') {
                val close = code.indexOf('>', i)
                val end = if (close == -1) n else close + 1
                appendTag(code.substring(i, end), palette)
                i = end
                continue
            }
            val next = code.indexOf('<', i).let { if (it == -1) n else it }
            withStyle(SpanStyle(color = palette.text)) { append(code, i, next) }
            i = next
        }
    }

    private fun AnnotatedString.Builder.appendTag(tag: String, palette: CodePalette) {
        var i = 0
        val n = tag.length
        // "<", "</", tag name
        while (i < n && (tag[i] == '<' || tag[i] == '/' || tag[i] == '!' || tag[i] == '?')) i++
        withStyle(SpanStyle(color = palette.punctuation)) { append(tag, 0, i) }
        var j = i
        while (j < n && (tag[j].isLetterOrDigit() || tag[j] == '-' || tag[j] == '_' || tag[j] == ':')) j++
        withStyle(SpanStyle(color = palette.tag, fontWeight = FontWeight.Medium)) { append(tag, i, j) }
        i = j
        while (i < n) {
            val c = tag[i]
            when {
                c == '"' || c == '\'' -> {
                    val end = stringEnd(tag, i, c, multiline = true)
                    push(palette.string, tag, i, end)
                    i = end
                }
                c.isLetter() || c == '_' -> {
                    var k = i
                    while (k < n && (tag[k].isLetterOrDigit() || tag[k] == '-' || tag[k] == '_' || tag[k] == ':')) k++
                    push(palette.attribute, tag, i, k)
                    i = k
                }
                c.isWhitespace() -> {
                    append(c); i++
                }
                else -> {
                    withStyle(SpanStyle(color = palette.punctuation)) { append(c) }
                    i++
                }
            }
        }
    }

    /* ---------------- markdown ---------------- */

    private fun markdown(code: String, palette: CodePalette) = buildAnnotatedString {
        var fenced = false
        code.split("\n").forEachIndexed { index, line ->
            if (index > 0) append('\n')
            when {
                line.trimStart().startsWith("```") -> {
                    fenced = !fenced
                    push(palette.comment, line, 0, line.length)
                }
                fenced -> push(palette.string, line, 0, line.length)
                line.startsWith("#") -> withStyle(
                    SpanStyle(color = palette.type, fontWeight = FontWeight.Bold),
                ) { append(line) }
                line.trimStart().startsWith(">") -> push(palette.comment, line, 0, line.length)
                Regex("^\\s*([-*+]|\\d+\\.)\\s").containsMatchIn(line) -> {
                    val marker = line.takeWhile { it.isWhitespace() || it == '-' || it == '*' || it == '+' || it.isDigit() || it == '.' }
                    withStyle(SpanStyle(color = palette.keyword)) { append(marker) }
                    withStyle(SpanStyle(color = palette.text)) { append(line.substring(marker.length)) }
                }
                else -> push(palette.text, line, 0, line.length)
            }
        }
    }

    /* ---------------- helpers ---------------- */

    private fun AnnotatedString.Builder.push(
        color: Color,
        source: String,
        start: Int,
        end: Int,
    ) {
        withStyle(SpanStyle(color = color)) { append(source, start, end) }
    }

    /** Index just past the closing quote (or the line/​buffer end for an unterminated string). */
    private fun stringEnd(code: String, start: Int, quote: Char, multiline: Boolean): Int {
        var i = start + 1
        while (i < code.length) {
            val c = code[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            if (c == '\n' && !multiline) return i
            i++
        }
        return code.length
    }

    private fun nextNonSpace(code: String, from: Int): Char? {
        var i = from
        while (i < code.length && code[i] == ' ') i++
        return code.getOrNull(i)
    }

    private data class Spec(
        val lineComments: List<String>,
        val blockComment: Pair<String, String>?,
        val quotes: Set<Char>,
        val tripleQuotes: Boolean,
        val keywords: Set<String>,
        val caseInsensitive: Boolean = false,
    )

    private fun spec(lang: CodeLang): Spec = when (lang) {
        CodeLang.KOTLIN -> Spec(listOf("//"), "/*" to "*/", setOf('"', '\''), true, KOTLIN_KW)
        CodeLang.JAVA -> Spec(listOf("//"), "/*" to "*/", setOf('"', '\''), false, JAVA_KW)
        CodeLang.PYTHON -> Spec(listOf("#"), null, setOf('"', '\''), true, PYTHON_KW)
        CodeLang.JS, CodeLang.TS -> Spec(listOf("//"), "/*" to "*/", setOf('"', '\'', '`'), false, JS_KW)
        CodeLang.JSON -> Spec(emptyList(), null, setOf('"'), false, setOf("true", "false", "null"))
        CodeLang.CSS -> Spec(emptyList(), "/*" to "*/", setOf('"', '\''), false, CSS_KW)
        CodeLang.SHELL -> Spec(listOf("#"), null, setOf('"', '\''), false, SHELL_KW)
        CodeLang.C -> Spec(listOf("//"), "/*" to "*/", setOf('"', '\''), false, C_KW)
        CodeLang.GO -> Spec(listOf("//"), "/*" to "*/", setOf('"', '\'', '`'), false, GO_KW)
        CodeLang.RUST -> Spec(listOf("//"), "/*" to "*/", setOf('"', '\''), false, RUST_KW)
        CodeLang.SWIFT -> Spec(listOf("//"), "/*" to "*/", setOf('"'), true, SWIFT_KW)
        CodeLang.SQL -> Spec(listOf("--"), "/*" to "*/", setOf('"', '\''), false, SQL_KW, caseInsensitive = true)
        CodeLang.YAML -> Spec(listOf("#"), null, setOf('"', '\''), false, setOf("true", "false", "null", "yes", "no"))
        else -> Spec(listOf("#", "//"), null, setOf('"', '\''), false, emptySet())
    }

    private const val MAX_CHARS = 60_000

    private val KOTLIN_KW = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do",
        "return", "import", "package", "private", "public", "internal", "protected", "override", "open",
        "abstract", "sealed", "data", "enum", "companion", "init", "constructor", "this", "super", "null",
        "true", "false", "is", "as", "in", "out", "by", "get", "set", "suspend", "inline", "lateinit",
        "const", "typealias", "try", "catch", "finally", "throw", "break", "continue", "vararg", "operator",
        "infix", "reified", "crossinline", "noinline", "annotation", "expect", "actual", "it",
    )

    private val JAVA_KW = setOf(
        "public", "private", "protected", "class", "interface", "enum", "extends", "implements", "new",
        "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
        "static", "final", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short",
        "this", "super", "null", "true", "false", "try", "catch", "finally", "throw", "throws", "import",
        "package", "abstract", "synchronized", "volatile", "transient", "instanceof", "record", "var",
    )

    private val PYTHON_KW = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue", "import",
        "from", "as", "pass", "raise", "try", "except", "finally", "with", "lambda", "yield", "global",
        "nonlocal", "assert", "del", "in", "is", "not", "and", "or", "None", "True", "False", "self",
        "async", "await", "match", "case",
    )

    private val JS_KW = setOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case",
        "default", "break", "continue", "class", "extends", "new", "this", "super", "null", "undefined",
        "true", "false", "typeof", "instanceof", "in", "of", "try", "catch", "finally", "throw", "import",
        "export", "from", "async", "await", "yield", "delete", "void", "static", "get", "set",
        "interface", "type", "enum", "implements", "public", "private", "readonly", "namespace",
    )

    private val CSS_KW = setOf(
        "important", "media", "import", "keyframes", "supports", "font-face", "root", "hover", "active",
        "focus", "before", "after", "not", "nth-child", "var", "calc",
    )

    private val SHELL_KW = setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "case", "esac", "function",
        "return", "export", "local", "echo", "cd", "set", "source", "exit",
    )

    private val C_KW = setOf(
        "int", "long", "short", "char", "float", "double", "void", "struct", "union", "enum", "typedef",
        "static", "const", "extern", "return", "if", "else", "for", "while", "do", "switch", "case",
        "default", "break", "continue", "sizeof", "class", "public", "private", "protected", "new",
        "delete", "namespace", "using", "template", "typename", "nullptr", "true", "false", "auto",
    )

    private val GO_KW = setOf(
        "func", "package", "import", "var", "const", "type", "struct", "interface", "map", "chan", "go",
        "defer", "return", "if", "else", "for", "range", "switch", "case", "default", "break", "continue",
        "select", "nil", "true", "false", "string", "int", "error", "make", "len", "append",
    )

    private val RUST_KW = setOf(
        "fn", "let", "mut", "const", "struct", "enum", "impl", "trait", "pub", "use", "mod", "match",
        "if", "else", "for", "while", "loop", "return", "break", "continue", "self", "Self", "None",
        "Some", "Ok", "Err", "true", "false", "async", "await", "move", "ref", "where", "dyn", "crate",
    )

    private val SWIFT_KW = setOf(
        "func", "let", "var", "class", "struct", "enum", "protocol", "extension", "import", "return",
        "if", "else", "guard", "for", "in", "while", "repeat", "switch", "case", "default", "break",
        "continue", "self", "super", "nil", "true", "false", "init", "deinit", "override", "private",
        "public", "internal", "static", "throws", "try", "catch", "async", "await", "some", "any",
    )

    private val SQL_KW = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "drop", "alter", "add", "index", "join", "left", "right", "inner", "outer", "on",
        "group", "by", "order", "having", "limit", "offset", "distinct", "as", "and", "or", "not",
        "null", "primary", "key", "foreign", "references", "unique", "default", "case", "when", "then",
        "else", "end", "union", "all", "exists", "between", "like", "in",
    )
}
