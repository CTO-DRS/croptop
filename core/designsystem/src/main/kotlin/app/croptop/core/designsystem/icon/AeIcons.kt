package app.croptop.core.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** CropTop icon set v1 — 60 icons. No Material icons in the chrome. */
@Suppress("MaxLineLength")
object AeIcons {
    // ---- Tools ----
    val Move: ImageVector by lazy {
        aeIcon("Move", "M10 2.5v15M2.5 10h15", "M7.5 5 10 2.5 12.5 5M7.5 15l2.5 2.5 2.5-2.5M5 7.5 2.5 10 5 12.5M15 7.5l2.5 2.5-2.5 2.5")
    }
    val MarqueeRect: ImageVector by lazy {
        aeIcon("MarqueeRect", "M3.5 3.5h2M8 3.5h4M14.5 3.5h2v2M16.5 8v4M16.5 14.5v2h-2M12 16.5H8M5.5 16.5h-2v-2M3.5 12V8M3.5 5.5v-2")
    }
    val MarqueeEllipse: ImageVector by lazy { aeIcon("MarqueeEllipse", "M10 3.5a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13z") }
    val Lasso: ImageVector by lazy {
        aeIcon(
            "Lasso",
            "M5.5 13.5C3.3 12.4 2.5 10.9 2.5 9.3 2.5 5.6 6 3 10.3 3s7.2 2.3 7.2 5.4c0 3.5-3.6 5.6-7.6 5.6-1.2 0-2.4-.2-3.4-.5",
            "M6.5 12.5c-1.4.5-1.6 2.3-.3 2.8 1.1.4 1.7 1.3 1.3 2.2",
        )
    }
    val PolygonLasso: ImageVector by lazy { aeIcon("PolygonLasso", "M3 7.5 9 3l8 3.5-2.5 7.5H6.5z") }
    val QuickSelect: ImageVector by lazy {
        aeIcon("QuickSelect", "M11.5 8.5 4 16", "M14.5 3.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z", "M16 13l1.5 1.5M17 10.5h1")
    }
    val MagicWand: ImageVector by lazy {
        aeIcon(
            "MagicWand",
            "M3 17 12 8",
            "M11 9l-1.2-1.2L12 5.6 13.2 6.8z",
            "M15.5 2.5v2M14.5 3.5h2M16.5 8.5v1.5M15.75 9.25h1.5M8.5 3v1.5M7.75 3.75h1.5",
        )
    }
    val ObjectSelect: ImageVector by lazy {
        aeIcon(
            "ObjectSelect",
            "M3 6V3h3M14 3h3v3M17 14v3h-3M6 17H3v-3",
            "M7 12.5c0-2 1.3-3 3-3s3 1 3 3v1H7z",
            "M10 5.5a1.75 1.75 0 1 1 0 3.5 1.75 1.75 0 0 1 0-3.5z",
        )
    }
    val Crop: ImageVector by lazy { aeIcon("Crop", "M5.5 2.5v12h12", "M2.5 5.5h12v12") }
    val PerspectiveCrop: ImageVector by lazy { aeIcon("PerspectiveCrop", "M5 4.5h10l2 11H3z", "M8 4.5 7 15.5M12 4.5l1 11") }
    val Eyedropper: ImageVector by lazy { aeIcon("Eyedropper", "M11.5 5.5l3 3L7 16H4v-3z", "M13 4a2.1 2.1 0 0 1 3 3l-1.5 1.5-3-3z") }
    val Healing: ImageVector by lazy {
        aeIcon(
            "Healing",
            "M4.3 12.2 12.2 4.3a2.6 2.6 0 0 1 3.5 3.5l-7.9 7.9a2.6 2.6 0 0 1-3.5-3.5z",
            "M8 8l4 4",
            fills = listOf("M9.2 9.9a.7.7 0 1 1 0 .01zM10.8 11.5a.7.7 0 1 1 0 .01z"),
        )
    }
    val Remove: ImageVector by lazy {
        aeIcon("Remove", "M4 16c2-1 3-3 5-3s2.5 2 4.5 1.5", "M13 3.5 16.5 7 10 13.5 6.5 10z", "M3.5 4.5l1 1M6 2.5v1.3M2.5 7h1.3")
    }
    val Brush: ImageVector by lazy {
        aeIcon("Brush", "M16.5 3.5 9.5 11", "M7.8 11.4c1.2-.3 2.2.6 2 1.9-.3 2.1-2.6 3.3-6.3 3.2.9-.9.8-2 1.3-3.2.5-1.1 1.7-1.7 3-1.9z")
    }
    val CloneStamp: ImageVector by lazy {
        aeIcon(
            "CloneStamp",
            "M8 9.5V7.2A2.2 2.2 0 0 1 10 3a2.2 2.2 0 0 1 2 4.2v2.3",
            "M4 12a2.5 2.5 0 0 1 2.5-2.5h7A2.5 2.5 0 0 1 16 12v1.5H4z",
            "M4.5 16.5h11",
        )
    }
    val HistoryBrush: ImageVector by lazy { aeIcon("HistoryBrush", "M3 10a7 7 0 1 0 2-4.9", "M3 3v3h3", "M10 6.5V10l2.5 1.5") }
    val Eraser: ImageVector by lazy {
        aeIcon(
            "Eraser",
            "M8 16.5h8.5",
            "M3.9 12.6 11.6 4.9a1.5 1.5 0 0 1 2.1 0l2.4 2.4a1.5 1.5 0 0 1 0 2.1L9 16.5H7.8L3.9 12.6a1.5 1.5 0 0 1 0-2.1z",
            "M7.5 8.5l4 4",
        )
    }
    val Gradient: ImageVector by lazy {
        aeIcon("Gradient", "M3.5 3.5h13v13h-13z", fills = listOf("M6 5h1.5v10H6zM9 5h1.2v10H9zM11.8 5h.9v10h-.9zM14.2 5h.6v10h-.6z"))
    }
    val PaintBucket: ImageVector by lazy {
        aeIcon(
            "PaintBucket",
            "M9 3.5 15.5 10 10 15.5a1.4 1.4 0 0 1-2 0L3.5 11a1.4 1.4 0 0 1 0-2z",
            "M3.5 10h12",
            "M17 13.5s1.2 1.6 1.2 2.4a1.2 1.2 0 0 1-2.4 0c0-.8 1.2-2.4 1.2-2.4z",
        )
    }
    val Blur: ImageVector by lazy { aeIcon("Blur", "M10 2.5s5.5 5.8 5.5 9.5a5.5 5.5 0 0 1-11 0c0-3.7 5.5-9.5 5.5-9.5z") }
    val Smudge: ImageVector by lazy {
        aeIcon(
            "Smudge",
            "M7 17.5V9a1.5 1.5 0 0 1 3 0v3.5",
            "M10 8.5a1.5 1.5 0 0 1 3 0V12M13 9.5a1.5 1.5 0 0 1 3 0v3.5a5 5 0 0 1-5 5H9.5",
            "M7 11.5a1.5 1.5 0 0 0-3 0V13a5 5 0 0 0 1.5 3.5",
        )
    }
    val Liquify: ImageVector by lazy {
        aeIcon(
            "Liquify",
            "M3 10c2.2-4 4.2-5.8 7-5.8 3 0 4.4 2.4 7 1.8",
            "M17 6l.2 3-2.8-.7",
            "M17 10c-2.2 4-4.2 5.8-7 5.8-3 0-4.4-2.4-7-1.8",
            "M3 14l-.2-3 2.8.7",
        )
    }
    val Dodge: ImageVector by lazy { aeIcon("Dodge", "M10 9.5a4 4 0 1 1 0-.01z", "M10 13.5v4") }
    val Burn: ImageVector by lazy {
        aeIcon(
            "Burn",
            "M6.5 17.5c-1.5-1-2.5-2.6-2.5-4.5C4 9 10 2.5 10 2.5S16 9 16 13c0 1.9-1 3.5-2.5 4.5",
            "M8.2 17.5c-.7-.6-1.2-1.4-1.2-2.4 0-1.9 3-5 3-5s3 3.1 3 5c0 1-.5 1.8-1.2 2.4",
        )
    }
    val Pen: ImageVector by lazy {
        aeIcon("Pen", "M10 2.5 15 9l-2.5 6.5h-5L5 9z", "M10 2.5V9", "M7 17.5h6", fills = listOf("M9 9.8a1 1 0 1 1 2 0 1 1 0 0 1-2 0z"))
    }
    val Text: ImageVector by lazy { aeIcon("Text", "M4 5V3.5h12V5M10 3.5v13M7.5 16.5h5") }
    val PathSelect: ImageVector by lazy {
        aeIcon("PathSelect", "M5 2.5v13.5l3.5-3.5 2.5 5 2-1-2.5-4.8h5z", fills = listOf("M5 2.5v13.5l3.5-3.5 2.5 5 2-1-2.5-4.8h5z"))
    }
    val DirectSelect: ImageVector by lazy { aeIcon("DirectSelect", "M5 2.5v13.5l3.5-3.5 2.5 5 2-1-2.5-4.8h5z") }
    val Rectangle: ImageVector by lazy { aeIcon("Rectangle", "M3.5 5h13v10h-13z") }
    val Ellipse: ImageVector by lazy { aeIcon("Ellipse", "M10 4.5c3.6 0 6.5 2.5 6.5 5.5s-2.9 5.5-6.5 5.5S3.5 13 3.5 10 6.4 4.5 10 4.5z") }
    val Polygon: ImageVector by lazy { aeIcon("Polygon", "M10 3l7 5-2.7 8.5H5.7L3 8z") }
    val Line: ImageVector by lazy { aeIcon("Line", "M4 16 16 4") }
    val Hand: ImageVector by lazy {
        aeIcon(
            "Hand",
            "M6.5 10.5V5a1.25 1.25 0 0 1 2.5 0v4.5M9 4.5V3.25a1.25 1.25 0 0 1 2.5 0V9.5M11.5 4.5a1.25 1.25 0 0 1 2.5 0V10M14 6.5a1.25 1.25 0 0 1 2.5 0V12a5.5 5.5 0 0 1-5.5 5.5h-.8a5.5 5.5 0 0 1-4.3-2.1L3.4 12.1a1.3 1.3 0 0 1 2-1.6l1.1 1.2",
        )
    }
    val RotateView: ImageVector by lazy { aeIcon("RotateView", "M16.5 10A6.5 6.5 0 1 1 14 4.9", "M14.5 2v3.5H11", "M10 8.5v3M8.5 10h3") }
    val Zoom: ImageVector by lazy { aeIcon("Zoom", "M8.5 3a5.5 5.5 0 1 1 0 11 5.5 5.5 0 0 1 0-11z", "M12.5 12.5 17 17") }

