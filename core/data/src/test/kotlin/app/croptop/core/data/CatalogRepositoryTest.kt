package app.croptop.core.data

import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.data.catalog.ImageMetadata
import app.croptop.core.data.catalog.RoomCatalogRepository
import app.croptop.core.data.catalog.ScannedFile
import app.croptop.core.data.db.CropTopDatabase
import app.croptop.core.model.ColorLabel
import app.croptop.core.model.Flag
import app.croptop.core.model.LibraryFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogRepositoryTest {
    private lateinit var db: CropTopDatabase
    private lateinit var repo: RoomCatalogRepository
    private var files = mutableListOf<ScannedFile>()
    private val tree = "content://tree/photos"

    private fun f(name: String, modified: Long = 1) = ScannedFile("$tree/$name", name, "image/jpeg", 100, modified)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CropTopDatabase::class.java).allowMainThreadQueries().build()
        repo = RoomCatalogRepository(
            db.catalog(),
            { files.toList() },
            { uri ->
                ImageMetadata(
                    6000, 4000, if ("a" in uri) 2_000 else 1_000, "Fujifilm X-T5", "XF33mmF1.4", 400, "1/250 s", 2.8f, 33f,
                    caption = "From camera", creator = "Camera Owner",
                )
            },
            object : UriGrants {
                override fun release(uri: String) = Unit
            },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `scan, metadata, rating and filters`() = runTest {
        files = mutableListOf(f("a.jpg"), f("b.raf"), f("c.jpg"))
        assertEquals(3, repo.addFolder(tree, "Photos", now = 10))
        assertEquals(3, repo.folders.first().single().imageCount)
        assertEquals(3, repo.backfillMetadata())
        assertEquals(0, repo.backfillMetadata())
        val all = repo.images(LibraryFilter()).first()
        assertEquals("a.jpg", all.first().name) // newest takenAt first
        assertEquals("Fujifilm X-T5", repo.image(all.first().id).first()?.camera) // full detail for one photo

        val b = all.first { it.name == "b.raf" }
        repo.setRating(listOf(b.id), 4)
        repo.setFlag(listOf(b.id), Flag.PICK)
        repo.setLabel(listOf(b.id), ColorLabel.GREEN)
        assertEquals(listOf("b.raf"), repo.images(LibraryFilter(minRating = 3)).first().map { it.name })
        assertEquals(listOf("b.raf"), repo.images(LibraryFilter(flag = Flag.PICK)).first().map { it.name })
        assertEquals(listOf("b.raf"), repo.images(LibraryFilter(rawOnly = true)).first().map { it.name })
        assertEquals(3, repo.images(LibraryFilter(query = "x-t5")).first().size)
        repo.addKeywords(listOf(b.id), listOf("Sunset"))
        assertEquals(listOf("b.raf"), repo.images(LibraryFilter(query = "sunset")).first().map { it.name })
    }

    @Test
    fun `iptc prefill from file never overwrites edits, batch edit, search`() = runTest {
        files = mutableListOf(f("a.jpg"), f("b.jpg"))
        repo.addFolder(tree, "Photos")
        val ids = repo.images(LibraryFilter()).first().map { it.id }
        repo.setIptc(listOf(ids[0]), app.croptop.core.model.IptcEdit(creator = "Kim"))
        repo.backfillMetadata()
        val a = repo.image(ids[0]).first()!!
        val b = repo.image(ids[1]).first()!!
        assertEquals("Kim", a.creator)
        assertEquals("Camera Owner", b.creator)
        assertEquals("From camera", a.caption)
        repo.setIptc(ids, app.croptop.core.model.IptcEdit(title = "Harbor at dusk", caption = " "))
        val all = ids.map { repo.image(it).first()!! }
        assertEquals(listOf("Harbor at dusk", "Harbor at dusk"), all.map { it.title })
        assertEquals(listOf(null, null), all.map { it.caption })
        assertEquals(2, repo.images(LibraryFilter(query = "harbor")).first().size)
    }

    @Test
    fun `rescan keeps ratings, drops deleted files, adds new`() = runTest {
        files = mutableListOf(f("a.jpg"), f("b.jpg"))
        repo.addFolder(tree, "Photos")
        val a = repo.images(LibraryFilter()).first().first { it.name == "a.jpg" }
        repo.setRating(listOf(a.id), 5)
        files = mutableListOf(f("a.jpg"), f("d.jpg"))
        repo.addFolder(tree, "Photos")
        val names = repo.images(LibraryFilter()).first().map { it.name }.sorted()
        assertEquals(listOf("a.jpg", "d.jpg"), names)
        assertEquals(5, repo.images(LibraryFilter()).first().first { it.name == "a.jpg" }.rating)
        repo.removeFolder(tree)
        assertEquals(0, repo.images(LibraryFilter()).first().size)
    }

    @Test
    fun `paged grid, ids and id lookups agree with the list query`() = runTest {
        files = (1..300).map { f("p$it.${if (it % 3 == 0) "raf" else "jpg"}", modified = it.toLong()) }.toMutableList()
        repo.addFolder(tree, "Photos", now = 10)
        val filter = LibraryFilter(rawOnly = true)
        val expected = repo.images(filter).first()
        assertEquals(100, expected.size)
        assertEquals(expected, repo.pagedImages(filter).asSnapshot { scrollTo(99) })
        assertEquals(expected.map { it.id }, repo.ids(filter).first())
        val pick = listOf(expected[5].id, expected[0].id, -1L)
        assertEquals(listOf(expected[5], expected[0]), repo.images(pick).first())
        assertEquals(listOf(expected[5].uri, expected[0].uri), repo.uris(pick))
    }

    @Test
    fun `collections, smart collections and sort orders`() = runTest {
        files = mutableListOf(f("b.jpg", modified = 3), f("a.raf", modified = 1), f("C.jpg", modified = 2))
        repo.addFolder(tree, "Photos", now = 10)
        val byName = repo.images(LibraryFilter()).first().associateBy { it.name }
        repo.setRating(listOf(byName.getValue("a.raf").id), 5)

        val trip = repo.createCollection("  Trip  ")
        repo.addToCollection(trip, listOf(byName.getValue("b.jpg").id, byName.getValue("a.raf").id))
        repo.addToCollection(trip, listOf(byName.getValue("b.jpg").id)) // duplicates are ignored
        val smart = repo.createCollection("Best RAW", smart = LibraryFilter(minRating = 4, rawOnly = true, folderUri = tree))
        val cols = repo.collections.first()
        assertEquals(listOf("Best RAW", "Trip"), cols.map { it.name })
        assertEquals(2, cols.first { it.id == trip }.imageCount)
        // Smart collections save criteria only: no folder, no sort.
        assertEquals(LibraryFilter(minRating = 4, rawOnly = true), cols.first { it.id == smart }.smart)
        assertEquals(listOf("a.raf"), repo.images(cols.first { it.id == smart }.smart!!).first().map { it.name })

        assertEquals(setOf("a.raf", "b.jpg"), repo.images(LibraryFilter(collectionId = trip)).first().map { it.name }.toSet())
        val live = repo.ids(LibraryFilter(collectionId = trip))
        assertEquals(2, live.first().size)
        repo.removeFromCollection(trip, listOf(byName.getValue("a.raf").id))
        assertEquals(1, live.first().size) // the grid's query watches collection membership
        assertEquals(listOf("b.jpg"), repo.images(LibraryFilter(collectionId = trip)).first().map { it.name })

        suspend fun names(sort: app.croptop.core.model.LibrarySort) = repo.images(LibraryFilter(sort = sort)).first().map { it.name }
        assertEquals(listOf("b.jpg", "C.jpg", "a.raf"), names(app.croptop.core.model.LibrarySort.CAPTURE_NEWEST))
        assertEquals(listOf("a.raf", "C.jpg", "b.jpg"), names(app.croptop.core.model.LibrarySort.CAPTURE_OLDEST))
        assertEquals(listOf("C.jpg", "a.raf", "b.jpg"), names(app.croptop.core.model.LibrarySort.NAME))
        assertEquals("a.raf", names(app.croptop.core.model.LibrarySort.RATING).first())

        repo.renameCollection(trip, "Japan")
        repo.deleteCollection(smart)
        assertEquals(listOf("Japan"), repo.collections.first().map { it.name })
        // Removing the folder removes its photos from collections too.
        repo.removeFolder(tree)
        assertEquals(0, repo.collections.first().single().imageCount)
    }
}
