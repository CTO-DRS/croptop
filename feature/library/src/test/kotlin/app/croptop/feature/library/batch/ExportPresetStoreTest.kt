package app.croptop.feature.library.batch

import app.croptop.core.engine.api.ExportFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportPresetStoreTest {
    private class Memory(var text: String? = null) : PresetStorage {
        override fun read() = text
        override fun write(json: String) {
            text = json
        }
    }

    private val mine = ExportPreset("Instagram", ExportFormat.JPEG, 90, 40, true)

    @Test
    fun savedPresetsSurviveARestartAndReplaceByName() {
        val memory = Memory()
        val store = ExportPresetStore(memory)
        assertTrue(store.save(mine))
        assertTrue(store.save(mine.copy(quality = 80)))
        val reopened = ExportPresetStore(memory)
        assertEquals(listOf(mine.copy(quality = 80)), reopened.user.value)
        reopened.delete("Instagram")
        assertEquals(emptyList(), ExportPresetStore(memory).user.value)
    }

    @Test
    fun builtInNamesAreProtectedAndCorruptStorageIsIgnored() {
        val store = ExportPresetStore(Memory("not json"))
        assertEquals(emptyList(), store.user.value)
        assertFalse(store.save(ExportPresetStore.BUILT_IN.first().copy(quality = 1)))
    }

    @Test
    fun presetBuildsExportOptions() {
        val options = mine.toOptions()
        assertEquals(0.4f, options.scale)
        assertEquals(ExportFormat.JPEG, options.format)
        assertTrue(options.stripMetadata)
    }
}