    // ---- Transform ----
    val RotateCw: ImageVector by lazy { aeIcon("RotateCw", "M16 10a6 6 0 1 1-1.76-4.24", "M16 3v3.5h-3.5") }
    val RotateCcw: ImageVector by lazy { aeIcon("RotateCcw", "M4 10a6 6 0 1 0 1.76-4.24", "M4 3v3.5h3.5") }
    val FlipHorizontal: ImageVector by lazy { aeIcon("FlipHorizontal", "M10 2.5v15", "M7.5 6v8L3 10z", "M12.5 6v8l4.5-4z") }
    val FlipVertical: ImageVector by lazy { aeIcon("FlipVertical", "M2.5 10h15", "M6 7.5h8L10 3z", "M6 12.5h8L10 17z") }

    // ---- Layers & panels ----
    val Layers: ImageVector by lazy { aeIcon("Layers", "M10 3 17 7l-7 4-7-4z", "M3 10.5l7 4 7-4", "M3 14l7 4 7-4") }
    val Adjustments: ImageVector by lazy {
        aeIcon("Adjustments", "M10 3a7 7 0 1 1 0 14z", "M10 3a7 7 0 1 0 0 14", fills = listOf("M10 3a7 7 0 1 1 0 14z"))
    }
    val Mask: ImageVector by lazy { aeIcon("Mask", "M3.5 3.5h13v13h-13z", "M10 6.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z") }
    val Fx: ImageVector by lazy { aeIcon("Fx", "M9 4.5C7.5 4 6.5 5 6.3 6.5L5 15.5c-.2 1.3-1.2 2-2.2 1.6", "M4.5 9h4", "M11 9l5 6M16 9l-5 6") }
    val Group: ImageVector by lazy { aeIcon("Group", "M2.5 5.5v10h15V7.5H9.5L8 5.5z") }
    val History: ImageVector by lazy { aeIcon("History", "M10 3.5a6.5 6.5 0 1 1-6.1 4.3", "M3 4v4h4", "M10 7v3.5l2.5 1.5") }
    val Histogram: ImageVector by lazy { aeIcon("Histogram", "M2.5 16.5h15", "M3 16.5c1.5-3 2.5-8 4-8s2 5 3.5 5 2-9 3.5-9 2 8 3 12") }
    val Navigator: ImageVector by lazy { aeIcon("Navigator", "M2.5 4.5h15v11h-15z", "M8 8h5v4H8z") }
    val Properties: ImageVector by lazy {
        aeIcon(
            "Properties",
            "M3 5.5h8M14.5 5.5H17M3 10h3M9.5 10H17M3 14.5h8M14.5 14.5H17",
            "M12.75 4a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM7.75 8.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM12.75 13a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z",
        )
    }
    val Color: ImageVector by lazy {
        aeIcon(
            "Color",
            "M10 17.5a7.5 7.5 0 1 1 7.5-7.5c0 2-1.5 2.5-3 2.5h-1.8a1.6 1.6 0 0 0-1.2 2.7c.6.7.2 2.3-1.5 2.3z",
            fills = listOf("M6.2 8.2a1 1 0 1 1 0 .01zM9 5.8a1 1 0 1 1 0 .01zM12.5 6.3a1 1 0 1 1 0 .01z"),
        )
    }
    val Channels: ImageVector by lazy {
        aeIcon(
            "Channels",
            "M7.5 3.5a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9z",
            "M12.5 3.5a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9z",
            "M10 8a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9z",
        )
    }

