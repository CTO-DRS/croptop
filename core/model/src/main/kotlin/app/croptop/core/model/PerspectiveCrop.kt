package app.croptop.core.model

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** Crop corners in clockwise perimeter order, expressed in source document pixels.
 * Coordinates lie on canvas boundaries: the valid ranges are `0..width` and `0..height`.
 */
@Serializable
data class CropQuad(
    val topLeft: DocPoint,
    val topRight: DocPoint,
    val bottomRight: DocPoint,
    val bottomLeft: DocPoint,
) {
    val points: List<DocPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/** Shared deterministic perspective-crop geometry used by FakeEngine metadata and native contract validation. */
object PerspectiveCropGeometry {
    private const val MIN_TURN = 1e-8
    private const val MAX_DIMENSION = 65_535

    fun outputSize(quad: CropQuad): DocSize? {
        val p = quad.points
        if (!isConvex(p)) return null
        fun distance(a: DocPoint, b: DocPoint) = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        val width = ((distance(p[0], p[1]) + distance(p[3], p[2])) * 0.5).roundToInt()
        val height = ((distance(p[0], p[3]) + distance(p[1], p[2])) * 0.5).roundToInt()
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null
        return DocSize(width, height)
    }

    /** Row-major source-to-destination homography; null means non-finite or degenerate input. */
    fun homography(quad: CropQuad, output: DocSize): DoubleArray? {
        val p = quad.points
        if (!isConvex(p)) return null
        val targets = listOf(
            DocPoint(0f, 0f),
            DocPoint(output.width.toFloat(), 0f),
            DocPoint(output.width.toFloat(), output.height.toFloat()),
            DocPoint(0f, output.height.toFloat()),
        )
        val matrix = Array(8) { DoubleArray(9) }
        for (i in 0..3) {
            val x = p[i].x.toDouble()
            val y = p[i].y.toDouble()
            val u = targets[i].x.toDouble()
            val v = targets[i].y.toDouble()
            matrix[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            matrix[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (column in 0..7) {
            val pivot = (column..7).maxByOrNull { kotlin.math.abs(matrix[it][column]) } ?: return null
            if (kotlin.math.abs(matrix[pivot][column]) < 1e-12) return null
            val swap = matrix[column]
            matrix[column] = matrix[pivot]
            matrix[pivot] = swap
            val divisor = matrix[column][column]
            for (j in column..8) matrix[column][j] /= divisor
            for (row in 0..7) {
                if (row == column) continue
                val factor = matrix[row][column]
                for (j in column..8) matrix[row][j] -= factor * matrix[column][j]
            }
        }
        // The eight unknowns are row-major h00..h21,h30,h31, with h22 fixed to one.
        val solved = DoubleArray(9)
        for (i in 0..7) solved[i] = matrix[i][8]
        solved[8] = 1.0
        return solved.takeIf { it.all(Double::isFinite) }
    }

    fun map(matrix: DoubleArray, point: DocPoint): DocPoint? {
        if (matrix.size != 9 || !matrix.all(Double::isFinite) || !point.x.isFinite() || !point.y.isFinite()) return null
        val x = point.x.toDouble()
        val y = point.y.toDouble()
        val w = matrix[6] * x + matrix[7] * y + matrix[8]
        if (!(w > 1e-12)) return null
        val outX = (matrix[0] * x + matrix[1] * y + matrix[2]) / w
        val outY = (matrix[3] * x + matrix[4] * y + matrix[5]) / w
        if (!outX.isFinite() || !outY.isFinite() || kotlin.math.abs(outX) > Int.MAX_VALUE || kotlin.math.abs(outY) > Int.MAX_VALUE) return null
        return DocPoint(outX.toFloat(), outY.toFloat())
    }

    private fun isConvex(points: List<DocPoint>): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val turns = (0..3).map { i ->
            val o = points[i]
            val a = points[(i + 1) % 4]
            val b = points[(i + 2) % 4]
            (a.x - o.x).toDouble() * (b.y - o.y) - (a.y - o.y).toDouble() * (b.x - o.x)
        }
        return turns.all { it > MIN_TURN } || turns.all { it < -MIN_TURN }
    }
}
