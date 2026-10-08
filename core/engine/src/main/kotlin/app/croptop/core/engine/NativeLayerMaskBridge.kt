package app.croptop.core.engine

import app.croptop.core.engine.api.MaskFill
import app.croptop.core.model.LayerMaskInfo

/**
 * Layer masks in the native compositor. A mask handle owns an A16 coverage store plus the coverage of tiles it holds no
 * pixels for (so "Reveal All" is free until painted). Attach a mask to a layer's store handle with [attach] and every render
 * pass (viewport, thumbnails of the composite, export) composites it, honouring enabled, density and feather; [setClipped]
 * turns the layer into a clipping-group member. Masks are immutable values: history snapshots keep their own handle
 * ([clone]) and re-[attach] when a step is undone or redone. Handles the caller no longer needs go to [destroy]; result
 * stores go to `NativeSelectionBridge.destroyStore`.
 */
internal object NativeLayerMaskBridge {
    /** A new mask sized like [likeStore]; the selection variants need a selection store handle. */
    fun create(likeStore: Long, fill: MaskFill, selectionStore: Long = 0L): Long {
        require(!fill.needsSelection || selectionStore != 0L) { "${fill.label} needs a selection" }
        val kind = when (fill) {
            MaskFill.REVEAL_ALL -> 0
            MaskFill.HIDE_ALL -> 1
            MaskFill.REVEAL_SELECTION -> 2
            MaskFill.HIDE_SELECTION -> 3
        }
        return check(nativeCreate(likeStore, kind, selectionStore))
    }

    /** Wraps a painted A16 store; [emptyCoverage] is 1 for a mask whose unpainted tiles reveal. */
    fun fromStore(a16Store: Long, emptyCoverage: Float): Long = check(nativeFromStore(a16Store, emptyCoverage))

    /** Copies the raw A16 mask data attached to a layer store into an owned handle for edits. */
    fun fromLayerStore(layerStore: Long): Long = check(nativeFromLayerStore(layerStore))

    /** The mask pixels as a copy-on-write store, the brush's target when painting on the mask. */
    fun pixels(mask: Long): Long = check(nativeStore(mask))

    /** Coverage of tiles with no stored pixels: pair with [pixels] when re-wrapping a painted store via [fromStore]. */
    fun emptyCoverage(mask: Long): Float = nativeEmpty(mask)

    fun clone(mask: Long): Long = check(nativeClone(mask))

    fun invert(mask: Long): Long = check(nativeInvert(mask))

    fun destroy(mask: Long) = nativeDestroy(mask)

    /** Composite [mask] under [layerStore] with [info]'s enabled/density/feather. Call again after any change. */
    fun attach(layerStore: Long, mask: Long, info: LayerMaskInfo = LayerMaskInfo()) {
        check(nativeAttach(layerStore, mask, info.enabled, info.density, info.feather)) {
            nativeLastError().ifBlank { "Could not attach the layer mask" }
        }
    }

    fun detach(layerStore: Long) = nativeDetach(layerStore)

    fun setClipped(layerStore: Long, clipped: Boolean) = nativeSetClipped(layerStore, clipped)

    /** Blend If sliders: the layer's alpha is scaled per pixel by its own and the backdrop's luminance in every composite. */
    fun setBlendIf(layerStore: Long, blendIf: app.croptop.core.model.BlendIfSettings) = nativeSetBlendIf(layerStore, blendIf.toInts())

    /** The layer's pixels with the mask multiplied into their alpha (Apply Layer Mask): a new store handle. */
    fun apply(layerStore: Long, mask: Long, info: LayerMaskInfo = LayerMaskInfo()): Long =
        check(nativeApply(layerStore, mask, info.enabled, info.density, info.feather))

    /** The mask's effective coverage as an A16 selection store handle (Load Selection). */
    fun toSelection(mask: Long, info: LayerMaskInfo = LayerMaskInfo()): Long =
        check(nativeToSelection(mask, info.enabled, info.density, info.feather))

    /** A grayscale preview for the Layers panel: [argb] holds `width * height` opaque gray pixels, row-major. */
    class Thumbnail(val width: Int, val height: Int, val argb: IntArray)

    /** The painted mask (no density or feather) reduced to at most [maxPx] on its longer edge. */
    fun thumbnail(mask: Long, maxPx: Int): Thumbnail {
        val values = nativeThumbnail(mask, maxPx) ?: error(nativeLastError().ifBlank { "Could not render the mask thumbnail" })
        return Thumbnail(values[0], values[1], values.copyOfRange(2, values.size))
    }

    private fun check(handle: Long): Long {
        check(handle != 0L) { nativeLastError().ifBlank { "Layer mask operation failed" } }
        return handle
    }

    private external fun nativeLastError(): String
    private external fun nativeCreate(likeStore: Long, kind: Int, selectionStore: Long): Long
    private external fun nativeFromStore(a16Store: Long, empty: Float): Long
    private external fun nativeFromLayerStore(layerStore: Long): Long
    private external fun nativeStore(mask: Long): Long
    private external fun nativeEmpty(mask: Long): Float
    private external fun nativeClone(mask: Long): Long
    private external fun nativeInvert(mask: Long): Long
    private external fun nativeDestroy(mask: Long)
    private external fun nativeAttach(layerStore: Long, mask: Long, enabled: Boolean, density: Float, feather: Float): Boolean
    private external fun nativeDetach(layerStore: Long)
    private external fun nativeSetClipped(layerStore: Long, clipped: Boolean)
    private external fun nativeSetBlendIf(layerStore: Long, values: IntArray)
    private external fun nativeApply(layerStore: Long, mask: Long, enabled: Boolean, density: Float, feather: Float): Long
    private external fun nativeThumbnail(mask: Long, maxPx: Int): IntArray?
    private external fun nativeToSelection(mask: Long, enabled: Boolean, density: Float, feather: Float): Long
}
