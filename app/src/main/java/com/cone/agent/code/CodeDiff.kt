package com.cone.agent.code

/**
 * Line diffing for the transcript's change cards.
 *
 * A classic LCS table, which is quadratic — fine for the hand-sized files this feature edits, ruinous
 * for a generated 5,000-line bundle. Past [MAX_CELLS] the diff degrades to "removed everything, added
 * everything" rather than allocating a table big enough to matter on a phone: the card still shows
 * honest +/− counts, it just stops being line-precise.
 */
object CodeDiff {

    fun compute(old: String, new: String, context: Int = 3): List<DiffLine> {
        val a = old.lines()
        val b = new.lines()
        if (old == new) return emptyList()
        val raw = if (a.size.toLong() * b.size.toLong() > MAX_CELLS) coarse(a, b) else lcsDiff(a, b)
        return condense(raw, context)
    }

    /** Counts across the *whole* change, unaffected by the elision [condense] applies for display. */
    fun stats(old: String, new: String): Pair<Int, Int> {
        val a = old.lines()
        val b = new.lines()
        val raw = if (a.size.toLong() * b.size.toLong() > MAX_CELLS) coarse(a, b) else lcsDiff(a, b)
        return raw.count { it.kind == DiffLine.ADDED } to raw.count { it.kind == DiffLine.REMOVED }
    }

    private fun coarse(a: List<String>, b: List<String>): List<DiffLine> =
        a.map { DiffLine(DiffLine.REMOVED, it) } + b.map { DiffLine(DiffLine.ADDED, it) }

    private fun lcsDiff(a: List<String>, b: List<String>): List<DiffLine> {
        val n = a.size
        val m = b.size
        // table[i][j] = LCS length of a[i..] and b[j..], stored flat as (n+1) × (m+1).
        val width = m + 1
        val table = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i * width + j] = if (a[i] == b[j]) {
                    table[(i + 1) * width + (j + 1)] + 1
                } else {
                    maxOf(table[(i + 1) * width + j], table[i * width + (j + 1)])
                }
            }
        }
        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out += DiffLine(DiffLine.CONTEXT, a[i]); i++; j++
                }
                table[(i + 1) * width + j] >= table[i * width + (j + 1)] -> {
                    out += DiffLine(DiffLine.REMOVED, a[i]); i++
                }
                else -> {
                    out += DiffLine(DiffLine.ADDED, b[j]); j++
                }
            }
        }
        while (i < n) out += DiffLine(DiffLine.REMOVED, a[i++])
        while (j < m) out += DiffLine(DiffLine.ADDED, b[j++])
        return out
    }

    /**
     * Keeps [context] unchanged lines either side of each edit and replaces the rest with a gap
     * marker, so a one-line fix in a long file reads as a one-line fix.
     */
    private fun condense(lines: List<DiffLine>, context: Int): List<DiffLine> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { index, line ->
            if (line.kind != DiffLine.CONTEXT) {
                for (k in (index - context).coerceAtLeast(0)..(index + context).coerceAtMost(lines.size - 1)) {
                    keep[k] = true
                }
            }
        }
        val out = ArrayList<DiffLine>()
        var skipped = 0
        lines.forEachIndexed { index, line ->
            if (keep[index]) {
                if (skipped > 0) {
                    out += DiffLine(DiffLine.GAP, "⋯ 省略 $skipped 行")
                    skipped = 0
                }
                out += line
            } else {
                skipped++
            }
        }
        if (skipped > 0) out += DiffLine(DiffLine.GAP, "⋯ 省略 $skipped 行")
        return if (out.size > MAX_DISPLAY_LINES) {
            out.take(MAX_DISPLAY_LINES) + DiffLine(DiffLine.GAP, "⋯ 还有 ${out.size - MAX_DISPLAY_LINES} 行")
        } else {
            out
        }
    }

    private const val MAX_CELLS = 400_000L
    private const val MAX_DISPLAY_LINES = 400
}
