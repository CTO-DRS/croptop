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
import app.croptop.core.model.ShapeToolOptions
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorPathId
import app.croptop.core.model.VectorPathStyle
import app.croptop.core.model.VectorPrimitiveMetadata
import app.croptop.core.model.VectorShapeKind
import app.croptop.core.model.VectorSourceBox
import app.croptop.core.model.VectorSubpath
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Typed result for shape-layer bridge calls; failures stay values across editor/UI boundaries. */
sealed interface ShapeLayerBridgeResult<out T> {
    data class Success<T>(val value: T) : ShapeLayerBridgeResult<T>

    data class Failure(val code: RejectionCode, val message: String) : ShapeLayerBridgeResult<Nothing>
}

class ShapeRecordHandle internal constructor(internal var value: Long)

enum class ShapeHitPart { NONE, INCOMING_HANDLE, OUTGOING_HANDLE, ANCHOR, SEGMENT, INTERIOR }

data class ShapeHit(
    val part: ShapeHitPart,
    val recordIndex: Int,
    val subpathIndex: Int,
    val anchorIndex: Int,
    val distance: Double,
)

/**
 * Kotlin codec and JNI facade for the CTSR/CTSB v1 shape-record format (see shape_layer.h). This object does
 * not own document history or persistence; its opaque handles must be destroyed exactly once.
 */
