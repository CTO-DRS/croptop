package app.croptop.core.ai

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TilePlanTest {
    @Test
    fun smallImagesGetOneTileOfTheirOwnSize() {
        assertEquals(listOf(Tile(0, 0, 100, 50)), TilePlanner.plan(100, 50, 512, 32))
        assertEquals(listOf(Tile(0, 0, 512, 512)), TilePlanner.plan(512, 512, 512, 32))
    }

    @Test
    fun theLastTileIsShiftedBackToTheEdgeAndAllTilesAreFullSize() {
        val tiles = TilePlanner.plan(1000, 700, 512, 64)
        assertTrue(tiles.all { it.width == 512 && it.height == 512 })
        assertEquals(listOf(0, 448, 488), tiles.map { it.x }.distinct().sorted().let { it.take(2) + it.last() })
        assertEquals(1000, tiles.maxOf { it.right })
        assertEquals(700, tiles.maxOf { it.bottom })
        assertTrue(tiles.all { it.x >= 0 && it.y >= 0 && it.right <= 1000 && it.bottom <= 700 })
    }

    @Test
    fun everyPixelIsCoveredAndNeighboursOverlapByAtLeastTheRequestedAmount() {
        val width = 333
        val height = 211
        val tile = 64
        val overlap = 16
        val tiles = TilePlanner.plan(width, height, tile, overlap)
        val seen = Array(height) { BooleanArray(width) }
        tiles.forEach { t -> for (y in t.y until t.bottom) for (x in t.x until t.right) seen[y][x] = true }
        assertTrue(seen.all { row -> row.all { it } })
        val xs = tiles.map { it.x }.distinct().sorted()
        assertTrue(xs.zipWithNext().all { (a, b) -> a + tile - b >= overlap })
    }

    @Test
    fun rejectsInvalidArguments() {
        assertFailsWith<IllegalArgumentException> { TilePlanner.plan(0, 10, 8, 1) }
        assertFailsWith<IllegalArgumentException> { TilePlanner.plan(10, 10, 0, 0) }
        assertFailsWith<IllegalArgumentException> { TilePlanner.plan(10, 10, 8, 8) }
        assertFailsWith<IllegalArgumentException> { TilePlanner.plan(10, 10, 8, -1) }
    }

    /** A network that returns its input unchanged must give back the input exactly, however the tiles overlap. */
    @Test
    fun blendingAnIdentityNetworkReconstructsTheImage() {
        val cases = listOf(listOf(100, 70, 32, 8), listOf(257, 129, 64, 16), listOf(50, 40, 64, 8), listOf(96, 96, 32, 0))
        for ((width, height, tile, overlap) in cases) {
            val channels = 3
            val image = FloatArray(width * height * channels) { i -> ((i * 37) % 255) / 255f }
            val blender = TileBlender(width, height, channels, overlap)
            for (t in TilePlanner.plan(width, height, tile, overlap)) {
                val data = FloatArray(t.width * t.height * channels)
                for (y in 0 until t.height) {
                    for (x in 0 until t.width) {
                        for (c in 0 until channels) {
                            data[(y * t.width + x) * channels + c] = image[((t.y + y) * width + t.x + x) * channels + c]
                        }
                    }
                }
                blender.add(t, data)
            }
            val out = blender.result()
            val worst = image.indices.maxOf { abs(out[it] - image[it]) }
            assertTrue(worst < 1e-5f, "identity blend drifted by $worst for ${width}x$height tile $tile overlap $overlap")
        }
    }

    /** Two tiles that disagree cross-fade smoothly through the overlap band instead of jumping. */
    @Test
    fun seamsCrossFadeMonotonically() {
        val width = 64
        val height = 1
        val blender = TileBlender(width, height, 1, overlap = 16)
        val tiles = TilePlanner.plan(width, height, 40, 16)
        assertEquals(2, tiles.size)
        blender.add(tiles[0], FloatArray(40) { 0f })
        blender.add(tiles[1], FloatArray(40) { 1f })
        val out = blender.result()
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(1f, out[width - 1], 1e-6f)
        assertTrue(out.toList().zipWithNext().all { (a, b) -> b >= a - 1e-6f }, "the seam is not monotone")
        val jumps = out.toList().zipWithNext().maxOf { (a, b) -> b - a }
        assertTrue(jumps < 0.15f, "the seam jumps by $jumps")
    }

    @Test
    fun uncoveredPixelsStayZeroAndBadInputsAreRejected() {
        val blender = TileBlender(10, 10, 1, 2)
        blender.add(Tile(0, 0, 5, 5), FloatArray(25) { 1f })
        val out = blender.result()
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(0f, out[9 * 10 + 9], 0f)
        assertFailsWith<IllegalArgumentException> { blender.add(Tile(8, 8, 5, 5), FloatArray(25)) }
        assertFailsWith<IllegalArgumentException> { blender.add(Tile(0, 0, 5, 5), FloatArray(24)) }
    }
}
