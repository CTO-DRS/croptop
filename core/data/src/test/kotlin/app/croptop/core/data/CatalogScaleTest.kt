package app.croptop.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.data.catalog.RoomCatalogRepository
import app.croptop.core.data.db.CatalogImageEntity
import app.croptop.core.data.db.CropTopDatabase
import app.croptop.core.model.Flag
import app.croptop.core.model.LibraryFilter
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Acceptance: queries over a 50k-image catalog. Robolectric's SQLite is slower than a
 * device, so the bound here is loose (the device target is < 50 ms); the test mainly catches
 * accidental full-table work such as a missing index or per-row subqueries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogScaleTest {
    private lateinit var db: CropTopDatabase
    private lateinit var repo: RoomCatalogRepository

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CropTopDatabase::class.java).allowMainThreadQueries().build()
        repo = RoomCatalogRepository(
            db.catalog(),
            { emptyList() },
            { null },
            object : UriGrants {
                override fun release(uri: String) = Unit
            },
        )
        (0 until 20).forEach { db.catalog().upsertFolder(app.croptop.core.data.db.LibraryFolderEntity("content://t/f$it", "Folder $it", 0)) }
        val rows = (1..N).map { i ->
            CatalogImageEntity(
                uri = "content://t/f${i % 20}/IMG_$i.${if (i % 4 == 0) "raf" else "jpg"}",
                folderUri = "content://t/f${i % 20}",
                name = "IMG_$i.${if (i % 4 == 0) "raf" else "jpg"}",
                mimeType = "image/jpeg",
                sizeBytes = 10_000_000,
                lastModified = i.toLong(),
                takenAt = 1_700_000_000_000 + i * 1000L,
                camera = if (i % 3 == 0) "Fujifilm X-T5" else "Sony A7 IV",
                rating = i % 6,
                flag = if (i % 10 == 0) 1 else 0,
                metadataVersion = 2,
                addedAt = 0,
                isRaw = i % 4 == 0,
            )
        }
        db.catalog().replaceFolderContents(rows, emptyList())
    }

    @After
    fun tearDown() = db.close()

    /** Best of [RUNS] after a warm-up (statement cache, JIT): scheduling noise only ever adds time. */
    private suspend fun timeMs(filter: LibraryFilter): Pair<Int, Double> {
        repo.images(filter).first()
        var n = 0
        val ns = (1..RUNS).minOf { measureNanoTime { n = repo.images(filter).first().size } }
        return n to ns / 1e6
    }

    @Test
    fun `50k catalog queries stay fast`() = runTest {
        val cases = listOf(
            "all" to LibraryFilter(),
            "folder" to LibraryFilter(folderUri = "content://t/f7"),
            "4+ stars picked" to LibraryFilter(minRating = 4, flag = Flag.PICK),
            "text" to LibraryFilter(query = "x-t5"),
            "raw only" to LibraryFilter(rawOnly = true),
        )
        val slim = (1..RUNS).minOf { runSlim() }
        println("catalog 50k · slim cursor all: ${"%.1f".format(slim / 1e6)} ms")
        // A busy host slows every query alike, so the budget scales with this run's raw-cursor baseline.
        // A missing index or per-row subquery is still many times slower than a plain scan.
        val budget = maxOf(BUDGET_MS, slim / 1e6 * BASELINE_FACTOR)
        val sortOnly = measureNanoTime {
            db.openHelper.readableDatabase.query("SELECT id FROM catalog_images ORDER BY sortKey DESC, name").use { c ->
                while (c.moveToNext()) c.getLong(0)
            }
        }
        println("catalog 50k · ids only sorted: ${"%.1f".format(sortOnly / 1e6)} ms")
        val results = cases.associate { (name, f) -> name to timeMs(f) }
        results.forEach { (name, r) -> println("catalog 50k · $name: ${r.first} rows in ${"%.1f".format(r.second)} ms") }
        assertEquals(N, results.getValue("all").first)
        assertEquals(N / 20, results.getValue("folder").first)
        assertEquals(N / 4, results.getValue("raw only").first)
        results.forEach { (name, r) -> assertTrue(r.second < budget, "$name took ${r.second} ms (budget $budget ms)") }
        pagingStaysFast(budget)
    }

    /** The row fetch the grid needs, straight from a cursor: the host's floor for a full scan. */
    private fun runSlim(): Long = measureNanoTime {
        db.openHelper.readableDatabase.query(
            "SELECT id, uri, name, rating, flag, label FROM catalog_images ORDER BY sortKey DESC, name",
        ).use { c ->
            var k = 0
            while (c.moveToNext()) {
                c.getLong(0)
                c.getString(1)
                c.getString(2)
                c.getInt(3)
                c.getInt(4)
                c.getInt(5)
                k++
            }
        }
    }

    /** Paging: the grid only ever loads a page, wherever it is scrolled to. */
    private suspend fun pagingStaysFast(budget: Double) {
        repo.ids(LibraryFilter()).first()
        var ids = emptyList<Long>()
        val idsMs = measureNanoTime { ids = repo.ids(LibraryFilter()).first() } / 1e6
        println("catalog 50k · ordered ids: ${ids.size} in ${"%.1f".format(idsMs)} ms")
        assertEquals(N, ids.size)
        for (sort in app.croptop.core.model.LibrarySort.entries) {
            val source = db.catalog().pageGrid(app.croptop.core.data.db.catalogQuery(0, null, null, "", null, false, sort = sort))
            source.load(androidx.paging.PagingSource.LoadParams.Refresh(N / 2, 120, true))
            val ms = measureNanoTime { source.load(androidx.paging.PagingSource.LoadParams.Refresh(N / 2 + 120, 120, true)) } / 1e6
            println("catalog 50k · sort $sort, page at ${N / 2}: ${"%.1f".format(ms)} ms")
            assertTrue(ms < budget, "sort $sort page took $ms ms")
        }
        for (offset in listOf(0, N / 2, N - 120)) {
            val source = db.catalog().pageGrid(app.croptop.core.data.db.catalogQuery(0, null, null, "", null, false))
            var page: androidx.paging.PagingSource.LoadResult.Page<Int, app.croptop.core.data.db.CatalogGridRow>? = null
            val ms = measureNanoTime {
                page = source.load(androidx.paging.PagingSource.LoadParams.Refresh(offset, 120, true))
                    as androidx.paging.PagingSource.LoadResult.Page
            } / 1e6
            println("catalog 50k · page at $offset: ${page!!.data.size} rows in ${"%.1f".format(ms)} ms")
            assertEquals(ids.subList(offset, offset + 120), page.data.map { it.id })
            assertTrue(ms < budget, "page at $offset took $ms ms")
        }
    }

    private companion object {
        const val N = 50_000
        const val BUDGET_MS = 1_500.0
        const val BASELINE_FACTOR = 3.0
        const val RUNS = 3
    }
}
