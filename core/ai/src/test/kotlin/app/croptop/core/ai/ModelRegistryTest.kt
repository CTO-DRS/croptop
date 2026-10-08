package app.croptop.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun spec(
    id: String = "u2net-small",
    version: Int = 1,
    task: ModelTask = ModelTask.SEGMENT_SUBJECT,
    files: List<ModelFile> = listOf(ModelFile("model.tflite", "a".repeat(64), 1000, "https://example.com/model.tflite")),
    license: ModelLicense = ModelLicense("Apache-2.0", commercialUse = true),
) = ModelSpec(id, version, task, "Test model", files, license)

class ModelRegistryTest {
    @Test
    fun aValidSpecHasNoProblems() {
        assertEquals(emptyList(), spec().problems())
        assertEquals(1000L, spec().totalBytes)
    }

    @Test
    fun rejectsMalformedEntriesWithReasons() {
        assertTrue(spec(id = "Bad Id").problems().any { "id" in it })
        assertTrue(spec(version = 0).problems().any { "version" in it })
        assertTrue(spec(files = emptyList()).problems().any { "no files" in it })
        assertTrue(spec(files = listOf(ModelFile("../x.bin", "a".repeat(64), 5, "https://e.com/x"))).problems().any { "plain file name" in it })
        assertTrue(spec(files = listOf(ModelFile("x.bin", "XYZ", 5, "https://e.com/x"))).problems().any { "sha256" in it })
        assertTrue(spec(files = listOf(ModelFile("x.bin", "a".repeat(64), 0, "https://e.com/x"))).problems().any { "positive size" in it })
        assertTrue(spec(files = listOf(ModelFile("x.bin", "a".repeat(64), 5, "http://e.com/x"))).problems().any { "https" in it })
        val twice = ModelFile("x.bin", "a".repeat(64), 5, "https://e.com/x")
        assertTrue(spec(files = listOf(twice, twice)).problems().any { "duplicate file" in it })
    }

    @Test
    fun onlyCommerciallyUsableLicensesRegister() {
        assertTrue(spec(license = ModelLicense("Apache-2.0", commercialUse = false)).problems().any { "commercial" in it })
        assertTrue(spec(license = ModelLicense("CC-BY-NC-4.0", commercialUse = true)).problems().any { "non-commercial" in it })
        assertTrue(spec(license = ModelLicense("AGPL-3.0-only", commercialUse = true)).problems().any { "non-commercial" in it })
        assertTrue(spec(license = ModelLicense("MIT", commercialUse = true)).problems().isEmpty())
        assertTrue(spec(license = ModelLicense("BSD-3-Clause", commercialUse = true)).problems().isEmpty())
    }

    @Test
    fun registryKeepsGoodEntriesAndReportsTheRest() {
        val registry = ModelRegistry.of(
            listOf(
                spec(id = "a"),
                spec(id = "a", version = 2),
                spec(id = "b", task = ModelTask.DEPTH),
                spec(id = "c", license = ModelLicense("CC-BY-NC-4.0", commercialUse = false)),
            ),
        )
        assertEquals(listOf("a", "b"), registry.models.map { it.id }.sorted())
        assertEquals(2, registry.rejected.size)
        assertTrue(registry.rejected.any { it.id == "a" && "duplicate id" in it.problems })
        assertTrue(registry.rejected.any { it.id == "c" })
        assertNotNull(registry["a"])
        assertNull(registry["zzz"])
        assertEquals(listOf("b"), registry.forTask(ModelTask.DEPTH).map { it.id })
    }

    @Test
    fun manifestRoundTripsAndToleratesUnknownKeys() {
        val registry = ModelRegistry.of(listOf(spec(id = "a"), spec(id = "b", task = ModelTask.UPSCALE)))
        val parsed = ModelRegistry.parse(registry.encode())
        assertEquals(registry.models.sortedBy { it.id }, parsed.models.sortedBy { it.id })
        val future = registry.encode().replaceFirst("{", """{"futureField": [1, 2],""")
        assertEquals(2, ModelRegistry.parse(future).models.size)
    }

    @Test
    fun unreadableOrFutureManifestsFailLoudly() {
        assertFailsWith<IllegalArgumentException> { ModelRegistry.parse("not json") }
        assertFailsWith<IllegalArgumentException> { ModelRegistry.parse("""{"schema": 99, "models": []}""") }
    }
}
