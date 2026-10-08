// Model input/output handling for the segmentation features: subject selection from a
// DeepLab-style network, click-driven object selection from MediaPipe's MagicTouch, and a model-free sky detector. Everything here
// is pure Kotlin over float tensors, so it is tested with fake sessions; the real models run through LiteRtBackend.
package app.croptop.core.ai

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** An 8-bit straight-alpha RGBA picture (a downscaled preview of the document). */
class RgbaImage(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(rgba.size.toLong() == width.toLong() * height * 4) { "RGBA data does not match ${width}x$height" }
    }

    fun red(x: Int, y: Int): Int = rgba[(y * width + x) * 4].toInt() and 0xFF
    fun green(x: Int, y: Int): Int = rgba[(y * width + x) * 4 + 1].toInt() and 0xFF
    fun blue(x: Int, y: Int): Int = rgba[(y * width + x) * 4 + 2].toInt() and 0xFF
}

/** A model's coarse mask: [alpha] is `width × height` bytes, 255 = inside. */
class MaskResult(val width: Int, val height: Int, val alpha: ByteArray) {
    init {
        require(alpha.size == width * height) { "mask data does not match ${width}x$height" }
    }

    /** Share of the area at least half inside, 0–1. */
    val coverage: Float get() = alpha.count { (it.toInt() and 0xFF) >= 128 }.toFloat() / alpha.size

    /** The opposite mask: what was inside is outside. */
    fun inverted(): MaskResult = MaskResult(width, height, ByteArray(alpha.size) { (255 - (alpha[it].toInt() and 0xFF)).toByte() })

    /** Model noise (stray values under [floor]/255, far from the object) set to zero so it cannot stretch the selection's bounds. */
    fun withoutNoise(floor: Int = NOISE_FLOOR): MaskResult = MaskResult(width, height, ByteArray(alpha.size) { if ((alpha[it].toInt() and 0xFF) < floor) 0 else alpha[it] })

    /**
     * The mask of a single clean subject: stray blobs (components under [minShare] of the largest) are dropped, holes the subject
     * encloses are filled, and the soft values in between are pushed toward 0 or 255 so faint model haze no longer shows as a
     * washed-out ghost of the background. Edges keep a short soft ramp for the later guided-filter refinement.
     */
    fun cleaned(minShare: Float = 0.15f): MaskResult {
        val n = width * height
        val inside = BooleanArray(n) { (alpha[it].toInt() and 0xFF) >= 128 }
        val label = IntArray(n)
        val sizes = ArrayList<Int>()
        val queue = IntArray(n)
        fun flood(start: Int, value: Boolean, id: Int): Int {
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = id
            while (head < tail) {
                val p = queue[head++]
                val x = p % width
                val y = p / width
                if (x > 0) tail = push(p - 1, value, id, tail, queue, inside, label)
                if (x < width - 1) tail = push(p + 1, value, id, tail, queue, inside, label)
                if (y > 0) tail = push(p - width, value, id, tail, queue, inside, label)
                if (y < height - 1) tail = push(p + width, value, id, tail, queue, inside, label)
            }
            return tail
        }
        // Foreground components.
        for (p in 0 until n) if (inside[p] && label[p] == 0) sizes += flood(p, true, sizes.size + 1)
        val largest = sizes.maxOrNull() ?: return this
        val keep = BooleanArray(sizes.size + 1) { it > 0 && sizes[it - 1] >= largest * minShare }
        val kept = BooleanArray(n) { inside[it] && keep[label[it]] }
        // Background components that touch no border are holes in the subject.
        label.fill(0)
        val outside = BooleanArray(n) { !kept[it] }
        val reaches = ArrayList<Boolean>()
        val bgSizes = ArrayList<Int>()
        for (p in 0 until n) {
            if (!outside[p] || label[p] != 0) continue
            val id = bgSizes.size + 1
            label[p] = id
            var head = 0
            var tail = 0
            queue[tail++] = p
            var border = false
            while (head < tail) {
                val q = queue[head++]
                val x = q % width
                val y = q / width
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border = true
                if (x > 0 && outside[q - 1] && label[q - 1] == 0) { label[q - 1] = id; queue[tail++] = q - 1 }
                if (x < width - 1 && outside[q + 1] && label[q + 1] == 0) { label[q + 1] = id; queue[tail++] = q + 1 }
                if (y > 0 && outside[q - width] && label[q - width] == 0) { label[q - width] = id; queue[tail++] = q - width }
                if (y < height - 1 && outside[q + width] && label[q + width] == 0) { label[q + width] = id; queue[tail++] = q + width }
            }
            bgSizes += tail
            reaches += border
        }
        val out = ByteArray(n)
        for (p in 0 until n) {
            val a = (alpha[p].toInt() and 0xFF) / 255f
            val solid = kept[p] || (outside[p] && !reaches[label[p] - 1])
            val value = if (!solid) 0f else if (!kept[p]) 1f else smoothstep(0.45f, 0.9f, a)
            out[p] = (value * 255f + 0.5f).toInt().toByte()
        }
        return MaskResult(width, height, out)
    }

