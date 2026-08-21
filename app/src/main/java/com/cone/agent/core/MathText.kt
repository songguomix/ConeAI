package com.cone.agent.core

/**
 * Converts the LaTeX fragments LLMs habitually emit in math answers（`$x^2$`、`\frac{1}{2}`、
 * `\times`…）into plain Unicode（x²、1/2、×）before display. The app's Markdown renderer and the
 * assistant pill's TextView show text verbatim — without this pass a math answer reads as raw
 * backslash soup. Fenced ``` blocks and inline `code` spans are left untouched, so a model that is
 * *teaching* LaTeX still shows it literally. Unknown commands stay as-is (same philosophy as the
 * Markdown renderer: never destroy content we don't understand).
 */
object MathText {

    fun normalize(markdown: String): String {
        // Fast path: nothing math-ish anywhere.
        if ('\\' !in markdown && '$' !in markdown && '^' !in markdown) return markdown
        val out = StringBuilder(markdown.length)
        var inFence = false
        val lines = markdown.replace("\r\n", "\n").split("\n")
        lines.forEachIndexed { i, line ->
            if (i > 0) out.append('\n')
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                out.append(line)
            } else if (inFence) {
                out.append(line)
            } else {
                out.append(normalizeLine(line))
            }
        }
        return out.toString()
    }

    /** Normalizes one line, preserving inline `code` spans verbatim. */
    private fun normalizeLine(line: String): String {
        if ('`' !in line) return normalizeUrlSafe(line)
        // Even-indexed pieces are outside backticks; odd ones are code and stay untouched.
        return line.split('`').mapIndexed { i, seg ->
            if (i % 2 == 0) normalizeUrlSafe(seg) else seg
        }.joinToString("`")
    }

    /**
     * Normalizes everything except Markdown link/image targets and bare URLs, which are copied
     * through byte for byte.
     *
     * A URL is full of characters this file rewrites: `_` before a digit reads as a subscript, so
     * `.../my_image_2.png` became `.../my_image₂.png` and the picture simply failed to load. It bites
     * hardest exactly where images matter most — base64url payloads (mermaid.ink) are built from an
     * alphabet that includes `_` and `-`, and QuickChart configs are dense with `{}` and `%` escapes.
     */
    private fun normalizeUrlSafe(segment: String): String {
        if (segment.isEmpty()) return segment
        val out = StringBuilder(segment.length)
        var cursor = 0
        for (m in URL_SPAN.findAll(segment)) {
            if (m.range.first > cursor) out.append(normalizeSegment(segment.substring(cursor, m.range.first)))
            out.append(m.value)
            cursor = m.range.last + 1
        }
        if (cursor < segment.length) out.append(normalizeSegment(segment.substring(cursor)))
        return out.toString()
    }

    private fun normalizeSegment(input: String): String {
        var s = input
        s = stripDollars(s)
        // Chemistry first: inside \ce{…} the digits are subscripts by position, with none of the
        // `_` markers the general passes below look for.
        s = replaceChemistry(s)
        s = replaceReactionArrows(s)
        s = s.replace("\\[", "").replace("\\]", "").replace("\\(", "").replace("\\)", "")
        s = s.replace("^{\\circ}", "°").replace("^\\circ", "°")
        s = ENVIRONMENT.replace(s, "")
        s = unwrapWrappers(s)
        s = replaceFractions(s)
        s = replaceRoots(s)
        // LaTeX spacing / line-break helpers → plain spacing.
        s = s.replace("\\quad", "  ").replace("\\qquad", "    ")
            .replace("\\,", " ").replace("\\;", " ").replace("\\:", " ").replace("\\!", "")
        s = COMMAND.replace(s) { m -> SYMBOLS[m.groupValues[1]] ?: m.value }
        s = replaceScripts(s)
        // Literal escapes last, so an unescaped `_` can't re-enter the subscript pass above.
        s = s.replace("\\%", "%").replace("\\#", "#").replace("\\&", "&")
            .replace("\\_", "_").replace("\\{", "{").replace("\\}", "}").replace("\\$", "$")
        return s
    }

    /**
     * Removes `$…$` / `$$…$$` delimiters around math-looking content. Currency stays: `$5`、
     * `约 $5-$10` never match（no letter/backslash/script char between the dollars, or unbalanced）.
     */
    private fun stripDollars(s: String): String {
        if ('$' !in s) return s
        var out = s.replace(DISPLAY_DOLLARS) { it.groupValues[1] }
        out = out.replace(INLINE_DOLLARS) { m ->
            val body = m.groupValues[1]
            // Math variables are ASCII letters / LaTeX commands; CJK between dollar amounts
            // （「在$5和$10之间」）must NOT count as math.
            val mathy = body.any { it == '\\' || it == '^' || it == '_' || it in 'a'..'z' || it in 'A'..'Z' }
            if (mathy && body.isNotBlank() && !body.first().isWhitespace() && !body.last().isWhitespace()) body else m.value
        }
        // A line holding only the $$ fence of a multi-line display block.
        return if (out.trim() == "$$") "" else out
    }

    /** `\text{x}` / `\mathrm{x}` / `\boxed{x}` … → x（innermost-out, bounded loop）. */
    private fun unwrapWrappers(input: String): String {
        var s = input
        repeat(5) {
            val next = WRAPPER.replace(s) { it.groupValues[2] }
            if (next == s) return s
            s = next
        }
        return s
    }

    /** `\frac{a}{b}` → `a/b`（complex operands get parentheses）; handles nesting via re-scan. */
    private fun replaceFractions(input: String): String {
        var s = input
        repeat(8) {
            val at = FRAC_HEAD.find(s) ?: return s
            val open1 = at.range.last + 1
            if (open1 >= s.length || s[open1] != '{') return s
            val close1 = matchBrace(s, open1)
            if (close1 < 0) return s
            val open2 = close1 + 1
            if (open2 >= s.length || s[open2] != '{') return s
            val close2 = matchBrace(s, open2)
            if (close2 < 0) return s
            val a = s.substring(open1 + 1, close1)
            val b = s.substring(open2 + 1, close2)
            s = s.substring(0, at.range.first) + "${wrap(a)}/${wrap(b)}" + s.substring(close2 + 1)
        }
        return s
    }

    /**
     * mhchem `\ce{…}` → plain Unicode chemistry. Models reach for this package constantly in
     * chemistry answers, and none of the general LaTeX passes touch it: inside `\ce{}` a digit is a
     * subscript purely by position（`H2SO4`）, so without this the whole formula shows as raw source.
     *
     * Handles what LLM answers actually contain: subscripts, charges（`^2+`）, the three reaction
     * arrows, hydrate dots and state symbols. Anything else inside the braces is passed through.
     */
    private fun replaceChemistry(input: String): String {
        if ("\\ce" !in input && "\\pu" !in input) return input
        var s = input
        repeat(8) {
            val at = CE_HEAD.find(s) ?: return s
            val open = at.range.last + 1
            if (open >= s.length || s[open] != '{') return s
            val close = matchBrace(s, open)
            if (close < 0) return s
            s = s.substring(0, at.range.first) +
                formatChemistry(s.substring(open + 1, close)) +
                s.substring(close + 1)
        }
        return s
    }

    /** The body of a `\ce{}` group: positional subscripts, charges, arrows. */
    private fun formatChemistry(body: String): String {
        var s = body
        // Arrows before anything else — their characters (`-`, `>`, `=`) also appear in charges.
        s = s.replace("<=>>", "⇌").replace("<<=>", "⇌")
            .replace("<=>", "⇌").replace("<->", "↔")
            .replace("->", "→").replace("<-", "←")
        s = s.replace("*", "·")
        // Charges: `^2+` / `^-` / `^{2-}`. Done before positional subscripts so the digits of a
        // charge aren't demoted into subscripts.
        s = CE_CHARGE.replace(s) { m ->
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
            mapScript(raw, SUPERSCRIPT) ?: "^$raw"
        }
        // Bare ion charges（`Na+`, `Cl-`, `NH4+`）. Told apart from the `+` joining reactants by
        // adjacency: a charge is written flush against its formula, a reaction plus has spaces
        // around it（`2H2 + O2`）and anything followed by more formula（`B+C`）is a plus too.
        s = CE_BARE_CHARGE.replace(s) { m ->
            m.groupValues[1] + (mapScript(m.groupValues[2], SUPERSCRIPT) ?: m.groupValues[2])
        }
        // A digit right after an element letter or a closing bracket is a subscript; a digit at the
        // start of a term is a coefficient and must stay full-size（`2H2O`）.
        s = CE_SUBSCRIPT.replace(s) { m ->
            m.groupValues[1] + (mapScript(m.groupValues[2], SUBSCRIPT) ?: m.groupValues[2])
        }
        return s
    }

    /** Reaction arrows with conditions above them: `\xrightarrow{催化剂}` → `—催化剂→`. */
    private fun replaceReactionArrows(input: String): String {
        if ("\\xrightarrow" !in input && "\\xleftarrow" !in input && "\\overset" !in input) return input
        var s = input
        s = XARROW.replace(s) { m ->
            val condition = m.groupValues[2].trim()
            val arrow = if (m.groupValues[1] == "xleftarrow") "←" else "→"
            if (condition.isEmpty()) arrow else if (arrow == "→") "—$condition→" else "←$condition—"
        }
        // `\overset{条件}{\rightarrow}` — the same idea written the long way.
        s = OVERSET.replace(s) { m ->
            val condition = m.groupValues[1].trim()
            if (condition.isEmpty()) "→" else "—$condition→"
        }
        return s
    }

    /** `\sqrt{x}` → `√(x)`（simple operand → `√x`）; `\sqrt[3]{x}` → `∛(x)`. */
    private fun replaceRoots(input: String): String {
        var s = input
        repeat(8) {
            val at = SQRT_HEAD.find(s) ?: return s
            var i = at.range.last + 1
            var radical = "√"
            if (i < s.length && s[i] == '[') {
                val closeIdx = s.indexOf(']', i)
                if (closeIdx < 0) return s
                radical = when (s.substring(i + 1, closeIdx).trim()) {
                    "2", "" -> "√"
                    "3" -> "∛"
                    "4" -> "∜"
                    else -> "√[${s.substring(i + 1, closeIdx).trim()}]"
                }
                i = closeIdx + 1
            }
            if (i >= s.length || s[i] != '{') return s
            val close = matchBrace(s, i)
            if (close < 0) return s
            val body = s.substring(i + 1, close)
            val rendered = if (isSimpleOperand(body)) "$radical$body" else "$radical(${body.trim()})"
            s = s.substring(0, at.range.first) + rendered + s.substring(close + 1)
        }
        return s
    }

    /** `x^2`/`x^{10}` → x²/x¹⁰，`a_1`/`a_{n}` → a₁/aₙ; falls back to `^( )` when unmappable. */
    private fun replaceScripts(input: String): String {
        var s = input
        s = BRACED_SUP.replace(s) { m -> mapScript(m.groupValues[1], SUPERSCRIPT) ?: "^(${m.groupValues[1]})" }
        s = BRACED_SUB.replace(s) { m -> mapScript(m.groupValues[1], SUBSCRIPT) ?: "_(${m.groupValues[1]})" }
        s = BARE_SUP.replace(s) { m ->
            val ch = m.groupValues[2]
            m.groupValues[1] + (mapScript(ch, SUPERSCRIPT) ?: "^$ch")
        }
        // Bare `_x` only when it can't be prose（snake_case 的下划线后面跟着更多字母，不动）.
        s = BARE_SUB.replace(s) { m ->
            val ch = m.groupValues[2]
            m.groupValues[1] + (mapScript(ch, SUBSCRIPT) ?: "_$ch")
        }
        return s
    }

    private fun mapScript(body: String, table: Map<Char, Char>): String? {
        val text = body.trim()
        if (text.isEmpty()) return null
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(table[c] ?: return null)
        return sb.toString()
    }

    private fun wrap(operand: String): String {
        val t = operand.trim()
        return if (isSimpleOperand(t)) t else "($t)"
    }

    /** A single token（`2`、`x`、`12`、`π`、`ab`）needs no parentheses around it. */
    private fun isSimpleOperand(s: String): Boolean =
        s.isNotEmpty() && s.length <= 4 && s.all { it.isLetterOrDigit() }

    /** Index of the '}' matching the '{' at [open], honoring nesting; -1 when unbalanced. */
    private fun matchBrace(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private val DISPLAY_DOLLARS = Regex("\\$\\$([^$]+)\\$\\$")
    private val INLINE_DOLLARS = Regex("\\$([^$\\n]+)\\$")
    private val ENVIRONMENT = Regex("\\\\(?:begin|end)\\{[a-zA-Z*]+\\}")
    private val WRAPPER = Regex("\\\\(text|textbf|textit|mathrm|mathbf|mathit|mathcal|operatorname|boxed)\\{([^{}]*)\\}")
    private val FRAC_HEAD = Regex("\\\\[dt]?frac")
    private val SQRT_HEAD = Regex("\\\\sqrt")
    private val COMMAND = Regex("\\\\([a-zA-Z]+)")
    private val BRACED_SUP = Regex("\\^\\{([^{}]*)\\}")
    private val BRACED_SUB = Regex("_\\{([^{}]*)\\}")
    // Multi-digit, so `x^10` / `C_12` map whole（a single-char match left the rest full-size:
    // `x^10` used to render `x¹0`）.
    private val BARE_SUP = Regex("([\\p{L}\\p{N})\\]∫∑∏√°′])\\^([0-9]+|[ni+-])")

    /**
     * The trailing guard blocks *lowercase* letters, digits and `_` rather than all letters. Barring
     * every letter also barred chemistry — in `H_2O` / `Fe_2O_3` the subscript is followed by the
     * next element symbol, so the most common formulas in the app never converted at all. Element
     * symbols are capitalised and identifiers are not, which separates the two cases cleanly:
     * `H_2O` → H₂O, while `my_var_1x` and `user_1_name` stay untouched.
     */
    private val BARE_SUB = Regex("([\\p{L}\\p{N})\\]∫∑∏√°′])_([0-9]+)(?![\\p{Ll}\\p{N}_])")

    /**
     * A Markdown link/image（`[t](url)` / `![alt](url)`）or a bare http(s) URL — matched as one unit
     * so its inner text is never rewritten.
     */
    private val URL_SPAN = Regex("!?\\[[^\\]]*\\]\\([^)\\s]*\\)|https?://[^\\s)\\]<>\"']+")

    private val CE_HEAD = Regex("\\\\(?:ce|pu)")
    private val CE_CHARGE = Regex("\\^\\{([^{}]*)\\}|\\^([0-9]*[+-])")
    private val CE_BARE_CHARGE = Regex("([A-Za-z0-9\\)\\]])([+-]{1,3})(?![A-Za-z0-9])")
    private val CE_SUBSCRIPT = Regex("([A-Za-z\\)\\]])([0-9]+)")
    private val XARROW = Regex("\\\\(xrightarrow|xleftarrow)(?:\\[[^\\]]*\\])?\\{([^{}]*)\\}")
    private val OVERSET = Regex("\\\\overset\\{([^{}]*)\\}\\{\\\\(?:rightarrow|to|longrightarrow)\\}")

    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶',
        '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '=' to '⁼',
        '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ',
    )

    private val SUBSCRIPT = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆',
        '7' to '₇', '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌',
        '(' to '₍', ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ',
        'h' to 'ₕ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ',
    )

    /** LaTeX command → Unicode. Unknown commands pass through untouched. */
    private val SYMBOLS = mapOf(
        // 运算与关系
        "times" to "×", "div" to "÷", "cdot" to "·", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝", "infty" to "∞",
        "ll" to "≪", "gg" to "≫",
        // 箭头
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
        "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "iff" to "⇔",
        "mapsto" to "↦", "implies" to "⇒",
        // 化学反应式常用：可逆反应、长箭头、气体与沉淀符号
        "rightleftharpoons" to "⇌", "leftrightharpoons" to "⇌", "rightleftarrows" to "⇄",
        "longrightarrow" to "→", "longleftarrow" to "←", "longleftrightarrow" to "↔",
        "Longrightarrow" to "⇒", "Longleftarrow" to "⇐",
        "uparrow" to "↑", "downarrow" to "↓", "Uparrow" to "⇑", "Downarrow" to "⇓",
        // 集合与逻辑
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
        "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
        "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨",
        "setminus" to "∖",
        // 微积分与大型运算符
        "sum" to "∑", "prod" to "∏", "int" to "∫", "iint" to "∬", "oint" to "∮",
        "partial" to "∂", "nabla" to "∇", "lim" to "lim",
        // 几何
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "triangle" to "△", "degree" to "°",
        "circ" to "∘",
        // 希腊字母
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "Gamma" to "Γ", "delta" to "δ",
        "Delta" to "Δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "Theta" to "Θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ",
        "Lambda" to "Λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "Xi" to "Ξ", "pi" to "π",
        "Pi" to "Π", "rho" to "ρ", "sigma" to "σ", "Sigma" to "Σ", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "Phi" to "Φ", "varphi" to "φ", "chi" to "χ",
        "psi" to "ψ", "Psi" to "Ψ", "omega" to "ω", "Omega" to "Ω",
        // 函数名（保持原文，去掉反斜杠）
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot", "sec" to "sec",
        "csc" to "csc", "arcsin" to "arcsin", "arccos" to "arccos", "arctan" to "arctan",
        "log" to "log", "ln" to "ln", "lg" to "lg", "exp" to "exp", "max" to "max",
        "min" to "min", "gcd" to "gcd", "det" to "det", "dim" to "dim", "deg" to "deg",
        "bmod" to "mod", "pmod" to "mod",
        // 其它
        "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮", "prime" to "′",
        "ell" to "ℓ", "hbar" to "ℏ", "aleph" to "ℵ", "Re" to "ℜ", "Im" to "ℑ",
        "left" to "", "right" to "", "displaystyle" to "", "limits" to "",
    )
}
