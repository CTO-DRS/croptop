package app.croptop.feature.editor

import app.croptop.core.model.BasicTone
import app.croptop.core.model.DevelopSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EditorRouteSerializationTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun `raw develop recipe survives route serialization`() {
        val route = EditorRoute(
            sourceUri = "content://photos/photo.raf",
            rawDevelopSettings = DevelopSettings(basic = BasicTone(exposure = -1.25f)),
        )

        val restored = json.decodeFromString<EditorRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `routes serialized before develop handoff still decode without recipe`() {
        val restored = json.decodeFromString<EditorRoute>("""{"sourceUri":"content://photos/photo.raf"}""")

        assertEquals("content://photos/photo.raf", restored.sourceUri)
        assertNull(restored.rawDevelopSettings)
    }

    @Test
    fun `replacement source route preserves parent document and layer through navigation save state`() {
        val route = EditorRoute(
            sourceUri = "content://replacement/source.png",
            smartObjectParentDocumentId = 41L,
            smartObjectParentLayerId = 73L,
        )

        val restored = json.decodeFromString<EditorRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }
}