    /** A point well inside the mask (as fractions of the picture): the inside pixel closest to the centre of mass. */
    fun innerPoint(): PromptSegmenter.Click? {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (y in 0 until height) for (x in 0 until width) if ((alpha[y * width + x].toInt() and 0xFF) >= 128) { sx += x; sy += y; n++ }
        if (n == 0) return null
        val cx = sx / n
        val cy = sy / n
        var best = -1
        var bestD = Double.MAX_VALUE
        for (y in 0 until height) for (x in 0 until width) {
            if ((alpha[y * width + x].toInt() and 0xFF) < 250) continue
            val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (d < bestD) { bestD = d; best = y * width + x }
        }
        if (best < 0) return null
        return PromptSegmenter.Click((best % width + 0.5f) / width, (best / width + 0.5f) / height)
    }

    /** Nearest-neighbour resample to [w] × [h]. */
    fun resized(w: Int, h: Int): MaskResult {
        if (w == width && h == height) return this
        return MaskResult(w, h, ByteArray(w * h) { i -> alpha[((i / w) * height / h) * width + (i % w) * width / w] })
    }

    /** Bilinear resample to [w] × [h] (soft edges stay soft). */
    fun resizedBilinear(w: Int, h: Int): MaskResult {
        if (w == width && h == height) return this
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * height / h - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, height - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * width / w - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, width - 1)
                val tx = fx - x0
                fun at(px: Int, py: Int) = (alpha[py * width + px].toInt() and 0xFF).toFloat()
                val v = (at(x0, y0) * (1 - tx) + at(x1, y0) * tx) * (1 - ty) + (at(x0, y1) * (1 - tx) + at(x1, y1) * tx) * ty
                out[y * w + x] = (v + 0.5f).toInt().toByte()
            }
        }
        return MaskResult(w, h, out)
    }

    /** Intersection over union of the two masks' inside areas, 0–1. */
    fun agreement(other: MaskResult): Float {
        var both = 0
        var either = 0
        for (i in alpha.indices) {
            val a = (alpha[i].toInt() and 0xFF) >= 128
            val b = (other.alpha[i].toInt() and 0xFF) >= 128
            if (a && b) both++
            if (a || b) either++
        }
        return if (either == 0) 0f else both.toFloat() / either
    }

    private fun smoothstep(lo: Float, hi: Float, v: Float): Float {
        val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun push(q: Int, value: Boolean, id: Int, tail: Int, queue: IntArray, inside: BooleanArray, label: IntArray): Int {
        if (inside[q] != value || label[q] != 0) return tail
        label[q] = id
        queue[tail] = q
        return tail + 1
    }

    companion object {
        const val NOISE_FLOOR = 16
    }
}

