package app.croptop.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.data.db.CropTopDatabase
import app.croptop.core.model.DocSize
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
class RecentDocumentsRepositoryTest {
    private lateinit var db: CropTopDatabase
    private lateinit var repo: RoomRecentDocumentsRepository
    private val released = mutableListOf<String>()
    private val grants = object : UriGrants {
        override fun release(uri: String) {
            released += uri
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CropTopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomRecentDocumentsRepository(db.recentDocuments(), grants) { uri -> if (uri.endsWith("/27")) "IMG_2041.jpg" else null }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `most recent first, reopen moves to top, pinned stay first`() = runTest {
        repo.recordOpened("content://a", "a.jpg", DocSize(10, 10), now = 1)
        repo.recordOpened("content://b", "b.jpg", null, now = 2)
        assertEquals(listOf("b.jpg", "a.jpg"), repo.observe().first().map { it.name })
        repo.recordOpened("content://a", "a.jpg", DocSize(10, 10), now = 3)
        assertEquals(listOf("a.jpg", "b.jpg"), repo.observe().first().map { it.name })
        repo.setPinned("content://b", true)
        assertEquals(listOf("b.jpg", "a.jpg"), repo.observe().first().map { it.name })
        assertEquals(null, repo.observe().first().first().size)
    }

    @Test
    fun `list is trimmed but pinned survive`() = runTest {
        repo.recordOpened("content://pinned", "p", null, now = 0)
        repo.setPinned("content://pinned", true)
        repeat(RoomRecentDocumentsRepository.MAX_RECENTS + 5) { repo.recordOpened("content://$it", "$it", null, now = it + 1L) }
        val all = repo.observe(limit = 200).first()
        assertEquals(RoomRecentDocumentsRepository.MAX_RECENTS + 1, all.size)
        assertEquals("p", all.first().name)
        // The 5 oldest unpinned fell off and had their grants released; pinned survived.
        assertEquals((0 until 5).map { "content://$it" }, released)
        repo.remove("content://pinned")
        assertEquals(RoomRecentDocumentsRepository.MAX_RECENTS, repo.observe(limit = 200).first().size)
        assertEquals("content://pinned", released.last())
    }

    @Test
    fun `raw media ids are repaired to the file name`() = kotlinx.coroutines.test.runTest {
        repo.recordOpened("content://media/external/images/media/27", "image:27", null)
        repo.recordOpened("content://x/doc.png", "doc.png", null)
        val names = repo.observe().first().map { it.name }.toSet()
        kotlin.test.assertEquals(setOf("IMG_2041.jpg", "doc.png"), names)
        kotlin.test.assertEquals(setOf("IMG_2041.jpg", "doc.png"), repo.observe().first().map { it.name }.toSet())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentPresetsRepositoryTest {
    @Test
    fun `presets round trip and upsert by name`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CropTopDatabase::class.java,
        ).allowMainThreadQueries().build()
        val repo = RoomDocumentPresetsRepository(db.documentPresets())
        val spec = app.croptop.core.model.NewDocumentSpec(name = "Etsy banner", size = DocSize(3360, 840), resolutionPpi = 72f)
        repo.save(spec, now = 1)
        repo.save(spec.copy(size = DocSize(3000, 800)), now = 2)
        val all = repo.presets.first()
        assertEquals(1, all.size)
        assertEquals(DocSize(3000, 800), all.single().size)
        assertEquals("Etsy banner", all.single().name)
        repo.delete("Etsy banner")
        assertEquals(0, repo.presets.first().size)
        db.close()
    }
}