    // ---- Actions ----
    val Add: ImageVector by lazy { aeIcon("Add", "M10 4v12M4 10h12") }
    val Delete: ImageVector by lazy { aeIcon("Delete", "M3.5 5.5h13M8 5.5V3.5h4v2", "M5 5.5l.8 11h8.4l.8-11", "M8.5 8.5v5M11.5 8.5v5") }
    val Duplicate: ImageVector by lazy { aeIcon("Duplicate", "M7 7h9.5v9.5H7z", "M13 7V3.5H3.5V13H7") }
    val Visible: ImageVector by lazy {
        aeIcon("Visible", "M1.8 10S4.8 4.5 10 4.5 18.2 10 18.2 10 15.2 15.5 10 15.5 1.8 10 1.8 10z", "M10 7.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z")
    }
    val Hidden: ImageVector by lazy {
        aeIcon(
            "Hidden",
            "M3 3l14 14",
            "M8.2 4.7A8 8 0 0 1 10 4.5c5.2 0 8.2 5.5 8.2 5.5a14 14 0 0 1-2.2 2.9M13 14.8c-.9.4-1.9.7-3 .7-5.2 0-8.2-5.5-8.2-5.5a14.5 14.5 0 0 1 3.3-3.8",
        )
    }
    val Lock: ImageVector by lazy { aeIcon("Lock", "M4.5 9h11v8.5h-11z", "M7 9V6.5a3 3 0 0 1 6 0V9") }
    val Unlock: ImageVector by lazy { aeIcon("Unlock", "M4.5 9h11v8.5h-11z", "M7 9V6.5a3 3 0 0 1 5.8-1.1") }
    val Link: ImageVector by lazy {
        aeIcon("Link", "M8.5 11.5a3.5 3.5 0 0 0 5 0l2.5-2.5a3.5 3.5 0 0 0-5-5l-1 1", "M11.5 8.5a3.5 3.5 0 0 0-5 0L4 11a3.5 3.5 0 0 0 5 5l1-1")
    }
    val Undo: ImageVector by lazy { aeIcon("Undo", "M4.5 8h8a4 4 0 0 1 0 8H8", "M7.5 5l-3 3 3 3") }
    val Redo: ImageVector by lazy { aeIcon("Redo", "M15.5 8h-8a4 4 0 0 0 0 8H12", "M12.5 5l3 3-3 3") }
    val Open: ImageVector by lazy {
        aeIcon(
            "Open",
            "M2.5 15.5v-10A1.5 1.5 0 0 1 4 4h3.5l1.5 2h6A1.5 1.5 0 0 1 16.5 7.5v1",
            "M2.5 15.5l2.2-6A1.5 1.5 0 0 1 6.1 8.5h10.6a1 1 0 0 1 .9 1.3l-1.9 5.7H2.5z",
        )
    }
    val Save: ImageVector by lazy { aeIcon("Save", "M3.5 3.5h10l3 3v10h-13z", "M6.5 3.5v4h6v-4", "M6.5 16.5v-5h7v5") }
    val Export: ImageVector by lazy { aeIcon("Export", "M10 12.5V2.5M6.5 6 10 2.5 13.5 6", "M3.5 11v5.5h13V11") }
    val Search: ImageVector by lazy { aeIcon("Search", "M9 3.5a5.5 5.5 0 1 1 0 11 5.5 5.5 0 0 1 0-11z", "M13 13l3.5 3.5") }
    val Settings: ImageVector by lazy {
        aeIcon(
            "Settings",
            "M15.76 8.74 L17.53 8.96 L17.53 11.04 L15.76 11.26 L14.97 13.18 L16.06 14.59 L14.59 16.06 L13.18 14.97 L11.26 15.76 L11.04 17.53 L8.96 17.53 L8.74 15.76 L6.82 14.97 L5.41 16.06 L3.94 14.59 L5.03 13.18 L4.24 11.26 L2.47 11.04 L2.47 8.96 L4.24 8.74 L5.03 6.82 L3.94 5.41 L5.41 3.94 L6.82 5.03 L8.74 4.24 L8.96 2.47 L11.04 2.47 L11.26 4.24 L13.18 5.03 L14.59 3.94 L16.06 5.41 L14.97 6.82z",
            "M10 7.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z",
        )
    }
    val Close: ImageVector by lazy { aeIcon("Close", "M5 5l10 10M15 5 5 15") }
    val Check: ImageVector by lazy { aeIcon("Check", "M4 10.5l4 4 8-9") }
    val ChevronDown: ImageVector by lazy { aeIcon("ChevronDown", "M5.5 8 10 12.5 14.5 8") }
    val ChevronRight: ImageVector by lazy { aeIcon("ChevronRight", "M8 5.5 12.5 10 8 14.5") }
    val More: ImageVector by lazy {
        aeIcon(
            "More",
            fills = listOf(
                "M4 8.75a1.25 1.25 0 1 1 0 2.5 1.25 1.25 0 0 1 0-2.5zM10 8.75a1.25 1.25 0 1 1 0 2.5 1.25 1.25 0 0 1 0-2.5zM16 8.75a1.25 1.25 0 1 1 0 2.5 1.25 1.25 0 0 1 0-2.5z",
            ),
        )
    }
    val Menu: ImageVector by lazy { aeIcon("Menu", "M3.5 5.5h13M3.5 10h13M3.5 14.5h13") }
    val Image: ImageVector by lazy {
        aeIcon("Image", "M3 4h14v12H3z", "M3 13.5l4-4 3.5 3.5 2-2 4.5 4.5", "M13 6.5a1.25 1.25 0 1 1 0 2.5 1.25 1.25 0 0 1 0-2.5z")
    }
    val Develop: ImageVector by lazy {
        aeIcon(
            "Develop",
            "M10 2.5a7.5 7.5 0 1 1 0 15 7.5 7.5 0 0 1 0-15z",
            "M10 2.5 12 8M17.1 8l-5.1 3.5M13.8 16.3 10 12.5M4.8 15.3l3.9-3.8M3.5 6.5 8.5 8",
        )
    }
    val Library: ImageVector by lazy { aeIcon("Library", "M3 3h6v6H3zM11 3h6v6h-6zM3 11h6v6H3zM11 11h6v6h-6z") }
    val Sparkle: ImageVector by lazy {
        aeIcon(
            "Sparkle",
            "M9 2.5c.6 3.4 2.1 4.9 5.5 5.5-3.4.6-4.9 2.1-5.5 5.5-.6-3.4-2.1-4.9-5.5-5.5 3.4-.6 4.9-2.1 5.5-5.5z",
            "M15 12.5c.3 1.6 1 2.2 2.5 2.5-1.5.3-2.2.9-2.5 2.5-.3-1.6-1-2.2-2.5-2.5 1.5-.3 2.2-.9 2.5-2.5z",
        )
    }
    val Command: ImageVector by lazy {
        aeIcon(
            "Command",
            "M7 7h6v6H7z",
            "M7 7H5.5A2 2 0 1 1 7 5.5zM13 7V5.5A2 2 0 1 1 14.5 7zM13 13h1.5a2 2 0 1 1-1.5 1.5zM7 13v1.5A2 2 0 1 1 5.5 13z",
        )
    }
    val SwapColors: ImageVector by lazy {
        aeIcon("SwapColors", "M5 12V8a3 3 0 0 1 3-3h4", "M10 3l2 2-2 2", "M15 8v4a3 3 0 0 1-3 3H8", "M10 17l-2-2 2-2")
    }

