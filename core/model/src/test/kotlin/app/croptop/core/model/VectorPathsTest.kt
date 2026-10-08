package app.croptop.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class VectorPathsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `shape options clamp dimensions and ignore invalid dash lengths`() {
        val options = ShapeToolOptions(
            kind = VectorShapeKind.STAR,
            strokeWidth = Float.NaN,
            cornerRadii = ShapeCornerRadii(topLeft = Float.POSITIVE_INFINITY, bottomRight = 12f),
            polygonSides = 120,
            starInnerRatio = -0.4f,
            rotationDegrees = 720f,
            dashLengths = listOf(2f, 0f, -1f, Float.NaN, 3f),
            dashOffset = Float.NaN,
        ).normalized()

        assertEquals(VectorShapeKind.STAR, options.kind)
        assertEquals(1f, options.strokeWidth)
        assertEquals(0f, options.cornerRadii.topLeft)
        assertEquals(12f, options.cornerRadii.bottomRight)
        assertEquals(ShapeToolOptions.MAX_POLYGON_SIDES, options.polygonSides)
        assertEquals(ShapeToolOptions.MIN_STAR_INNER_RATIO, options.starInnerRatio)
        assertEquals(0f, options.rotationDegrees)
        assertEquals(listOf(2f, 3f), options.dashLengths)
        assertEquals(0f, options.dashOffset)
    }

    @Test
    fun `path anchors and shape options serialize as editable values`() {
        val path = VectorPathDraft(
            id = VectorPathId(8),
            name = "Bezier",
            subpaths = listOf(
                VectorSubpath(
                    anchors = listOf(
                        PathAnchor(PathAnchorId(3), DocPoint(1.25f, 2.5f), outgoing = DocPoint(4f, 0f), mode = PathAnchorMode.SMOOTH),
                        PathAnchor(PathAnchorId(4), DocPoint(12f, 15f), incoming = DocPoint(-2f, -3f)),
                    ),
                ),
            ),
            fillRule = PathFillRule.EVEN_ODD,
            style = VectorPathStyle(
                fillColor = RgbaColor(0.2f, 0.3f, 0.4f, 0.8f),
                strokeColor = null,
                strokeWidth = 4.5f,
                join = PathStrokeJoin.ROUND,
                miterLimit = 2.5f,
                dashLengths = listOf(8f, 3f),
            ),
            primitive = VectorPrimitiveMetadata(
                kind = VectorShapeKind.ROUNDED_RECTANGLE,
                cornerRadii = ShapeCornerRadii.all(12f),
                rotationDegrees = 15f,
                sourceBox = VectorSourceBox(10f, 12f, 80f, 40f),
            ),
        )

        val encoded = json.encodeToString(path)
        assertEquals(path, json.decodeFromString<VectorPathDraft>(encoded))
    }

    @Test
    fun `shape defaults snapshot to style and primitive metadata`() {
        val options = ShapeToolOptions(
            kind = VectorShapeKind.STAR,
            fillEnabled = false,
            strokeEnabled = true,
            strokeWidth = 3f,
            polygonSides = 7,
            starInnerRatio = 0.35f,
            rotationDegrees = 28f,
            miterLimit = 9f,
        )

        val style = options.toPathStyle()
        val primitive = options.toPrimitiveMetadata()
        assertEquals(null, style.fillColor)
        assertEquals(3f, style.strokeWidth)
        assertEquals(9f, style.miterLimit)
        assertEquals(VectorShapeKind.STAR, primitive.kind)
        assertEquals(7, primitive.polygonSides)
        assertEquals(0.35f, primitive.starInnerRatio)
        assertEquals(28f, primitive.rotationDegrees)
    }

    @Test
    fun `primitive source box rejects invalid bounds`() {
        assertEquals(null, VectorSourceBox(0f, 0f, 0f, 10f).normalized())
        assertEquals(null, VectorSourceBox(Float.NaN, 0f, 10f, 10f).normalized())
        assertEquals(VectorSourceBox(1f, 2f, 3f, 4f), VectorSourceBox(1f, 2f, 3f, 4f).normalized())
    }

    @Test
    fun `miter limit defaults and clamps to renderer bounds`() {
        assertEquals(4f, VectorPathStyle().normalized().miterLimit)
        assertEquals(100f, VectorPathStyle(miterLimit = 250f).normalized().miterLimit)
        assertEquals(1f, VectorPathStyle(miterLimit = 0f).normalized().miterLimit)
        assertEquals(4f, ShapeToolOptions(miterLimit = Float.NaN).normalized().miterLimit)
    }
}
