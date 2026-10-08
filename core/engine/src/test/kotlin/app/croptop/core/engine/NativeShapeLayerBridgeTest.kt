package app.croptop.core.engine

import app.croptop.core.engine.api.RejectionCode
import app.croptop.core.model.DocPoint
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.PathAnchorMode
import app.croptop.core.model.PathFillRule
import app.croptop.core.model.PathStrokeAlignment
import app.croptop.core.model.PathStrokeCap
import app.croptop.core.model.PathStrokeJoin
import app.croptop.core.model.RgbaColor
import app.croptop.core.model.ShapeCornerRadii
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorPathStyle
import app.croptop.core.model.VectorPrimitiveMetadata
import app.croptop.core.model.VectorShapeKind
import app.croptop.core.model.VectorSourceBox
import app.croptop.core.model.VectorSubpath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShapeLayerBridgeTest {
    @Test
    fun roundTripsNameStableIdsRelativeHandlesStylesAndPrimitiveSourceBox() {
        val draft = fixture()
        val encoded = success(NativeShapeLayerBridge.encode(draft))
        assertEquals(
            "Shape ✦".toByteArray(Charsets.UTF_8).size,
            ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).let {
                it.position(8)
                it.short.toInt() and 0xffff
            },
        )
        assertArrayEquals(byteArrayOf(0x43, 0x54, 0x53, 0x52, 1, 0, 0, 0), encoded.copyOfRange(0, 8))

        val decoded = success(NativeShapeLayerBridge.decode(encoded))
        assertEquals(draft.id, decoded.id)
        assertEquals(draft.name, decoded.name)
        assertEquals(draft.fillRule, decoded.fillRule)
        assertEquals(draft.subpaths, decoded.subpaths)
        assertEquals(draft.style, decoded.style)
        assertEquals(draft.primitive, decoded.primitive)
        assertEquals(6.5f, decoded.style.miterLimit)
        assertEquals(PathAnchorId(501), decoded.subpaths.first().anchors.first().id)
        assertEquals(DocPoint(4f, -2f), decoded.subpaths.first().anchors.first().incoming)
        assertEquals(VectorSourceBox(20f, 30f, 80f, 60f), decoded.primitive?.sourceBox)
    }

    @Test
    fun nullFillAndStrokeMapToDisabledPaintAndBackToNull() {
        val draft = fixture().copy(style = fixture().style.copy(fillColor = null, strokeColor = null))
        val bytes = success(NativeShapeLayerBridge.encode(draft))
        val headerAndName = 10 + draft.name.toByteArray(Charsets.UTF_8).size
        val boolOffset = headerAndName + 8 + 1
        assertEquals(0, bytes[boolOffset].toInt())
        assertEquals(0, bytes[boolOffset + 1].toInt())

        val decoded = success(NativeShapeLayerBridge.decode(bytes))
        assertNull(decoded.style.fillColor)
        assertNull(decoded.style.strokeColor)
        assertEquals(draft.style.strokeWidth, decoded.style.strokeWidth)
        assertEquals(draft.style.miterLimit, decoded.style.miterLimit)
    }

    @Test
    fun batchPreservesRecordOrderAndHitResultDiscriminant() {
        val first = fixture().copy(id = VectorPathId(101), name = "bottom", primitive = null)
        val second = fixture().copy(id = VectorPathId(102), name = "top", primitive = null)
        val bytes = success(NativeShapeLayerBridge.encodeBatch(listOf(first, second)))
        assertArrayEquals(byteArrayOf(0x43, 0x54, 0x53, 0x42, 1, 0, 0, 0), bytes.copyOfRange(0, 8))
        val decoded = success(NativeShapeLayerBridge.decodeBatch(bytes))
        assertEquals(listOf(first, second), decoded)

        val hit = success(NativeShapeLayerBridge.decodeHit(doubleArrayOf(5.0, 1.0, 2.0, 3.0, 0.0)))
        assertEquals(ShapeHitPart.INTERIOR, hit.part)
        assertEquals(1, hit.recordIndex)
        assertEquals(2, hit.subpathIndex)
        assertEquals(3, hit.anchorIndex)
    }

    @Test
    fun invalidInputsReturnTypedRejectionsAndMalformedBytesDoNotThrow() {
        val invalidPrimitive = fixture().copy(
            primitive = fixture().primitive?.copy(sourceBox = null),
        )
        val missingBox = failure(NativeShapeLayerBridge.encode(invalidPrimitive))
        assertEquals(RejectionCode.INVALID_ARGUMENT, missingBox.code)

        val invalidName = fixture().copy(name = "bad\u0000name")
        assertEquals(RejectionCode.INVALID_ARGUMENT, failure(NativeShapeLayerBridge.encode(invalidName)).code)
        val invalidGeometry = fixture().copy(
            subpaths = listOf(VectorSubpath(listOf(PathAnchor(PathAnchorId(1), DocPoint(Float.NaN, 0f))))),
        )
        assertEquals(RejectionCode.INVALID_ARGUMENT, failure(NativeShapeLayerBridge.encode(invalidGeometry)).code)

        val truncated = failure(NativeShapeLayerBridge.decode(byteArrayOf(0x43, 0x54)))
        assertEquals(RejectionCode.INVALID_ARGUMENT, truncated.code)
        val trailing = success(NativeShapeLayerBridge.encode(fixture())) + 0
        assertEquals(RejectionCode.INVALID_ARGUMENT, failure(NativeShapeLayerBridge.decode(trailing)).code)
        val badBoolean = success(NativeShapeLayerBridge.encode(fixture())).also { bytes ->
            val nameSize = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { position(8) }.short.toInt() and 0xffff
            bytes[10 + nameSize + 9] = 2
        }
        assertEquals(RejectionCode.INVALID_ARGUMENT, failure(NativeShapeLayerBridge.decode(badBoolean)).code)
    }

    @Test
    fun duplicateIdsAndInvalidNativeHitResultsAreRejected() {
        val draft = fixture()
        val duplicateAnchors = draft.copy(
            subpaths = listOf(draft.subpaths.first().copy(anchors = draft.subpaths.first().anchors.let { it + it.first() })),
        )
        assertEquals(RejectionCode.INVALID_ARGUMENT, failure(NativeShapeLayerBridge.encode(duplicateAnchors)).code)
        val duplicateRecords = failure(NativeShapeLayerBridge.encodeBatch(listOf(draft, draft.copy(name = "duplicate"))))
        assertEquals(RejectionCode.INVALID_ARGUMENT, duplicateRecords.code)
        assertEquals(RejectionCode.INVALID_ARGUMENT, failure(NativeShapeLayerBridge.decodeHit(doubleArrayOf(9.0, 0.0, 0.0, 0.0, 0.0))).code)
        assertEquals(RejectionCode.INVALID_ARGUMENT, failure(NativeShapeLayerBridge.decodeHit(doubleArrayOf(5.5, 0.0, 0.0, 0.0, 0.0))).code)
        assertFalse(NativeShapeLayerBridge.decodeHit(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)) is ShapeLayerBridgeResult.Failure)
        assertTrue(NativeShapeLayerBridge.encodeBatch(emptyList()) is ShapeLayerBridgeResult.Success)
        assertEquals(
            RejectionCode.INVALID_ARGUMENT,
            failure(NativeShapeLayerBridge.hitTest(listOf(draft), 10_000_001.0, 0.0, 4.0)).code,
        )
    }

    private fun fixture(): VectorPathDraft = VectorPathDraft(
        id = VectorPathId(42),
        name = "Shape ✦",
        subpaths = listOf(
            VectorSubpath(
                anchors = listOf(
                    PathAnchor(PathAnchorId(501), DocPoint(100f, 110f), DocPoint(4f, -2f), DocPoint(8f, 1f), PathAnchorMode.SYMMETRIC),
                    PathAnchor(PathAnchorId(502), DocPoint(180f, 170f), null, DocPoint(-3f, 5f), PathAnchorMode.SMOOTH),
                    PathAnchor(PathAnchorId(503), DocPoint(120f, 200f), mode = PathAnchorMode.CORNER),
                ),
                closed = true,
            ),
        ),
        fillRule = PathFillRule.EVEN_ODD,
        style = VectorPathStyle(
            fillColor = RgbaColor(0.1f, 0.2f, 0.3f, 0.4f),
            strokeColor = RgbaColor(0.9f, 0.8f, 0.7f, 1f),
            strokeWidth = 4.5f,
            join = PathStrokeJoin.BEVEL,
            miterLimit = 6.5f,
            cap = PathStrokeCap.ROUND,
            alignment = PathStrokeAlignment.OUTSIDE,
            dashLengths = listOf(5f, 2f),
            dashOffset = 1.25f,
            arrowAtStart = true,
            arrowAtEnd = false,
            arrowLength = 7f,
            arrowWidth = 2.5f,
        ),
        primitive = VectorPrimitiveMetadata(
            kind = VectorShapeKind.STAR,
            cornerRadii = ShapeCornerRadii(1f, 2f, 3f, 4f),
            polygonSides = 7,
            starInnerRatio = 0.37f,
            rotationDegrees = -22.5f,
            sourceBox = VectorSourceBox(20f, 30f, 80f, 60f),
        ),
    )

    private fun <T> success(result: ShapeLayerBridgeResult<T>): T = when (result) {
        is ShapeLayerBridgeResult.Success -> result.value
        is ShapeLayerBridgeResult.Failure -> error("Expected success, got ${result.code}: ${result.message}")
    }

    private fun <T> failure(result: ShapeLayerBridgeResult<T>): ShapeLayerBridgeResult.Failure = when (result) {
        is ShapeLayerBridgeResult.Success -> error("Expected failure, got ${result.value}")
        is ShapeLayerBridgeResult.Failure -> result
    }
}
