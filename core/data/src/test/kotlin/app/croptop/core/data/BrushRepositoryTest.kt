package app.croptop.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.croptop.core.data.db.CropTopDatabase
import app.croptop.core.model.BrushCategory
import app.croptop.core.model.BrushPreset
import app.croptop.core.model.BrushSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
class BrushRepositoryTest {
    private lateinit var db: CropTopDatabase
    private lateinit var repo: RoomBrushRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CropTopDatabase::class.java).allowMainThreadQueries().build()
        repo = RoomBrushRepository(db.brushes())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `built-ins, user presets, favorites, rename, delete`() = runTest {
        assertEquals(BrushPreset.BuiltIn.map { it.name }, repo.presets.first().map { it.name })
        val id = repo.create("  My Liner ", BrushSettings(size = 7f))
        repo.setFavorite(id, true)
        repo.setFavorite(-2, true)
        var all = repo.presets.first()
        val mine = all.last()
        assertEquals("My Liner", mine.name)
        assertEquals(BrushCategory.CUSTOM, mine.category)
        assertTrue(mine.favorite && all.first { it.id == -2L }.favorite)
        repo.update(id, BrushSettings(size = 9f))
        repo.rename(id, "Liner")
        repo.rename(-1, "Nope") // built-ins are read-only
        all = repo.presets.first()
        assertEquals(9f, all.last().settings.size)
        assertEquals("Liner", all.last().name)
        assertEquals("Hard Round", all.first().name)
        repo.delete(id)
        repo.delete(-1)
        all = repo.presets.first()
        assertEquals(BrushPreset.BuiltIn.size, all.size)
    }

    @Test
    fun `export and import round trip, bad files are explained`() = runTest {
        val bytes = repo.export(BrushPreset.BuiltIn.take(3))
        assertEquals(3, repo.import(bytes))
        val imported = repo.presets.first().drop(BrushPreset.BuiltIn.size)
        assertEquals(BrushPreset.BuiltIn.take(3).map { it.name to it.settings.normalized() }, imported.map { it.name to it.settings })
        assertFailsWith<BrushFormatException> { repo.import("{}".toByteArray()) }
        assertFailsWith<BrushFormatException> { repo.import(byteArrayOf(1, 2, 3)) }
    }
}
