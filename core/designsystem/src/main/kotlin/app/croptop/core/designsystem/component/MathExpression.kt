package app.croptop.core.designsystem.component

/**
 * Evaluates the small math expressions allowed in numeric fields, e.g. `120*2`,
 * `(4000-24)/3`, `-5 + 2.5`. Supports + - * / % and parentheses. Returns null on invalid input.
 */
fun evaluateExpression(input: String): Double? = runCatching { ExprParser(input.replace(" ", "")).parse() }.getOrNull()

private class ExprParser(private val s: String) {
    private var i = 0

    fun parse(): Double {
        val v = expr()
        require(i == s.length) { "trailing input" }
        require(v.isFinite()) { "not finite" }
        return v
    }

    private fun expr(): Double {
        var v = term()
        while (i < s.length && (s[i] == '+' || s[i] == '-')) {
            val op = s[i++]
            val r = term()
            v = if (op == '+') v + r else v - r
        }
        return v
    }

    private fun term(): Double {
        var v = factor()
        while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
            val op = s[i++]
            val r = factor()
            v = when (op) {
                '*' -> v * r
                '/' -> v / r
                else -> v % r
            }
        }
        return v
    }

    private fun factor(): Double {
        require(i < s.length) { "unexpected end" }
        return when (s[i]) {
            '-' -> {
                i++
                -factor()
            }

            '+' -> {
                i++
                factor()
            }

            '(' -> {
                i++
                val v = expr()
                require(i < s.length && s[i] == ')') { "missing )" }
                i++
                v
            }

            else -> number()
        }
    }

    private fun number(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
        require(i > start) { "number expected at $start" }
        return s.substring(start, i).replace(',', '.').toDouble()
    }
}
