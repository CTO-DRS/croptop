package app.croptop.core.engine

import app.croptop.core.model.DocPoint
import app.croptop.core.model.PathAnchor
import app.croptop.core.model.PathAnchorId
import app.croptop.core.model.PathAnchorMode
import app.croptop.core.model.PathFillRule
import app.croptop.core.model.VectorPathDraft
import app.croptop.core.model.VectorSubpath

/** Converts the persistent anchor model to and from the path verbs accepted by Clipper2. */
internal object NativeVectorPathBooleanCodec {
    fun encode(path: VectorPathDraft): NativePathOpsBridge.PathData {
        val verbs = ArrayList<Byte>()
        val points = ArrayList<Double>()
        fun point(x: Double, y: Double) {
            points += x
            points += y
        }
        path.subpaths.forEach { subpath ->
            val anchors = subpath.anchors
            if (anchors.isEmpty()) return@forEach
            verbs += 0
            point(anchors.first().point.x.toDouble(), anchors.first().point.y.toDouble())
            anchors.zipWithNext().forEach { (start, end) ->
                val x0 = start.point.x.toDouble()
                val y0 = start.point.y.toDouble()
                val x1 = end.point.x.toDouble()
                val y1 = end.point.y.toDouble()
                val control1 = start.outgoing?.let { x0 + it.x to y0 + it.y } ?: (x0 to y0)
                val control2 = end.incoming?.let { x1 + it.x to y1 + it.y } ?: (x1 to y1)
                verbs += 3
                point(control1.first, control1.second)
                point(control2.first, control2.second)
                point(x1, y1)
            }
            if (subpath.closed) {
                if (anchors.size > 1) {
                    val start = anchors.last()
                    val end = anchors.first()
                    val x0 = start.point.x.toDouble()
                    val y0 = start.point.y.toDouble()
                    val x1 = end.point.x.toDouble()
                    val y1 = end.point.y.toDouble()
                    val control1 = start.outgoing?.let { x0 + it.x to y0 + it.y } ?: (x0 to y0)
                    val control2 = end.incoming?.let { x1 + it.x to y1 + it.y } ?: (x1 to y1)
                    verbs += 3
                    point(control1.first, control1.second)
                    point(control2.first, control2.second)
                    point(x1, y1)
                }
                verbs += 4
            }
        }
        return NativePathOpsBridge.PathData(verbs.toByteArray(), points.toDoubleArray())
    }

    fun decode(
        data: NativePathOpsBridge.PathData,
        source: VectorPathDraft,
    ): VectorPathDraft {
        val subpaths = mutableListOf<VectorSubpath>()
        var anchors = mutableListOf<PathAnchor>()
        var closed = false
        var pointIndex = 0
        var nextAnchorId = 1L
        fun takePoint() = DocPoint(data.points[pointIndex++].toFloat(), data.points[pointIndex++].toFloat())
        fun flush() {
            if (anchors.isNotEmpty()) subpaths += VectorSubpath(anchors.toList(), closed)
            anchors = mutableListOf()
            closed = false
        }
        data.verbs.forEach { byte ->
            when (byte.toInt()) {
                0 -> {
                    flush()
                    anchors += PathAnchor(PathAnchorId(nextAnchorId++), takePoint())
                }

                1 -> {
                    check(anchors.isNotEmpty()) { "Path segment precedes Move" }
                    anchors += PathAnchor(PathAnchorId(nextAnchorId++), takePoint())
                }

                2 -> {
                    check(anchors.isNotEmpty()) { "Path segment precedes Move" }
                    val start = anchors.last()
                    val control = takePoint()
                    val end = takePoint()
                    val c1 = DocPoint(
                        (start.point.x + (control.x - start.point.x) * (2f / 3f)),
                        (start.point.y + (control.y - start.point.y) * (2f / 3f)),
                    )
                    val c2 = DocPoint(end.x + (control.x - end.x) * (2f / 3f), end.y + (control.y - end.y) * (2f / 3f))
                    anchors[anchors.lastIndex] =
                        start.copy(outgoing = DocPoint(c1.x - start.point.x, c1.y - start.point.y), mode = PathAnchorMode.SMOOTH)
                    anchors +=
                        PathAnchor(PathAnchorId(nextAnchorId++), end, incoming = DocPoint(c2.x - end.x, c2.y - end.y), mode = PathAnchorMode.SMOOTH)
                }

                3 -> {
                    check(anchors.isNotEmpty()) { "Path segment precedes Move" }
                    val start = anchors.last()
                    val c1 = takePoint()
                    val c2 = takePoint()
                    val end = takePoint()
                    anchors[anchors.lastIndex] =
                        start.copy(outgoing = DocPoint(c1.x - start.point.x, c1.y - start.point.y), mode = PathAnchorMode.SMOOTH)
                    anchors +=
                        PathAnchor(PathAnchorId(nextAnchorId++), end, incoming = DocPoint(c2.x - end.x, c2.y - end.y), mode = PathAnchorMode.SMOOTH)
                }

                4 -> {
                    check(anchors.isNotEmpty()) { "Close precedes Move" }
                    if (anchors.size > 1 && anchors.last().point == anchors.first().point) {
                        val closingAnchor = anchors.removeAt(anchors.lastIndex)
                        anchors[0] = anchors[0].copy(incoming = closingAnchor.incoming, mode = PathAnchorMode.SMOOTH)
                    }
                    closed = true
                }

                else -> error("Unknown path verb ${byte.toInt()}")
            }
        }
        flush()
        check(pointIndex == data.points.size) { "Path contains unused coordinates" }
        return source.copy(subpaths = subpaths, fillRule = PathFillRule.NON_ZERO, primitive = null)
    }
}
