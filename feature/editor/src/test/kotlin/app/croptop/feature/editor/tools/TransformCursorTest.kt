package app.croptop.feature.editor.tools

import android.view.PointerIcon
import kotlin.test.Test
import kotlin.test.assertEquals

class TransformCursorTest {
    @Test
    fun unrotatedHandlesPointAlongTheirEdges() {
        assertEquals(PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW, transformCursorType(CropHandle.RIGHT, 0f))
        assertEquals(PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW, transformCursorType(CropHandle.TOP, 0f))
        assertEquals(PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW, transformCursorType(CropHandle.BOTTOM_RIGHT, 0f))
        assertEquals(PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW, transformCursorType(CropHandle.TOP_RIGHT, 0f))
    }

    @Test
    fun rotationTurnsTheArrows() {
        assertEquals(PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW, transformCursorType(CropHandle.RIGHT, 90f))
        assertEquals(PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW, transformCursorType(CropHandle.RIGHT, 45f))
        assertEquals(PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW, transformCursorType(CropHandle.LEFT, 180f))
    }

    @Test
    fun insideMovesAndOutsideRotates() {
        assertEquals(PointerIcon.TYPE_ALL_SCROLL, transformCursorType(CropHandle.MOVE, 30f))
        assertEquals(PointerIcon.TYPE_GRAB, transformCursorType(null, 30f))
    }
}

class HandleHitTargetTest {
    @Test
    fun pointerHandlesReachAtLeast8DpAndFingersAtLeast24Dp() {
        assertEquals(8f, handleHitRadiusPx(1f, app.croptop.core.input.PointerKind.MOUSE))
        assertEquals(20f, handleHitRadiusPx(2.5f, app.croptop.core.input.PointerKind.MOUSE))
        assertEquals(24f, handleHitRadiusPx(1f, app.croptop.core.input.PointerKind.FINGER))
        assertEquals(60f, handleHitRadiusPx(2.5f, app.croptop.core.input.PointerKind.FINGER))
        assertEquals(8f, handleHitRadiusPx(0f, app.croptop.core.input.PointerKind.STYLUS) * 2f, "a missing density never shrinks below 0.5x")
    }
}
