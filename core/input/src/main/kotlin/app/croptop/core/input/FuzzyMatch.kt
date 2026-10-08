package app.croptop.core.input

/**
 * Command-palette matcher. Scores a [query] against a [label]:
 * all query characters must appear in order (case-insensitive). Contiguous runs, word starts and
 * prefix matches score higher; shorter labels win ties. Returns null for no match.
 */
object FuzzyMatch {
    fun score(query: String, label: String): Int? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val l = label.lowercase()
        if (l.startsWith(q)) return 10_000 - l.length
        l.indexOf(q).takeIf { it >= 0 }?.let { at -> return 8_000 - at * 10 - l.length + if (isWordStart(l, at)) 500 else 0 }
        var score = 0
        var li = 0
        var prev = -2
        for (ch in q) {
            if (ch == ' ') continue
            val found = l.indexOf(ch, li)
            if (found < 0) return null
            score += when {
                found == prev + 1 -> 30
                isWordStart(l, found) -> 40
                else -> 5
            }
            prev = found
            li = found + 1
        }
        return score - l.length
    }

    private fun isWordStart(s: String, i: Int) = i == 0 || !s[i - 1].isLetterOrDigit()

    /** Ranks [items] by [label], best first, dropping non-matches. */
    fun <T> rank(query: String, items: List<T>, label: (T) -> String): List<T> =
        items.mapNotNull { item -> score(query, label(item))?.let { item to it } }
            .sortedByDescending { it.second }
            .map { it.first }
}
