package app.croptop.core.input

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a shortcut is active. Develop and Library reuse letters the Editor binds to tools. */
enum class ShortcutScope { GLOBAL, EDITOR, DEVELOP, LIBRARY }

val ActionCategory.scope: ShortcutScope
    get() = when (this) {
        ActionCategory.APP -> ShortcutScope.GLOBAL
        ActionCategory.DEVELOP -> ShortcutScope.DEVELOP
        ActionCategory.LIBRARY -> ShortcutScope.LIBRARY
        else -> ShortcutScope.EDITOR
    }

@Serializable
data class KeymapFile(val name: String, val bindings: Map<String, List<String>>)

enum class ShortcutPreset(val label: String) {
    PHOTOSHOP("Photoshop-compatible"),
    AFFINITY_PHOTO("Affinity Photo 2"),
    CUSTOM("Custom"),
}

/** Immutable action → chords map. Unknown actions/unparseable chords are reported, not thrown. */
data class Keymap(val name: String, val bindings: Map<ActionId, List<KeyChord>>, val problems: List<String> = emptyList()) {

    fun chordsFor(id: ActionId): List<KeyChord> = bindings[id].orEmpty()

    fun primaryChord(id: ActionId): KeyChord? = chordsFor(id).firstOrNull()

    /** Actions whose bindings differ from [base] (for "modified" markers and minimal exports). */
    fun differencesFrom(base: Keymap): Set<ActionId> =
        (bindings.keys + base.bindings.keys).filterTo(mutableSetOf()) { chordsFor(it) != base.chordsFor(it) }

    /** Actions (other than [except]) that already use [chord] in a scope overlapping [except]'s. */
    fun actionsUsing(chord: KeyChord, except: ActionId): List<ActionId> {
        val scope = Actions[except]?.category?.scope ?: return emptyList()
        return bindings.filter { (id, chords) ->
            id != except && chord in chords &&
                Actions[id]?.category?.scope?.let { it == scope || it == ShortcutScope.GLOBAL || scope == ShortcutScope.GLOBAL } == true
        }.keys.toList()
    }

    /** Replace the chords of [id] (user remap). An empty list unbinds. */
    fun rebind(id: ActionId, chords: List<KeyChord>): Keymap = copy(bindings = bindings + (id to chords))

    fun toFile(): KeymapFile = KeymapFile(name, bindings.mapKeys { it.key.id }.mapValues { e -> e.value.map { it.format() } })

    fun toJson(): String = json.encodeToString(KeymapFile.serializer(), toFile())

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        fun fromJson(text: String): Keymap = fromFile(json.decodeFromString(KeymapFile.serializer(), text))

        fun fromFile(file: KeymapFile): Keymap {
            val problems = mutableListOf<String>()
            val map = file.bindings.mapNotNull { (id, chords) ->
                val action = ActionId(id)
                if (Actions[action] == null) {
                    problems += "Unknown action '$id'"
                    return@mapNotNull null
                }
                action to chords.mapNotNull { c -> KeyChord.parse(c) ?: null.also { problems += "Bad chord '$c' for '$id'" } }
            }.toMap()
            return Keymap(file.name, map, problems)
        }

        /** The bundled Photoshop-compatible default. */
        fun default(): Keymap {
            val text = Keymap::class.java.getResourceAsStream("/keymaps/croptop.json")!!.bufferedReader().use { it.readText() }
            return fromJson(text)
        }
    }
}

/** Applies documented Affinity Photo 2 bindings that do not collide with CropTop's existing shortcuts. */
fun Keymap.withAffinityPhoto2Shortcuts(): Keymap {
    val documentedOverrides = mapOf(
        ActionId("tool.CLONE") to "K",
        ActionId("app.exportAs") to "Ctrl+Alt+Shift+S",
        ActionId("edit.fill") to "F5",
    )
    return documentedOverrides.entries.fold(this) { keymap, (action, chord) ->
        keymap.rebind(action, listOf(requireNotNull(KeyChord.parse(chord))))
    }
}

/** Two actions sharing a chord in overlapping scopes. */
data class ShortcutConflict(val chord: KeyChord, val actions: List<ActionId>)

fun Keymap.conflicts(): List<ShortcutConflict> {
    val entries = bindings.flatMap { (id, chords) -> chords.map { Triple(it, id, Actions[id]!!.category.scope) } }
    return entries.groupBy { it.first }.mapNotNull { (chord, list) ->
        val clashing = list.filter { a -> list.any { b -> a !== b && overlaps(a.third, b.third) } }
        if (clashing.size > 1) ShortcutConflict(chord, clashing.map { it.second }.distinct()) else null
    }
}

private fun overlaps(a: ShortcutScope, b: ShortcutScope) = a == b || a == ShortcutScope.GLOBAL || b == ShortcutScope.GLOBAL
