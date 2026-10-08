package app.croptop.feature.editor

import androidx.compose.ui.graphics.vector.ImageVector
import app.croptop.core.designsystem.icon.AeIcons
import app.croptop.core.model.ToolId

internal val ToolId.icon: ImageVector
    get() = when (this) {
        ToolId.MOVE, ToolId.ARTBOARD -> AeIcons.Move
        ToolId.RECT_MARQUEE -> AeIcons.MarqueeRect
        ToolId.ELLIPSE_MARQUEE -> AeIcons.MarqueeEllipse
        ToolId.LASSO, ToolId.MAGNETIC_LASSO -> AeIcons.Lasso
        ToolId.POLYGONAL_LASSO -> AeIcons.PolygonLasso
        ToolId.OBJECT_SELECT -> AeIcons.ObjectSelect
        ToolId.QUICK_SELECT -> AeIcons.QuickSelect
        ToolId.MAGIC_WAND -> AeIcons.MagicWand
        ToolId.CROP -> AeIcons.Crop
        ToolId.PERSPECTIVE_CROP -> AeIcons.PerspectiveCrop
        ToolId.EYEDROPPER -> AeIcons.Eyedropper
        ToolId.SPOT_HEALING, ToolId.HEALING, ToolId.PATCH, ToolId.RED_EYE -> AeIcons.Healing
        ToolId.AI_REMOVE -> AeIcons.Remove
        ToolId.BRUSH -> AeIcons.Brush
        ToolId.CLONE_STAMP -> AeIcons.CloneStamp
        ToolId.HISTORY_BRUSH -> AeIcons.HistoryBrush
        ToolId.ERASER, ToolId.BACKGROUND_ERASER -> AeIcons.Eraser
        ToolId.GRADIENT -> AeIcons.Gradient
        ToolId.PAINT_BUCKET -> AeIcons.PaintBucket
        ToolId.BLUR, ToolId.SHARPEN -> AeIcons.Blur
        ToolId.SMUDGE -> AeIcons.Smudge
        ToolId.LIQUIFY -> AeIcons.Liquify
        ToolId.DODGE, ToolId.SPONGE -> AeIcons.Dodge
        ToolId.BURN -> AeIcons.Burn
        ToolId.PEN -> AeIcons.Pen
        ToolId.TEXT -> AeIcons.Text
        ToolId.PATH_SELECT -> AeIcons.PathSelect
        ToolId.DIRECT_SELECT -> AeIcons.DirectSelect
        ToolId.RECTANGLE -> AeIcons.Rectangle
        ToolId.ELLIPSE -> AeIcons.Ellipse
        ToolId.POLYGON -> AeIcons.Polygon
        ToolId.LINE -> AeIcons.Line
        ToolId.HAND -> AeIcons.Hand
        ToolId.ROTATE_VIEW -> AeIcons.RotateView
        ToolId.ZOOM -> AeIcons.Zoom
    }
