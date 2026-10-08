package app.croptop.core.engine.api

/** What a new layer mask starts as (Layer ▸ Layer Mask). */
enum class MaskFill(val label: String) {
    REVEAL_ALL("Reveal All"),
    HIDE_ALL("Hide All"),
    REVEAL_SELECTION("Reveal Selection"),
    HIDE_SELECTION("Hide Selection"),
    ;

    val needsSelection: Boolean get() = this == REVEAL_SELECTION || this == HIDE_SELECTION
}