object NativeShapeLayerBridge {
    internal const val MAX_NAME_BYTES = 1024
    internal const val MAX_ANCHORS = 100_000
    internal const val MAX_SUBPATHS = 100_000
    internal const val MAX_RECORD_BYTES = 8 * 1024 * 1024
    internal const val MAX_BATCH_BYTES = 32 * 1024 * 1024
    internal const val MAX_RECORDS_PER_BATCH = 100_000
    private val recordMagic = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte())
    private val batchMagic = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte())

    fun encode(draft: VectorPathDraft): ShapeLayerBridgeResult<ByteArray> = encodeRecord(draft)

    fun decode(bytes: ByteArray): ShapeLayerBridgeResult<VectorPathDraft> = decodeRecord(bytes)

    fun create(draft: VectorPathDraft): ShapeLayerBridgeResult<ShapeRecordHandle> {
        val encoded = when (val result = encodeRecord(draft)) {
            is ShapeLayerBridgeResult.Success -> result.value
            is ShapeLayerBridgeResult.Failure -> return result
        }
        return nativeCall {
            val handle = nativeCreate(encoded)
            if (handle == 0L) {
                failure(RejectionCode.INVALID_ARGUMENT, nativeError("Native shape creation failed"))
            } else {
                ShapeLayerBridgeResult.Success(ShapeRecordHandle(handle))
            }
        }
    }

    fun update(handle: ShapeRecordHandle, draft: VectorPathDraft): ShapeLayerBridgeResult<Unit> {
        val encoded = when (val result = encodeRecord(draft)) {
            is ShapeLayerBridgeResult.Success -> result.value
            is ShapeLayerBridgeResult.Failure -> return result
        }
        return synchronized(handle) {
            nativeCall {
                if (handle.value == 0L) return@nativeCall failure(RejectionCode.INVALID_ARGUMENT, "Shape record handle is invalid")
                if (nativeUpdate(handle.value, encoded)) {
                    ShapeLayerBridgeResult.Success(Unit)
                } else {
                    failure(RejectionCode.INVALID_ARGUMENT, nativeError("Native shape update failed"))
                }
            }
        }
    }

    fun read(handle: ShapeRecordHandle): ShapeLayerBridgeResult<VectorPathDraft> = synchronized(handle) {
        nativeCall {
            if (handle.value == 0L) return@nativeCall failure(RejectionCode.INVALID_ARGUMENT, "Shape record handle is invalid")
            val encoded = nativeEncode(handle.value)
                ?: return@nativeCall failure(RejectionCode.INVALID_ARGUMENT, nativeError("Native shape encoding failed"))
            decodeRecord(encoded)
        }
    }

    fun destroy(handle: ShapeRecordHandle): ShapeLayerBridgeResult<Unit> = synchronized(handle) {
        nativeCall {
            if (handle.value == 0L) return@nativeCall ShapeLayerBridgeResult.Success(Unit)
            nativeDestroy(handle.value)
            handle.value = 0L
            ShapeLayerBridgeResult.Success(Unit)
        }
    }

    fun hitTest(records: List<VectorPathDraft>, x: Double, y: Double, tolerance: Double): ShapeLayerBridgeResult<ShapeHit> {
        val encoded = when (val result = encodeBatch(records)) {
            is ShapeLayerBridgeResult.Success -> result.value
            is ShapeLayerBridgeResult.Failure -> return result
        }
        if (!x.isFinite() || !y.isFinite() || kotlin.math.abs(x) > 1e7 || kotlin.math.abs(y) > 1e7 ||
            !tolerance.isFinite() || tolerance < 0.0 || tolerance > 1e6
        ) {
            return failure(RejectionCode.INVALID_ARGUMENT, "Direct-select point or tolerance is out of range")
        }
        return nativeCall {
            val result = nativeHitTest(encoded, x, y, tolerance)
                ?: return@nativeCall failure(RejectionCode.INVALID_ARGUMENT, nativeError("Native shape hit test failed"))
            decodeHit(result)
        }
    }

    internal fun encodeBatch(records: List<VectorPathDraft>): ShapeLayerBridgeResult<ByteArray> = codec {
        if (records.size > MAX_RECORDS_PER_BATCH) reject("Shape batch contains too many records")
        val ids = HashSet<Long>(records.size)
        val encoded = ArrayList<ByteArray>(records.size)
        var total = 12L
        records.forEach { draft ->
            if (!ids.add(draft.id.value)) reject("Shape record IDs must be unique in a batch")
            val record = encodeRecordOrThrow(draft)
            total += 4L + record.size
            if (total > MAX_BATCH_BYTES) reject("Encoded shape batch exceeds 32 MiB")
            encoded += record
        }
        val out = ByteBuffer.allocate(total.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        out.put(batchMagic)
        out.putShort(1)
        out.putShort(0)
        out.putInt(encoded.size)
        encoded.forEach { out.putInt(it.size).put(it) }
        out.array()
    }

    internal fun decodeBatch(bytes: ByteArray): ShapeLayerBridgeResult<List<VectorPathDraft>> = codec {
        if (bytes.size < 12 || bytes.size > MAX_BATCH_BYTES) reject("Shape batch byte length is invalid")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (!input.readMagic(batchMagic) || input.readUnsignedShort() != 1 || input.readUnsignedShort() != 0) reject("Shape batch header is invalid")
        val count = input.readUnsignedIntBounded(MAX_RECORDS_PER_BATCH, "record count")
        if (count > input.remaining() / 4) reject("Shape batch record table is truncated")
        val ids = HashSet<Long>(count)
        val result = ArrayList<VectorPathDraft>(count)
        repeat(count) {
            val size = input.readUnsignedIntBounded(MAX_RECORD_BYTES, "record length")
            if (size > input.remaining()) reject("Shape batch record is truncated")
            val recordBytes = ByteArray(size)
            input.get(recordBytes)
            val draft = decodeRecordOrThrow(recordBytes)
            if (!ids.add(draft.id.value)) reject("Shape batch contains duplicate record IDs")
            result += draft
        }
        if (input.hasRemaining()) reject("Shape batch contains trailing data")
        result
    }

    internal fun decodeHit(values: DoubleArray): ShapeLayerBridgeResult<ShapeHit> = codec {
        if (values.size != 5 || values.any { !it.isFinite() }) reject("Native hit result has the wrong shape")
        val part = values[0].toExactInt("hit part")
        val record = values[1].toExactInt("record index")
        val subpath = values[2].toExactInt("subpath index")
        val anchor = values[3].toExactInt("anchor index")
        val distance = values[4]
        if (part !in ShapeHitPart.entries.indices || record < 0 || subpath < 0 || anchor < 0 ||
            distance < 0
        ) {
            reject("Native hit result is out of range")
        }
        ShapeHit(ShapeHitPart.entries[part], record, subpath, anchor, distance)
    }

    internal fun encodeRecord(draft: VectorPathDraft): ShapeLayerBridgeResult<ByteArray> = codec { encodeRecordOrThrow(draft) }

    internal fun decodeRecord(bytes: ByteArray): ShapeLayerBridgeResult<VectorPathDraft> = codec { decodeRecordOrThrow(bytes) }

    private fun encodeRecordOrThrow(draft: VectorPathDraft): ByteArray {
        validateDraft(draft)
        val name = encodeName(draft.name)
        val style = draft.style
        val primitive = draft.primitive
        val dash = style.dashLengths
        val anchorCount = draft.subpaths.sumOf { it.anchors.size }
        val size = 110L + name.size + dash.size * 8L + (if (primitive == null) 0L else 88L) +
            draft.subpaths.sumOf { 8L + it.anchors.size * 64L }
        if (size > MAX_RECORD_BYTES) reject("Encoded shape record exceeds 8 MiB")
        val out = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        out.put(recordMagic).putShort(1).putShort(0).putShort(name.size.toShort()).put(name)
        out.putLong(draft.id.value)
        out.put(draft.fillRule.ordinal.toByte())
        out.put((if (style.fillColor == null) 0 else 1).toByte())
        out.put((if (style.strokeColor == null) 0 else 1).toByte())
        out.put((if (primitive == null) 0 else 1).toByte())
        putColor(out, style.fillColor ?: RgbaColor.Transparent)
        putColor(out, style.strokeColor ?: RgbaColor.Black)
        out.putDouble(style.strokeWidth.toDouble())
        out.put(style.join.ordinal.toByte()).put(style.cap.ordinal.toByte()).put(style.alignment.ordinal.toByte()).put(0)
        out.putDouble(style.miterLimit.toDouble()).putDouble(style.dashOffset.toDouble())
        out.putInt(dash.size)
        dash.forEach { out.putDouble(it.toDouble()) }
        out.put((if (style.arrowAtStart) 1 else 0).toByte()).put((if (style.arrowAtEnd) 1 else 0).toByte()).putShort(0)
        out.putDouble(style.arrowLength.toDouble()).putDouble(style.arrowWidth.toDouble())
        if (primitive != null) putPrimitive(out, primitive)
        out.putInt(draft.subpaths.size)
        draft.subpaths.forEach { subpath ->
            out.putInt(subpath.anchors.size).put((if (subpath.closed) 1 else 0).toByte()).put(0).putShort(0)
            subpath.anchors.forEach { anchor -> putAnchor(out, anchor) }
        }
        if (out.hasRemaining()) reject("Internal shape record length calculation failed")
        if (anchorCount > MAX_ANCHORS) reject("Shape has too many anchors")
        return out.array()
    }

    private fun decodeRecordOrThrow(bytes: ByteArray): VectorPathDraft {
        if (bytes.size < 10 || bytes.size > MAX_RECORD_BYTES) reject("Shape record byte length is invalid")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (!input.readMagic(recordMagic) || input.readUnsignedShort() != 1 ||
            input.readUnsignedShort() != 0
        ) {
            reject("Shape record header is invalid")
        }
        val nameSize = input.readUnsignedShort()
        if (nameSize > MAX_NAME_BYTES || nameSize > input.remaining()) reject("Shape name byte length is invalid")
        val nameBytes = ByteArray(nameSize)
        input.get(nameBytes)
        val name = decodeName(nameBytes)
        val id = VectorPathId(input.long)
        val fillRule = PathFillRule.entries.getOrNull(input.readUnsignedByte()) ?: reject("Fill-rule ordinal is unsupported")
        val fillEnabled = input.readStrictBoolean("fill-enabled")
        val strokeEnabled = input.readStrictBoolean("stroke-enabled")
        val hasPrimitive = input.readStrictBoolean("primitive-present")
        val fillColor = input.readColor()
        val strokeColor = input.readColor()
        val strokeWidth = input.readFiniteFloat("stroke width")
        val join = PathStrokeJoin.entries.getOrNull(input.readUnsignedByte()) ?: reject("Stroke join ordinal is unsupported")
        val cap = PathStrokeCap.entries.getOrNull(input.readUnsignedByte()) ?: reject("Stroke cap ordinal is unsupported")
        val alignment = PathStrokeAlignment.entries.getOrNull(input.readUnsignedByte()) ?: reject("Stroke alignment ordinal is unsupported")
        if (input.readUnsignedByte() != 0) reject("Shape record reserved byte is nonzero")
        val miterLimit = input.readFiniteFloat("miter limit")
        val dashOffset = input.readFiniteFloat("dash offset")
        val dashCount = input.readUnsignedIntBounded(32, "dash count")
        if (dashCount > input.remaining() / 8) reject("Dash array is truncated")
        val dashes = List(dashCount) { input.readFiniteFloat("dash length") }
        val arrowStart = input.readStrictBoolean("arrow-at-start")
        val arrowEnd = input.readStrictBoolean("arrow-at-end")
        if (input.readUnsignedShort() != 0) reject("Shape record reserved field is nonzero")
        val arrowLength = input.readFiniteFloat("arrow length")
        val arrowWidth = input.readFiniteFloat("arrow width")
        val primitive = if (hasPrimitive) readPrimitive(input) else null
        val subpathCount = input.readUnsignedIntBounded(MAX_SUBPATHS, "subpath count")
        if (subpathCount > input.remaining() / 8) reject("Subpath table is truncated")
        var totalAnchors = 0
        val subpaths = ArrayList<VectorSubpath>(subpathCount)
        repeat(subpathCount) {
            val count = input.readUnsignedIntBounded(MAX_ANCHORS - totalAnchors, "anchor count")
            val closed = input.readStrictBoolean("subpath-closed")
            if (input.readUnsignedByte() != 0 || input.readUnsignedShort() != 0) reject("Subpath reserved bytes are nonzero")
            if (count > input.remaining() / 64) reject("Anchor data is truncated")
            totalAnchors += count
            val anchors = ArrayList<PathAnchor>(count)
            repeat(count) { anchors += input.readAnchor() }
            subpaths += VectorSubpath(anchors, closed)
        }
        if (input.hasRemaining()) reject("Shape record contains trailing data")
        val style = VectorPathStyle(
            fillColor = fillColor.takeIf { fillEnabled },
            strokeColor = strokeColor.takeIf { strokeEnabled },
            strokeWidth = strokeWidth,
            join = join,
            miterLimit = miterLimit,
            cap = cap,
            alignment = alignment,
            dashLengths = dashes,
            dashOffset = dashOffset,
            arrowAtStart = arrowStart,
            arrowAtEnd = arrowEnd,
            arrowLength = arrowLength,
            arrowWidth = arrowWidth,
        )
        val draft = VectorPathDraft(id, name, subpaths, fillRule, style, primitive)
        validateDraft(draft)
        return draft
    }

    private fun validateDraft(draft: VectorPathDraft) {
        if (draft.name.contains('\u0000')) reject("Shape name cannot contain NUL")
        val encodedName = encodeName(draft.name)
        if (encodedName.size > MAX_NAME_BYTES) reject("Shape name exceeds 1024 UTF-8 bytes")
        if (draft.subpaths.size > MAX_SUBPATHS) reject("Shape has too many subpaths")
        val style = draft.style
        if (!style.strokeWidth.isFinite() || style.strokeWidth !in 0f..10_000f) reject("Stroke width is out of range")
        if (!style.miterLimit.isFinite() || style.miterLimit !in 1f..100f) reject("Miter limit is out of range")
        if (!style.dashOffset.isFinite() || style.dashLengths.size > 32 ||
            style.dashLengths.any { !it.isFinite() || it <= 0f }
        ) {
            reject("Stroke dashes are invalid")
        }
        if (!style.arrowLength.isFinite() || style.arrowLength !in 1f..32f || !style.arrowWidth.isFinite() || style.arrowWidth !in 1f..32f) {
            reject("Arrow dimensions are out of range")
        }
        style.fillColor?.let { validateColor(it, "fill") }
        style.strokeColor?.let { validateColor(it, "stroke") }
        var anchors = 0
        val anchorIds = HashSet<Long>()
        draft.subpaths.forEach { subpath ->
            anchors += subpath.anchors.size
            if (anchors > MAX_ANCHORS) reject("Shape has too many anchors")
            subpath.anchors.forEach { anchor ->
                if (!anchorIds.add(anchor.id.value)) reject("Anchor IDs must be unique within a shape")
                validatePoint(anchor.point, "anchor point")
                anchor.incoming?.let { validatePoint(it, "incoming handle") }
                anchor.outgoing?.let { validatePoint(it, "outgoing handle") }
            }
        }
        draft.primitive?.let { validatePrimitive(it) }
    }

    private fun validatePrimitive(primitive: VectorPrimitiveMetadata) {
        if (primitive.polygonSides !in 3..64) reject("Primitive side count is out of range")
        if (!primitive.starInnerRatio.isFinite() || primitive.starInnerRatio !in 0.05f..0.95f) reject("Primitive inner ratio is out of range")
        if (!primitive.rotationDegrees.isFinite() || primitive.rotationDegrees < -180f ||
            primitive.rotationDegrees >= 180f
        ) {
            reject("Primitive rotation is out of range")
        }
        val radii = primitive.cornerRadii
        listOf(radii.topLeft, radii.topRight, radii.bottomRight, radii.bottomLeft).forEach {
            if (!it.isFinite() || it !in 0f..10_000f) reject("Primitive corner radius is out of range")
        }
        val box = primitive.sourceBox ?: reject("Editable primitive is missing its source box")
        if (!box.x.isFinite() || !box.y.isFinite() || !box.width.isFinite() || !box.height.isFinite() || box.width <= 0f || box.height <= 0f ||
            !box.x.plus(box.width).isFinite() || !box.y.plus(box.height).isFinite() ||
            listOf(box.x, box.y, box.x + box.width, box.y + box.height).any { kotlin.math.abs(it) > 1e7f }
        ) {
            reject("Primitive source box is invalid")
        }
    }

    private fun validatePoint(point: DocPoint, label: String) {
        if (!point.x.isFinite() || !point.y.isFinite() || kotlin.math.abs(point.x) > 1e7f || kotlin.math.abs(point.y) > 1e7f) {
            reject("$label is out of range")
        }
    }

    private fun validateColor(color: RgbaColor, label: String) {
        if (listOf(color.r, color.g, color.b, color.a).any { !it.isFinite() || it !in 0f..1f }) reject("$label color must be finite and normalized")
    }

    private fun putColor(out: ByteBuffer, color: RgbaColor) {
        out.putFloat(color.r).putFloat(color.g).putFloat(color.b).putFloat(color.a)
    }

    private fun putPrimitive(out: ByteBuffer, primitive: VectorPrimitiveMetadata) {
        val box = primitive.sourceBox ?: reject("Editable primitive is missing its source box")
        val radii = primitive.cornerRadii
        out.put(primitive.kind.ordinal.toByte()).put(0).putShort(0)
        out.putDouble(box.x.toDouble()).putDouble(box.y.toDouble()).putDouble(box.width.toDouble()).putDouble(box.height.toDouble())
        out.putDouble(
            radii.topLeft.toDouble(),
        ).putDouble(radii.topRight.toDouble()).putDouble(radii.bottomRight.toDouble()).putDouble(radii.bottomLeft.toDouble())
        out.putInt(primitive.polygonSides)
        out.putDouble(primitive.starInnerRatio.toDouble()).putDouble(primitive.rotationDegrees.toDouble())
    }

    private fun putAnchor(out: ByteBuffer, anchor: PathAnchor) {
        out.putLong(anchor.id.value).putDouble(anchor.point.x.toDouble()).putDouble(anchor.point.y.toDouble())
        out.put(anchor.mode.ordinal.toByte())
        out.put((if (anchor.incoming == null) 0 else 1).toByte()).put((if (anchor.outgoing == null) 0 else 1).toByte())
        repeat(5) { out.put(0) }
        out.putDouble(anchor.incoming?.x?.toDouble() ?: 0.0).putDouble(anchor.incoming?.y?.toDouble() ?: 0.0)
        out.putDouble(anchor.outgoing?.x?.toDouble() ?: 0.0).putDouble(anchor.outgoing?.y?.toDouble() ?: 0.0)
    }

    private fun readPrimitive(input: ByteBuffer): VectorPrimitiveMetadata {
        val kind = VectorShapeKind.entries.getOrNull(input.readUnsignedByte()) ?: reject("Primitive kind is unsupported")
        if (input.readUnsignedByte() != 0 || input.readUnsignedShort() != 0) reject("Primitive reserved bytes are nonzero")
        val x = input.readFiniteFloat("primitive x")
        val y = input.readFiniteFloat("primitive y")
        val width = input.readFiniteFloat("primitive width")
        val height = input.readFiniteFloat("primitive height")
        val radii = ShapeCornerRadii(
            input.readFiniteFloat("top-left radius"),
            input.readFiniteFloat("top-right radius"),
            input.readFiniteFloat("bottom-right radius"),
            input.readFiniteFloat("bottom-left radius"),
        )
        val sides = input.int
        val ratio = input.readFiniteFloat("star inner ratio")
        val rotation = input.readFiniteFloat("primitive rotation")
        return VectorPrimitiveMetadata(kind, radii, sides, ratio, rotation, VectorSourceBox(x, y, width, height))
    }

    private fun ByteBuffer.readAnchor(): PathAnchor {
        val id = PathAnchorId(long)
        val point = DocPoint(readFiniteFloat("anchor x"), readFiniteFloat("anchor y"))
        val mode = PathAnchorMode.entries.getOrNull(readUnsignedByte()) ?: reject("Anchor mode is unsupported")
        val hasIncoming = readStrictBoolean("incoming-handle-present")
        val hasOutgoing = readStrictBoolean("outgoing-handle-present")
        repeat(5) { if (readUnsignedByte() != 0) reject("Anchor reserved bytes are nonzero") }
        val ix = double
        val iy = double
        val ox = double
        val oy = double
        if (listOf(ix, iy, ox, oy).any { !it.isFinite() || kotlin.math.abs(it) > 1e7 }) reject("Handle is out of range")
        if ((!hasIncoming && (ix != 0.0 || iy != 0.0)) || (!hasOutgoing && (ox != 0.0 || oy != 0.0))) reject("Absent handle contains coordinates")
        val incoming = if (hasIncoming) DocPoint(ix.toFloat(), iy.toFloat()) else null
        val outgoing = if (hasOutgoing) DocPoint(ox.toFloat(), oy.toFloat()) else null
        return PathAnchor(id, point, incoming, outgoing, mode)
    }

    private fun ByteBuffer.readColor(): RgbaColor {
        val color = RgbaColor(float, float, float, float)
        validateColor(color, "shape")
        return color
    }

    private fun encodeName(name: String): ByteArray {
        if (name.contains('\u0000')) reject("Shape name cannot contain NUL")
        var index = 0
        while (index < name.length) {
            val char = name[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= name.length || !name[index + 1].isLowSurrogate()) reject("Shape name contains invalid Unicode")
                    index += 2
                }

                char.isLowSurrogate() -> reject("Shape name contains invalid Unicode")

                else -> index++
            }
        }
        val bytes = name.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_NAME_BYTES) reject("Shape name exceeds 1024 UTF-8 bytes")
        return bytes
    }

    private fun decodeName(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val value = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            reject("Shape name is not valid UTF-8")
        }
        if (value.contains('\u0000')) reject("Shape name cannot contain NUL")
        return value
    }

    private inline fun <T> codec(block: () -> T): ShapeLayerBridgeResult<T> = try {
        ShapeLayerBridgeResult.Success(block())
    } catch (failure: BridgeRejected) {
        failure(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Shape record is invalid")
    } catch (_: BufferUnderflowException) {
        failure(RejectionCode.INVALID_ARGUMENT, "Shape record is truncated")
    } catch (_: ArithmeticException) {
        failure(RejectionCode.INVALID_ARGUMENT, "Shape record size is out of range")
    }

    private inline fun <T> nativeCall(block: () -> ShapeLayerBridgeResult<T>): ShapeLayerBridgeResult<T> = try {
        block()
    } catch (_: UnsatisfiedLinkError) {
        failure(RejectionCode.UNSUPPORTED, "Native shape-layer bridge is unavailable")
    } catch (failure: RuntimeException) {
        failure(RejectionCode.INVALID_ARGUMENT, failure.message ?: "Native shape-layer operation failed")
    }

    private fun nativeError(fallback: String): String = try {
        nativeLastError().ifBlank { fallback }
    } catch (_: UnsatisfiedLinkError) {
        fallback
    }

    private fun failure(code: RejectionCode, message: String) = ShapeLayerBridgeResult.Failure(code, message)

    private fun reject(message: String): Nothing = throw BridgeRejected(message)

    private class BridgeRejected(message: String) : IllegalArgumentException(message)

    private fun ByteBuffer.readMagic(expected: ByteArray): Boolean {
        if (remaining() < expected.size) reject("Shape wire header is truncated")
        return ByteArray(expected.size).also(::get).contentEquals(expected)
    }

    private fun ByteBuffer.readUnsignedByte(): Int = get().toInt() and 0xff

    private fun ByteBuffer.readUnsignedShort(): Int = short.toInt() and 0xffff

    private fun ByteBuffer.readUnsignedIntBounded(maximum: Int, label: String): Int {
        val value = int
        if (value < 0 || value > maximum) reject("Shape $label is out of range")
        return value
    }

    private fun ByteBuffer.readStrictBoolean(label: String): Boolean = when (val value = readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> reject("Shape $label must be 0 or 1 (found $value)")
    }

    private fun ByteBuffer.readFiniteFloat(label: String): Float {
        val value = double
        if (!value.isFinite() || kotlin.math.abs(value) > Float.MAX_VALUE) reject("Shape $label is non-finite or outside Float range")
        val converted = value.toFloat()
        if (!converted.isFinite()) reject("Shape $label is outside Float range")
        return converted
    }

    private fun Double.toExactInt(label: String): Int {
        if (this < 0.0 || this > Int.MAX_VALUE.toDouble() || this % 1.0 != 0.0) reject("Native $label is invalid")
        return toInt()
    }

    private external fun nativeCreate(encoded: ByteArray): Long
    private external fun nativeUpdate(handle: Long, encoded: ByteArray): Boolean
    private external fun nativeEncode(handle: Long): ByteArray?
    private external fun nativeDestroy(handle: Long)
    private external fun nativeHitTest(encodedBatch: ByteArray, x: Double, y: Double, tolerance: Double): DoubleArray?
    private external fun nativeLastError(): String
}
