package app.croptop.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.data.db.CropTopDatabase
import app.croptop.core.model.BasicTone
import app.croptop.core.model.DevelopSettings
import app.croptop.core.model.developKey
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DevelopVirtualCopyTest {
    private lateinit var db: CropTopDatabase
    private lateinit var repo: RoomDevelopRepository
    private val uri = "content://photos/IMG_1.dng"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CropTopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomDevelopRepository(db.develop())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun copiesHaveTheirOwnRecipeAndLeaveTheOriginalAlone() = runTest {
        val original = DevelopSettings(basic = BasicTone(exposure = 0.5f))
        repo.save(uri, original)
        val bright = DevelopSettings(basic = BasicTone(exposure = 2f))
        val id = repo.createCopy(uri, bright, "  High key  ")
        assertEquals(listOf("High key"), repo.copies(uri).first().map { it.name })
        assertEquals(bright, repo.settings(developKey(uri, id)).first())
        assertEquals(original, repo.settings(uri).first())
        repo.save(developKey(uri, id), DevelopSettings())
        assertEquals(original, repo.settings(uri).first(), "editing the copy never touches the original")
    }

    @Test
    fun renameDeleteAndOtherPhotosAreIsolated() = runTest {
        val a = repo.createCopy(uri, DevelopSettings(basic = BasicTone(contrast = 20f)), "A")
        val b = repo.createCopy("content://photos/other.dng", DevelopSettings(), "B")
        repo.renameCopy(a, "Contrasty")
        assertEquals(listOf("Contrasty"), repo.copies(uri).first().map { it.name })
        repo.renameCopy(a, "   ")
        assertEquals(listOf("Contrasty"), repo.copies(uri).first().map { it.name }, "a blank name is ignored")
        repo.deleteCopy(uri, a)
        assertTrue(repo.copies(uri).first().isEmpty())
        assertEquals(DevelopSettings(), repo.settings(developKey(uri, a)).first())
        assertEquals(1, repo.copies("content://photos/other.dng").first().size)
        assertTrue(b > 0)
    }

    @Test
    fun snapshotsStoreARecipeEachAndStayWithTheirPhoto() = runTest {
        val warm = DevelopSettings(basic = BasicTone(exposure = 1f))
        val first = repo.createSnapshot(uri, "  Warm  ", warm, now = 10)
        repo.createSnapshot(uri, "", DevelopSettings(basic = BasicTone(contrast = 30f)), now = 20)
        repo.createSnapshot("content://photos/other.dng", "Elsewhere", DevelopSettings(), now = 30)
        val listed = repo.snapshots(uri).first()
        assertEquals(listOf("Warm", "Snapshot"), listed.map { it.name })
        assertEquals(warm, listed.first().settings)
        assertEquals(10L, listed.first().createdAt)
        repo.deleteSnapshot("content://photos/other.dng", first)
        assertEquals(2, repo.snapshots(uri).first().size, "a snapshot is only deleted through its own photo")
        repo.deleteSnapshot(uri, first)
        assertEquals(listOf("Snapshot"), repo.snapshots(uri).first().map { it.name })
        assertEquals(1, repo.snapshots("content://photos/other.dng").first().size)
    }
}
