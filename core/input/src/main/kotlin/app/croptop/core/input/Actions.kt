package app.croptop.core.input

import app.croptop.core.model.DockPanel
import app.croptop.core.model.ToolGroup
import app.croptop.core.model.WorkspaceLayout
import kotlinx.serialization.Serializable

/** Stable identifier for every user-invokable command (menus, shortcuts, palette, macros). */
@Serializable
@JvmInline
value class ActionId(val id: String) {
    override fun toString(): String = id
}

/** Groups for the shortcut helper and the shortcut editor. */
enum class ActionCategory(val label: String) {
    APP("Application"),
    TOOLS("Tools"),
    BRUSH("Brush"),
    COLOR("Color"),
    EDIT("Edit"),
    LAYER("Layer"),
    SELECT("Select"),
    IMAGE("Image"),
    VIEW("View"),
    DEVELOP("Develop"),
    LIBRARY("Library"),
}

data class ActionDef(val id: ActionId, val label: String, val category: ActionCategory)

fun windowPanelAction(panel: DockPanel): ActionId = ActionId("window.panel.${panel.name}")

fun workspaceAction(name: String): ActionId = ActionId("window.ws.$name")

/** Tool slots use `tool.<GROUP>`; Shift+key cycles the group (`tool.<GROUP>.cycle`, derived). */
fun toolAction(group: ToolGroup): ActionId = ActionId("tool.${group.name}")

/**
 * All actions known to the app. Features look up actions here; handlers are registered at runtime
 * by whichever screen is active (see [ShortcutManager.dispatch]).
 */
object Actions {
    private fun a(id: String, label: String, cat: ActionCategory) = ActionDef(ActionId(id), label, cat)

