package app.croptop.core.engine

/**
 * Geometric path operations (native `vector_boolean`, `curve_fit`): Unite / Subtract / Intersect / Exclude, simplify and offset. Results are
 * polygons from Clipper2; pass `refit = true` to turn them back into smooth cubic beziers (corners stay sharp, within [refitTolerance] px).
 * Paths are verbs + points: verb codes Move 0, Line 1, Quad 2, Cubic 3, Close 4; points are x, y pairs (Move 1, Line 1, Quad 2, Cubic 3, Close 0).
 */
internal object NativePathOpsBridge {
    enum class Op { UNITE, SUBTRACT, INTERSECT, EXCLUDE }
    enum class FillRule { NON_ZERO, EVEN_ODD }
    enum class Join { MITER, ROUND, BEVEL }

    class PathData(val verbs: ByteArray, val points: DoubleArray) {
        init {
            var needed = 0
            for (v in verbs) needed += when (v.toInt()) { 0, 1 -> 2; 2 -> 4; 3 -> 6; 4 -> 0; else -> throw IllegalArgumentException("unknown verb $v") }
            require(needed == points.size) { "verbs need $needed coordinates, got ${points.size}" }
        }
    }

    fun combine(
        subject: PathData, subjectRule: FillRule, clip: PathData, clipRule: FillRule, op: Op,
        tolerance: Double = 0.1, refit: Boolean = false, refitTolerance: Double = 0.25,
    ): PathData = decode(nativeCombine(subject.verbs, subject.points, subjectRule.ordinal, clip.verbs, clip.points, clipRule.ordinal, op.ordinal, tolerance, refit, refitTolerance))

    fun simplify(path: PathData, rule: FillRule, tolerance: Double = 0.1, refit: Boolean = false, refitTolerance: Double = 0.25): PathData =
        decode(nativeSimplify(path.verbs, path.points, rule.ordinal, tolerance, refit, refitTolerance))

    /** Grows ([delta] > 0) or shrinks (< 0) the filled area. */
    fun offset(
        path: PathData, rule: FillRule, delta: Double, join: Join = Join.MITER, miterLimit: Double = 4.0,
        tolerance: Double = 0.1, refit: Boolean = false, refitTolerance: Double = 0.25,
    ): PathData = decode(nativeOffset(path.verbs, path.points, rule.ordinal, delta, join.ordinal, miterLimit, tolerance, refit, refitTolerance))

    /** Net signed area in px² (outer contours positive, holes negative). */
    fun area(path: PathData, tolerance: Double = 0.1): Double {
        val a = nativeArea(path.verbs, path.points, tolerance)
        check(!a.isNaN()) { nativeLastError().ifBlank { "Path area failed" } }
        return a
    }

    private fun decode(raw: DoubleArray?): PathData {
        val r = checkNotNull(raw) { nativeLastError().ifBlank { "Path operation failed" } }
        val n = r[0].toInt()
        val verbs = ByteArray(n) { r[1 + it].toInt().toByte() }
        return PathData(verbs, r.copyOfRange(1 + n, r.size))
    }

    private external fun nativeLastError(): String
    private external fun nativeCombine(sv: ByteArray, sp: DoubleArray, sRule: Int, cv: ByteArray, cp: DoubleArray, cRule: Int, op: Int, tolerance: Double, refit: Boolean, refitTolerance: Double): DoubleArray?
    private external fun nativeSimplify(verbs: ByteArray, points: DoubleArray, rule: Int, tolerance: Double, refit: Boolean, refitTolerance: Double): DoubleArray?
    private external fun nativeOffset(verbs: ByteArray, points: DoubleArray, rule: Int, delta: Double, join: Int, miterLimit: Double, tolerance: Double, refit: Boolean, refitTolerance: Double): DoubleArray?
    private external fun nativeArea(verbs: ByteArray, points: DoubleArray, tolerance: Double): Double
}