object ImageOps {
    /** Bilinear resize of the RGB channels to [outWidth] × [outHeight], scaled by [scale] and shifted by [offset] (0–255 → value). */
    fun resizeRgb(image: RgbaImage, outWidth: Int, outHeight: Int, scale: Float, offset: Float, channelsOut: Int = 3): FloatArray {
        val out = FloatArray(outWidth * outHeight * channelsOut)
        for (y in 0 until outHeight) {
            val fy = ((y + 0.5f) * image.height / outHeight - 0.5f).coerceIn(0f, (image.height - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, image.height - 1)
            val ty = fy - y0
            for (x in 0 until outWidth) {
                val fx = ((x + 0.5f) * image.width / outWidth - 0.5f).coerceIn(0f, (image.width - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, image.width - 1)
                val tx = fx - x0
                val base = (y * outWidth + x) * channelsOut
                for (c in 0 until 3) {
                    fun at(px: Int, py: Int) = (image.rgba[(py * image.width + px) * 4 + c].toInt() and 0xFF).toFloat()
                    val top = at(x0, y0) * (1 - tx) + at(x1, y0) * tx
                    val bottom = at(x0, y1) * (1 - tx) + at(x1, y1) * tx
                    out[base + c] = (top * (1 - ty) + bottom * ty) * scale + offset
                }
            }
        }
        return out
    }

    /** Float mask 0–1 to bytes. */
    fun toBytes(values: FloatArray): ByteArray = ByteArray(values.size) { i -> (values[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() }
}

/** Click-driven selection with MediaPipe's MagicTouch interactive segmenter (input `[1,512,512,4]`: RGB 0–1 plus a click hint). */
class PromptSegmenter(private val session: ModelSession) {
    /** A click as fractions of the image (0–1 each). */
    data class Click(val x: Float, val y: Float)

    fun segment(image: RgbaImage, clicks: List<Click>): MaskResult? {
        require(clicks.isNotEmpty()) { "choose at least one point" }
        val size = INPUT
        val input = ImageOps.resizeRgb(image, size, size, 1f / 255f, 0f, channelsOut = 4)
        for (click in clicks) {
            val cx = click.x.coerceIn(0f, 1f)
            val cy = click.y.coerceIn(0f, 1f)
            val y0 = max(0, ((cy - HINT_RADIUS) * size).toInt())
            val y1 = min(size - 1, ((cy + HINT_RADIUS) * size).toInt() + 1)
            val x0 = max(0, ((cx - HINT_RADIUS) * size).toInt())
            val x1 = min(size - 1, ((cx + HINT_RADIUS) * size).toInt() + 1)
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val dx = (x + 0.5f) / size - cx
                    val dy = (y + 0.5f) / size - cy
                    if (sqrt(dx * dx + dy * dy) <= HINT_RADIUS) input[(y * size + x) * 4 + 3] = 1f
                }
            }
        }
        val output = session.run(input, intArrayOf(1, size, size, 4))
        require(output.size == size * size) { "model returned ${output.size} values, expected ${size * size}" }
        val mask = MaskResult(size, size, ImageOps.toBytes(output)).withoutNoise()
        return mask.takeIf { it.coverage > MIN_COVERAGE }
    }

    companion object {
        const val INPUT = 512
        const val HINT_RADIUS = 0.02f
        const val MIN_COVERAGE = 0.0005f
    }
}

/** Main-subject selection from a DeepLab v3 style network (input `[1,257,257,3]` in −1…1, output `[1,257,257,classes]` logits). */
class SubjectSegmenter(private val session: ModelSession, private val classes: Int = 21) {
    fun segment(image: RgbaImage): MaskResult? {
        val size = INPUT
        val input = ImageOps.resizeRgb(image, size, size, 1f / 127.5f, -1f)
        val output = session.run(input, intArrayOf(1, size, size, 3))
        require(output.size == size * size * classes) { "model returned ${output.size} values, expected ${size * size * classes}" }
        val foreground = FloatArray(size * size)
        for (p in 0 until size * size) {
            // Everything that is not class 0 (background) counts as subject: 1 − softmax(background).
            var maxLogit = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) maxLogit = max(maxLogit, output[p * classes + c])
            var sum = 0f
            var background = 0f
            for (c in 0 until classes) {
                val e = exp(output[p * classes + c] - maxLogit)
                sum += e
                if (c == 0) background = e
            }
            foreground[p] = 1f - background / sum
        }
        val mask = MaskResult(size, size, ImageOps.toBytes(foreground)).withoutNoise().cleaned()
        val covered = mask.coverage
        return mask.takeIf { covered in MIN_COVERAGE..MAX_COVERAGE }
    }

    companion object {
        const val INPUT = 257
        const val MIN_COVERAGE = 0.01f
        const val MAX_COVERAGE = 0.97f
    }
}

/**
 * High-precision cut-out of the picture's main object with DIS IS-Net (input `[1,3,1024,1024]` NCHW RGB in `x/255 − 0.5`, output
 * `[1,1,1024,1024]`). The LiteRT export applies the sigmoid twice, so its values sit in 0.5…0.73; the logit takes them back to 0–1.
 * The result has the picture's own aspect ratio (the network sees a square).
 */
class CutoutSegmenter(private val session: ModelSession) {
    fun segment(image: RgbaImage): MaskResult? {
        val size = INPUT
        val hwc = ImageOps.resizeRgb(image, size, size, 1f / 255f, -0.5f)
        val plane = size * size
        val chw = FloatArray(3 * plane)
        for (p in 0 until plane) for (c in 0 until 3) chw[c * plane + p] = hwc[p * 3 + c]
        val output = session.run(chw, intArrayOf(1, 3, size, size))
        require(output.size == plane) { "model returned ${output.size} values, expected $plane" }
        val alpha = FloatArray(plane) { i ->
            val m = output[i].coerceIn(1e-6f, 1f - 1e-6f)
            // The network's edge runs about a pixel and a half soft and slightly outside the object, so the background's colour
            // shows as a light rim; tightening the ramp to 0.3–0.9 keeps a soft edge without the rim.
            val v = ln(m / (1f - m)).coerceIn(0f, 1f)
            val t = ((v - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
            t * t * (3f - 2f * t)
        }
        val square = MaskResult(size, size, ImageOps.toBytes(alpha)).withoutNoise()
        val mask = square.resizedBilinear(image.width, image.height)
        return mask.takeIf { it.coverage in MIN_COVERAGE..MAX_COVERAGE }
    }

    companion object {
        const val INPUT = 1024
        const val EDGE_LOW = 0.3f
        const val EDGE_HIGH = 0.9f
        const val MIN_COVERAGE = 0.002f
        const val MAX_COVERAGE = 0.98f
    }
}

/**
 * Relative depth from a MiDaS small style network (input `[1,256,256,3]` ImageNet-normalised, output one inverse-depth value per
 * pixel, larger = nearer). The result is a grayscale map in the Lens Blur convention: 0 = nearest, 255 = farthest.
 */
class DepthEstimator(private val session: ModelSession) {
    fun estimate(image: RgbaImage): MaskResult {
        val size = INPUT
        val input = ImageOps.resizeRgb(image, size, size, 1f / 255f, 0f)
        for (p in 0 until size * size) {
            for (c in 0 until 3) input[p * 3 + c] = (input[p * 3 + c] - MEAN[c]) / STD[c]
        }
        val output = session.run(input, intArrayOf(1, size, size, 3))
        require(output.size == size * size) { "model returned ${output.size} values, expected ${size * size}" }
        // Stretch between the 2nd and 98th percentile so one hot pixel cannot flatten the map.
        val sorted = output.copyOf().also { it.sort() }
        val low = sorted[(sorted.size * 0.02f).toInt()]
        val high = sorted[(sorted.size * 0.98f).toInt().coerceAtMost(sorted.size - 1)]
        require(high - low > 1e-6f && low.isFinite() && high.isFinite()) { "the model found no depth variation in this picture" }
        val far = ByteArray(output.size) { i ->
            val near = ((output[i] - low) / (high - low)).coerceIn(0f, 1f)
            ((1f - near) * 255f + 0.5f).toInt().toByte()
        }
        return MaskResult(size, size, far)
    }

    companion object {
        const val INPUT = 256
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/**
 * Model-free sky detection: bright blue or neutral pixels connected to the top edge through gently changing colour, so sky between
 * branches and across clouds is found without grabbing grey buildings, water or asphalt. Works on a downscaled copy.
 */
object SkyDetector {
    private const val LONG_SIDE = 320
    private const val STEP = 0.030f * 3
    private const val DRIFT = 0.22f * 3
    private const val MIN_SEED_SHARE = 0.05f
    private const val MIN_COVERAGE = 0.01f

    fun detect(image: RgbaImage): MaskResult? {
        val scale = LONG_SIDE.toFloat() / max(image.width, image.height)
        val w = max(1, (image.width * scale).toInt())
        val h = max(1, (image.height * scale).toInt())
        val rgb = blur(ImageOps.resizeRgb(image, w, h, 1f / 255f, 0f), w, h)
        val n = w * h
        val allowed = BooleanArray(n)
        val skyish = BooleanArray(n)
        for (i in 0 until n) {
            val r = rgb[i * 3]
            val g = rgb[i * 3 + 1]
            val b = rgb[i * 3 + 2]
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val mx = max(r, max(g, b))
            val sat = (mx - min(r, min(g, b))) / max(mx, 1e-3f)
            val blueish = b > r + 0.005f && b >= g - 0.04f
            allowed[i] = (blueish && luma > 0.28f && sat < 0.85f) || (sat < 0.18f && luma > 0.55f && b >= r - 0.03f)
            skyish[i] = (b > r + 0.01f && b >= g - 0.04f && luma > 0.30f && sat > 0.05f) || (sat < 0.16f && luma > 0.6f && b >= r - 0.015f)
        }
        val seen = BooleanArray(n)
        val ref = FloatArray(n * 3)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        var seeds = 0
        for (row in 0..min(1, h - 1)) {
            for (x in 0 until w) {
                val i = row * w + x
                if (skyish[i] && !seen[i]) {
                    seen[i] = true
                    for (c in 0 until 3) ref[i * 3 + c] = rgb[i * 3 + c]
                    queue[tail++] = i
                    seeds++
                }
            }
        }
        if (seeds < w * MIN_SEED_SHARE) return null
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            for (d in 0 until 4) {
                val nx = x + (if (d == 0) 1 else if (d == 1) -1 else 0)
                val ny = y + (if (d == 2) 1 else if (d == 3) -1 else 0)
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (seen[j] || !allowed[j]) continue
                val step = abs(rgb[j * 3] - rgb[i * 3]) + abs(rgb[j * 3 + 1] - rgb[i * 3 + 1]) + abs(rgb[j * 3 + 2] - rgb[i * 3 + 2])
                if (step > STEP) continue
                val drift = abs(rgb[j * 3] - ref[i * 3]) + abs(rgb[j * 3 + 1] - ref[i * 3 + 1]) + abs(rgb[j * 3 + 2] - ref[i * 3 + 2])
                if (drift > DRIFT) continue
                seen[j] = true
                for (c in 0 until 3) ref[j * 3 + c] = ref[i * 3 + c]
                queue[tail++] = j
            }
        }
        val closed = fillHoles(erode(dilate(dilate(seen, w, h), w, h), w, h).let { erode(it, w, h) }, w, h)
        val soft = softEdge(closed, w, h)
        val result = MaskResult(w, h, soft)
        return result.takeIf { it.coverage >= MIN_COVERAGE }
    }

    private fun blur(rgb: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(rgb.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0 until 3) {
                    var sum = 0f
                    var weight = 0f
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = (x + dx).coerceIn(0, w - 1)
                            val ny = (y + dy).coerceIn(0, h - 1)
                            val k = (if (dx == 0) 2f else 1f) * (if (dy == 0) 2f else 1f)
                            sum += rgb[(ny * w + nx) * 3 + c] * k
                            weight += k
                        }
                    }
                    out[(y * w + x) * 3 + c] = sum / weight
                }
            }
        }
        return out
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int): BooleanArray = morph(m, w, h, any = true)

    private fun erode(m: BooleanArray, w: Int, h: Int): BooleanArray = morph(m, w, h, any = false)

    private fun morph(m: BooleanArray, w: Int, h: Int, any: Boolean): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var hit = !any
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        // Outside the picture counts as "set" for erosion so the sky is not eaten at the frame edge.
                        val v = if (nx < 0 || ny < 0 || nx >= w || ny >= h) !any else m[ny * w + nx]
                        if (any && v) {
                            hit = true
                            break@loop
                        }
                        if (!any && !v) {
                            hit = false
                            break@loop
                        }
                    }
                }
                out[y * w + x] = hit
            }
        }
        return out
    }

    /** Fills regions of "not sky" that do not touch the frame (specks and small objects inside the sky). */
    private fun fillHoles(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(m.size)
        val queue = IntArray(m.size)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!m[i] && !outside[i]) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        return BooleanArray(m.size) { m[it] || !outside[it] }
    }

    private fun softEdge(m: BooleanArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(m.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val ny = (y + dy).coerceIn(0, h - 1)
                        if (m[ny * w + nx]) sum++
                        count++
                    }
                }
                out[y * w + x] = (sum * 255 / count).toByte()
            }
        }
        return out
    }
}