    val all: List<ActionDef> = buildList {
        // Application
        add(a("app.palette", "Command Palette", ActionCategory.APP))
        add(a("app.new", "New…", ActionCategory.APP))
        add(a("app.open", "Open…", ActionCategory.APP))
        add(a("app.save", "Save", ActionCategory.APP))
        add(a("app.saveAs", "Save As…", ActionCategory.APP))
        add(a("app.exportAs", "Export As…", ActionCategory.APP))
        add(a("app.quickExport", "Quick Export as PNG", ActionCategory.APP))
        add(a("app.share", "Share…", ActionCategory.APP))
        add(a("app.close", "Close", ActionCategory.APP))
        add(a("app.nextDoc", "Next Document", ActionCategory.APP))
        add(a("app.prevDoc", "Previous Document", ActionCategory.APP))
        add(a("app.settings", "Settings…", ActionCategory.APP))
        add(a("app.newWindow", "New Window", ActionCategory.APP))
        // Tools
        ToolGroup.entries.forEach { add(ActionDef(toolAction(it), it.label, ActionCategory.TOOLS)) }
        add(a("tool.tempHand", "Temporary Hand (hold)", ActionCategory.TOOLS))
        // Brush
        add(a("brush.sizeDown", "Decrease Brush Size", ActionCategory.BRUSH))
        add(a("brush.sizeUp", "Increase Brush Size", ActionCategory.BRUSH))
        add(a("brush.hardnessDown", "Decrease Hardness", ActionCategory.BRUSH))
        add(a("brush.hardnessUp", "Increase Hardness", ActionCategory.BRUSH))
        (0..9).forEach { d ->
            val pct = if (d == 0) 100 else d * 10
            add(a("brush.opacity$pct", "Opacity $pct%", ActionCategory.BRUSH))
            add(a("brush.flow$pct", "Flow $pct%", ActionCategory.BRUSH))
        }
        // Color
        add(a("color.default", "Default Colors", ActionCategory.COLOR))
        add(a("color.swap", "Swap Colors", ActionCategory.COLOR))
        // Edit
        add(a("edit.undo", "Undo", ActionCategory.EDIT))
        add(a("edit.redo", "Redo", ActionCategory.EDIT))
        add(a("edit.stepBack", "Step Backward", ActionCategory.EDIT))
        add(a("edit.cut", "Cut", ActionCategory.EDIT))
        add(a("edit.copy", "Copy", ActionCategory.EDIT))
        add(a("edit.copyMerged", "Copy Merged", ActionCategory.EDIT))
        add(a("edit.paste", "Paste", ActionCategory.EDIT))
        add(a("edit.pasteInPlace", "Paste in Place", ActionCategory.EDIT))
        add(a("edit.freeTransform", "Free Transform", ActionCategory.EDIT))
        add(a("edit.warp", "Warp", ActionCategory.EDIT))
        add(a("edit.transformAgain", "Transform Again", ActionCategory.EDIT))
        add(a("edit.fill", "Fill…", ActionCategory.EDIT))
        add(a("edit.removeAi", "Remove Selection (AI)", ActionCategory.EDIT))
        add(a("edit.definePattern", "Define Pattern…", ActionCategory.EDIT))
        add(a("edit.patternLibrary", "Pattern Library…", ActionCategory.EDIT))
        add(a("edit.fillForeground", "Fill with Foreground Color", ActionCategory.EDIT))
        add(a("edit.fillBackground", "Fill with Background Color", ActionCategory.EDIT))
        add(a("edit.contentAwareFill", "Content-Aware Fill…", ActionCategory.EDIT))
        // Layer
        add(a("layer.new", "New Layer", ActionCategory.LAYER))
        add(a("layer.delete", "Delete Layer", ActionCategory.LAYER))
        add(a("layer.duplicate", "Duplicate Layer", ActionCategory.LAYER))
        add(a("layer.newAdjustment", "New Adjustment Layer…", ActionCategory.LAYER))
        add(a("layer.newFillSolid", "New Fill Layer: Solid Color", ActionCategory.LAYER))
        add(a("layer.newFillGradient", "New Fill Layer: Gradient", ActionCategory.LAYER))
        add(a("layer.newFillPattern", "New Fill Layer: Pattern", ActionCategory.LAYER))
        add(a("layer.viaCut", "Layer via Cut", ActionCategory.LAYER))
        add(a("layer.group", "Group Layers", ActionCategory.LAYER))
        add(a("layer.ungroup", "Ungroup Layers", ActionCategory.LAYER))
        add(a("layer.mergeDown", "Merge Down", ActionCategory.LAYER))
        add(a("layer.mergeVisible", "Merge Visible", ActionCategory.LAYER))
        add(a("layer.stampVisible", "Stamp Visible", ActionCategory.LAYER))
        add(a("layer.convertSmartObject", "Convert to Smart Object", ActionCategory.LAYER))
        add(a("layer.rasterizeSmartObject", "Rasterize Smart Object", ActionCategory.LAYER))
        add(a("layer.clippingMask", "Create/Release Clipping Mask", ActionCategory.LAYER))
        add(a("layer.maskRevealAll", "Layer Mask: Reveal All", ActionCategory.LAYER))
        add(a("layer.maskHideAll", "Layer Mask: Hide All", ActionCategory.LAYER))
        add(a("layer.maskRevealSelection", "Layer Mask: Reveal Selection", ActionCategory.LAYER))
        add(a("layer.maskHideSelection", "Layer Mask: Hide Selection", ActionCategory.LAYER))
        add(a("layer.maskToggle", "Layer Mask: Disable/Enable", ActionCategory.LAYER))
        add(a("layer.maskApply", "Layer Mask: Apply", ActionCategory.LAYER))
        add(a("layer.maskDelete", "Layer Mask: Delete", ActionCategory.LAYER))
        add(a("layer.moveDown", "Send Backward", ActionCategory.LAYER))
        add(a("layer.moveUp", "Bring Forward", ActionCategory.LAYER))
        add(a("layer.toBottom", "Send to Back", ActionCategory.LAYER))
        add(a("layer.toTop", "Bring to Front", ActionCategory.LAYER))
        add(a("layer.selectBelow", "Select Layer Below", ActionCategory.LAYER))
        add(a("layer.selectAbove", "Select Layer Above", ActionCategory.LAYER))
        // Select
        add(a("select.all", "Select All", ActionCategory.SELECT))
        add(a("select.deselect", "Deselect", ActionCategory.SELECT))
        add(a("select.reselect", "Reselect", ActionCategory.SELECT))
        add(a("select.inverse", "Inverse", ActionCategory.SELECT))
        add(a("select.saveSelection", "Save Selection…", ActionCategory.SELECT))
        add(a("select.loadSelection", "Load Selection…", ActionCategory.SELECT))
        add(a("select.subject", "Subject", ActionCategory.SELECT))
        add(a("select.sky", "Sky", ActionCategory.SELECT))
        add(a("select.background", "Background", ActionCategory.SELECT))
        add(a("select.removeBackground", "Remove Background", ActionCategory.SELECT))
        add(a("select.colorRange", "Color Range…", ActionCategory.SELECT))
        add(a("select.selectAndMask", "Select and Mask…", ActionCategory.SELECT))
        add(a("select.quickMask", "Quick Mask", ActionCategory.SELECT))
        add(a("select.expand", "Expand…", ActionCategory.SELECT))
        add(a("select.contract", "Contract…", ActionCategory.SELECT))
        add(a("select.border", "Border…", ActionCategory.SELECT))
        add(a("select.smooth", "Smooth…", ActionCategory.SELECT))
        add(a("select.feather", "Feather…", ActionCategory.SELECT))
        add(a("select.transform", "Transform Selection", ActionCategory.SELECT))
        // Image
        add(a("image.levels", "Levels…", ActionCategory.IMAGE))
        add(a("image.curves", "Curves…", ActionCategory.IMAGE))
        add(a("image.hueSat", "Hue/Saturation…", ActionCategory.IMAGE))
        add(a("image.colorBalance", "Color Balance…", ActionCategory.IMAGE))
        add(a("filter.last", "Last Filter", ActionCategory.IMAGE))
        add(a("image.desaturate", "Desaturate", ActionCategory.IMAGE))
        add(a("image.invert", "Invert", ActionCategory.IMAGE))
        add(a("image.imageSize", "Image Size…", ActionCategory.IMAGE))
        add(a("image.superResolution", "Super Resolution (AI)…", ActionCategory.IMAGE))
        add(a("filter.denoiseAi", "Denoise (AI)…", ActionCategory.IMAGE))
        add(a("image.canvasSize", "Canvas Size…", ActionCategory.IMAGE))
        add(a("image.assignProfile", "Assign Profile…", ActionCategory.IMAGE))
        add(a("image.convertProfile", "Convert to Profile…", ActionCategory.IMAGE))
        add(a("image.crop", "Crop to Selection", ActionCategory.IMAGE))
        add(a("image.rotate180", "Rotate 180°", ActionCategory.IMAGE))
        add(a("image.rotate90cw", "Rotate 90° Clockwise", ActionCategory.IMAGE))
        add(a("image.rotate90ccw", "Rotate 90° Counter Clockwise", ActionCategory.IMAGE))
        add(a("image.flipH", "Flip Canvas Horizontal", ActionCategory.IMAGE))
        add(a("image.flipV", "Flip Canvas Vertical", ActionCategory.IMAGE))
        // View
        add(a("view.zoomIn", "Zoom In", ActionCategory.VIEW))
        add(a("view.zoomOut", "Zoom Out", ActionCategory.VIEW))
        add(a("view.fit", "Fit on Screen", ActionCategory.VIEW))
        add(a("view.actualPixels", "100%", ActionCategory.VIEW))
        add(a("view.rulers", "Rulers", ActionCategory.VIEW))
        add(a("view.grid", "Grid", ActionCategory.VIEW))
        add(a("view.guides", "Guides", ActionCategory.VIEW))
        add(a("view.snap", "Snap", ActionCategory.VIEW))
        add(a("view.lockGuides", "Lock Guides", ActionCategory.VIEW))
        add(a("view.clearGuides", "Clear Guides", ActionCategory.VIEW))
        add(a("view.newGuide", "New Guide…", ActionCategory.VIEW))
        add(a("view.newGuideLayout", "New Guide Layout…", ActionCategory.VIEW))
        add(a("view.gridSettings", "Grid Settings…", ActionCategory.VIEW))
        add(a("view.proofColors", "Proof Colors", ActionCategory.VIEW))
        add(a("view.gamutWarning", "Gamut Warning", ActionCategory.VIEW))
        add(a("view.proofSetup", "Proof Setup…", ActionCategory.VIEW))
        add(a("view.hidePanels", "Hide Panels", ActionCategory.VIEW))
        add(a("view.screenMode", "Screen Mode", ActionCategory.VIEW))
        // Window
        DockPanel.entries.forEach { add(ActionDef(windowPanelAction(it), it.title, ActionCategory.VIEW)) }
        WorkspaceLayout.Presets.forEach { add(ActionDef(workspaceAction(it.name), "Workspace: ${it.name}", ActionCategory.VIEW)) }
        add(a("window.resetWorkspace", "Reset Workspace", ActionCategory.VIEW))
        add(a("window.saveWorkspace", "Save Workspace…", ActionCategory.VIEW))
        // Develop
        add(a("develop.grid", "Library Grid", ActionCategory.DEVELOP))
        add(a("develop.loupe", "Loupe", ActionCategory.DEVELOP))
        add(a("develop.develop", "Develop", ActionCategory.DEVELOP))
        add(a("develop.crop", "Crop", ActionCategory.DEVELOP))
        add(a("develop.masks", "Masks", ActionCategory.DEVELOP))
        add(a("develop.maskOverlay", "Show Mask Overlay", ActionCategory.DEVELOP))
        add(a("develop.whiteBalance", "White Balance Picker", ActionCategory.DEVELOP))
        add(a("develop.beforeAfter", "Before / After", ActionCategory.DEVELOP))
        add(a("develop.splitView", "Before / After Split", ActionCategory.DEVELOP))
        add(a("develop.clipping", "Clipping Warnings", ActionCategory.DEVELOP))
        // Library
        add(a("library.pick", "Flag as Pick", ActionCategory.LIBRARY))
        add(a("library.reject", "Flag as Reject", ActionCategory.LIBRARY))
        add(a("library.unflag", "Unflag", ActionCategory.LIBRARY))
        (0..5).forEach { add(a("library.rate$it", if (it == 0) "Clear Rating" else "Rate $it", ActionCategory.LIBRARY)) }
        listOf("Red", "Yellow", "Green", "Blue").forEach { add(a("library.label$it", "Label $it", ActionCategory.LIBRARY)) }
    }

    private val byId = all.associateBy { it.id }

    operator fun get(id: ActionId): ActionDef? = byId[id]
}

private val ToolGroup.label: String
    get() = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } + " tool"
