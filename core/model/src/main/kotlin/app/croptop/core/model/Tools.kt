package app.croptop.core.model

/**
 * Editor tools. Tools sharing a [group] cycle with Shift+[key].
 * [key] is the Photoshop-compatible default; remapping lives in the shortcut system.
 */
enum class ToolId(val displayName: String, val group: ToolGroup, val key: Char?) {
    MOVE("Move", ToolGroup.MOVE, 'V'),
    ARTBOARD("Artboard", ToolGroup.MOVE, 'V'),

    RECT_MARQUEE("Rectangular Marquee", ToolGroup.MARQUEE, 'M'),
    ELLIPSE_MARQUEE("Elliptical Marquee", ToolGroup.MARQUEE, 'M'),

    LASSO("Lasso", ToolGroup.LASSO, 'L'),
    POLYGONAL_LASSO("Polygonal Lasso", ToolGroup.LASSO, 'L'),
    MAGNETIC_LASSO("Magnetic Lasso", ToolGroup.LASSO, 'L'),

    OBJECT_SELECT("Object Selection", ToolGroup.QUICK_SELECT, 'W'),
    QUICK_SELECT("Quick Selection", ToolGroup.QUICK_SELECT, 'W'),
    MAGIC_WAND("Magic Wand", ToolGroup.QUICK_SELECT, 'W'),

    CROP("Crop", ToolGroup.CROP, 'C'),
    PERSPECTIVE_CROP("Perspective Crop", ToolGroup.CROP, 'C'),

    EYEDROPPER("Eyedropper", ToolGroup.EYEDROPPER, 'I'),

    SPOT_HEALING("Spot Healing Brush", ToolGroup.HEALING, 'J'),
    AI_REMOVE("Remove", ToolGroup.HEALING, 'J'),
    HEALING("Healing Brush", ToolGroup.HEALING, 'J'),
    PATCH("Patch", ToolGroup.HEALING, 'J'),
    RED_EYE("Red Eye", ToolGroup.HEALING, 'J'),

    BRUSH("Brush", ToolGroup.BRUSH, 'B'),
    CLONE_STAMP("Clone Stamp", ToolGroup.CLONE, 'S'),
    HISTORY_BRUSH("History Brush", ToolGroup.HISTORY, 'Y'),

    ERASER("Eraser", ToolGroup.ERASER, 'E'),
    BACKGROUND_ERASER("Background Eraser", ToolGroup.ERASER, 'E'),

    GRADIENT("Gradient", ToolGroup.FILL, 'G'),
    PAINT_BUCKET("Paint Bucket", ToolGroup.FILL, 'G'),

    BLUR("Blur", ToolGroup.FOCUS, null),
    SHARPEN("Sharpen", ToolGroup.FOCUS, null),
    SMUDGE("Smudge", ToolGroup.FOCUS, null),

    LIQUIFY("Liquify", ToolGroup.LIQUIFY, null),

    DODGE("Dodge", ToolGroup.TONING, 'O'),
    BURN("Burn", ToolGroup.TONING, 'O'),
    SPONGE("Sponge", ToolGroup.TONING, 'O'),

    PEN("Pen", ToolGroup.PEN, 'P'),
    TEXT("Text", ToolGroup.TEXT, 'T'),
    PATH_SELECT("Path Selection", ToolGroup.PATH_SELECT, 'A'),
    DIRECT_SELECT("Direct Selection", ToolGroup.PATH_SELECT, 'A'),

    RECTANGLE("Rectangle", ToolGroup.SHAPE, 'U'),
    ELLIPSE("Ellipse", ToolGroup.SHAPE, 'U'),
    POLYGON("Polygon", ToolGroup.SHAPE, 'U'),
    LINE("Line", ToolGroup.SHAPE, 'U'),

    HAND("Hand", ToolGroup.NAVIGATE, 'H'),
    ROTATE_VIEW("Rotate View", ToolGroup.NAVIGATE, 'R'),
    ZOOM("Zoom", ToolGroup.ZOOM, 'Z'),
    ;

    companion object {
        fun inGroup(group: ToolGroup): List<ToolId> = entries.filter { it.group == group }

        /** Next tool in the same group — Shift+key cycling. */
        fun cycle(from: ToolId): ToolId {
            val members = inGroup(from.group).filter { it.key == from.key }
            return members[(members.indexOf(from) + 1) % members.size]
        }
    }
}

/** Toolbar slots, in toolbar order. */
enum class ToolGroup {
    MOVE,
    MARQUEE,
    LASSO,
    QUICK_SELECT,
    CROP,
    EYEDROPPER,
    HEALING,
    BRUSH,
    CLONE,
    HISTORY,
    ERASER,
    FILL,
    FOCUS,
    LIQUIFY,
    TONING,
    PEN,
    TEXT,
    PATH_SELECT,
    SHAPE,
    NAVIGATE,
    ZOOM,
}