    // ---- Type ----
    val Paragraph: ImageVector by lazy { aeIcon("Paragraph", "M9 16.5v-13h6M13 3.5v13", "M9 3.5a3.25 3.25 0 0 0 0 6.5") }
    val AlignLeft: ImageVector by lazy { aeIcon("AlignLeft", "M3.5 5h13M3.5 8.5h8M3.5 12h13M3.5 15.5h8") }
    val AlignCenter: ImageVector by lazy { aeIcon("AlignCenter", "M3.5 5h13M6 8.5h8M3.5 12h13M6 15.5h8") }
    val AlignRight: ImageVector by lazy { aeIcon("AlignRight", "M3.5 5h13M8.5 8.5h8M3.5 12h13M8.5 15.5h8") }
    val AlignJustify: ImageVector by lazy { aeIcon("AlignJustify", "M3.5 5h13M3.5 8.5h13M3.5 12h13M3.5 15.5h8") }
    val Star: ImageVector by lazy { aeIcon("Star", "M10 2.8l2.2 4.5 5 .7-3.6 3.5.9 5-4.5-2.4-4.5 2.4.9-5L2.8 8l5-.7z") }
    val StarFilled: ImageVector by lazy {
        aeIcon(
            "StarFilled",
            "M10 2.8l2.2 4.5 5 .7-3.6 3.5.9 5-4.5-2.4-4.5 2.4.9-5L2.8 8l5-.7z",
            fills = listOf("M10 2.8l2.2 4.5 5 .7-3.6 3.5.9 5-4.5-2.4-4.5 2.4.9-5L2.8 8l5-.7z"),
        )
    }

