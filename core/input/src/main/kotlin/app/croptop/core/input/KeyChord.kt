package app.croptop.core.input

import android.view.KeyEvent

/**
 * A key plus modifiers, e.g. `Ctrl+Shift+Z`. [keyCode] is an Android `KeyEvent.KEYCODE_*`.
 * Parsing/formatting is pure so keymaps are testable on the JVM.
 */
data class KeyChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    val hasModifiers: Boolean get() = ctrl || shift || alt || meta

    fun format(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        if (meta) append("Meta+")
        append(KeyNames.nameOf(keyCode))
    }

    /** Android meta-state bits for [android.view.KeyboardShortcutInfo]. */
    val metaState: Int
        get() = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
            (if (shift) KeyEvent.META_SHIFT_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON else 0) or
            (if (meta) KeyEvent.META_META_ON else 0)

    override fun toString(): String = format()

    companion object {
        /** Parses "Ctrl+Shift+Z", "Alt+[", "Shift+Backspace", "Ctrl++" (plus key). Null when invalid. */
        fun parse(text: String): KeyChord? {
            val parts = splitChord(text.trim())
            if (parts.isEmpty()) return null
            var ctrl = false
            var shift = false
            var alt = false
            var meta = false
            parts.dropLast(1).forEach {
                when (it.lowercase()) {
                    "ctrl", "control", "cmd" -> ctrl = true
                    "shift" -> shift = true
                    "alt", "option", "opt" -> alt = true
                    "meta", "search", "win", "super" -> meta = true
                    else -> return null
                }
            }
            val key = KeyNames.codeOf(parts.last()) ?: return null
            return KeyChord(key, ctrl, shift, alt, meta)
        }

        private fun splitChord(s: String): List<String> {
            val out = mutableListOf<String>()
            var rest = s
            while (rest.isNotEmpty()) {
                val i = rest.indexOf('+', startIndex = 1)
                if (i < 0) {
                    out += rest
                    break
                }
                out += rest.substring(0, i)
                rest = rest.substring(i + 1)
            }
            return out
        }
    }
}

/** Human names ↔ Android key codes for the keys used in keymaps. */
object KeyNames {
    private val named: Map<String, Int> = buildMap {
        ('A'..'Z').forEachIndexed { i, c -> put(c.toString(), KeyEvent.KEYCODE_A + i) }
        ('0'..'9').forEachIndexed { i, c -> put(c.toString(), KeyEvent.KEYCODE_0 + i) }
        (1..12).forEach { put("F$it", KeyEvent.KEYCODE_F1 + it - 1) }
        put("[", KeyEvent.KEYCODE_LEFT_BRACKET)
        put("]", KeyEvent.KEYCODE_RIGHT_BRACKET)
        put("=", KeyEvent.KEYCODE_EQUALS)
        put("+", KeyEvent.KEYCODE_PLUS)
        put("-", KeyEvent.KEYCODE_MINUS)
        put(";", KeyEvent.KEYCODE_SEMICOLON)
        put("'", KeyEvent.KEYCODE_APOSTROPHE)
        put("\\", KeyEvent.KEYCODE_BACKSLASH)
        put("/", KeyEvent.KEYCODE_SLASH)
        put(",", KeyEvent.KEYCODE_COMMA)
        put(".", KeyEvent.KEYCODE_PERIOD)
        put("`", KeyEvent.KEYCODE_GRAVE)
        put("Space", KeyEvent.KEYCODE_SPACE)
        put("Tab", KeyEvent.KEYCODE_TAB)
        put("Enter", KeyEvent.KEYCODE_ENTER)
        put("Esc", KeyEvent.KEYCODE_ESCAPE)
        put("Backspace", KeyEvent.KEYCODE_DEL)
        put("Del", KeyEvent.KEYCODE_FORWARD_DEL)
        put("Up", KeyEvent.KEYCODE_DPAD_UP)
        put("Down", KeyEvent.KEYCODE_DPAD_DOWN)
        put("Left", KeyEvent.KEYCODE_DPAD_LEFT)
        put("Right", KeyEvent.KEYCODE_DPAD_RIGHT)
        put("Home", KeyEvent.KEYCODE_MOVE_HOME)
        put("End", KeyEvent.KEYCODE_MOVE_END)
        put("PageUp", KeyEvent.KEYCODE_PAGE_UP)
        put("PageDown", KeyEvent.KEYCODE_PAGE_DOWN)
    }
    private val aliases = mapOf("escape" to "Esc", "delete" to "Del", "return" to "Enter", "plus" to "+", "minus" to "-")
    private val byCode: Map<Int, String> = named.entries.associate { (k, v) -> v to k }

    fun codeOf(name: String): Int? {
        val canonical = aliases[name.lowercase()] ?: name
        return named[canonical] ?: named[canonical.uppercase()] ?: named.entries.firstOrNull { it.key.equals(canonical, ignoreCase = true) }?.value
    }

    fun nameOf(code: Int): String = byCode[code] ?: "Key$code"

    /** Letter keys that are safe as single-key tool shortcuts. */
    fun isLetter(code: Int): Boolean = code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
}