    /** All icons for the catalog sheet. */
    val all: List<Pair<String, ImageVector>> by lazy {
        listOf(
            "Move" to Move, "MarqueeRect" to MarqueeRect, "MarqueeEllipse" to MarqueeEllipse, "Lasso" to Lasso,
            "PolygonLasso" to PolygonLasso, "QuickSelect" to QuickSelect, "MagicWand" to MagicWand, "ObjectSelect" to ObjectSelect,
            "Crop" to Crop, "PerspectiveCrop" to PerspectiveCrop, "Eyedropper" to Eyedropper, "Healing" to Healing,
            "Remove" to Remove, "Brush" to Brush, "CloneStamp" to CloneStamp, "HistoryBrush" to HistoryBrush,
            "Eraser" to Eraser, "Gradient" to Gradient, "PaintBucket" to PaintBucket, "Blur" to Blur, "Smudge" to Smudge,
            "Dodge" to Dodge, "Burn" to Burn, "Pen" to Pen, "Text" to Text, "PathSelect" to PathSelect,
            "DirectSelect" to DirectSelect, "Rectangle" to Rectangle, "Ellipse" to Ellipse, "Polygon" to Polygon,
            "Line" to Line, "Hand" to Hand, "RotateView" to RotateView, "Zoom" to Zoom,
            "RotateCw" to RotateCw, "RotateCcw" to RotateCcw, "FlipHorizontal" to FlipHorizontal, "FlipVertical" to FlipVertical,
            "Layers" to Layers, "Adjustments" to Adjustments, "Mask" to Mask, "Fx" to Fx, "Group" to Group,
            "History" to History, "Histogram" to Histogram, "Navigator" to Navigator, "Properties" to Properties,
            "Color" to Color, "Channels" to Channels,
            "Add" to Add, "Delete" to Delete, "Duplicate" to Duplicate, "Visible" to Visible, "Hidden" to Hidden,
            "Lock" to Lock, "Unlock" to Unlock, "Link" to Link, "Undo" to Undo, "Redo" to Redo, "Open" to Open,
            "Save" to Save, "Export" to Export, "Search" to Search, "Settings" to Settings, "Close" to Close,
            "Check" to Check, "ChevronDown" to ChevronDown, "ChevronRight" to ChevronRight, "More" to More,
            "Menu" to Menu, "Image" to Image, "Develop" to Develop, "Library" to Library, "Sparkle" to Sparkle,
            "Command" to Command, "SwapColors" to SwapColors,
            "Paragraph" to Paragraph, "AlignLeft" to AlignLeft, "AlignCenter" to AlignCenter, "AlignRight" to AlignRight,
            "AlignJustify" to AlignJustify, "Star" to Star, "StarFilled" to StarFilled,
        )
    }
}
