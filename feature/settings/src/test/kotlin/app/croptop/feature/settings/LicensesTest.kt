package app.croptop.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LicensesTest {
    @Test
    fun `parses the native notice table`() {
        val md = File("../../core/engine/THIRD_PARTY.md").readText()
        val rows = parseNoticeTable(md)
        assertTrue(rows.size >= 6, "rows: $rows")
        val zstd = rows.first { it.name.startsWith("Zstandard") }
        assertTrue(zstd.license.contains("BSD-3-Clause"))
        assertEquals("1.5.7", zstd.version)
    }

    @Test
    fun `app license notice lists direct Kotlin and AndroidX dependencies`() {
        val notice = File("../../app/licenses/jvm/JVM-THIRD_PARTY.md").readText()
        val apache = File("../../app/licenses/jvm/apache-2.0.txt").readText()
        val datastoreProtobuf = File("../../app/licenses/jvm/datastore-preferences-external-protobuf-LICENSE.txt").readText()

        assertTrue(notice.contains("AndroidX Compose"))
        assertTrue(notice.contains("2.4.20"))
        assertTrue(notice.contains("2.60.1"))
        assertTrue(notice.contains("2.8.5"))
        assertTrue(notice.contains("DataStore External Protobuf | 1.2.1 | BSD-3-Clause"))
        assertTrue(notice.contains("Apache-2.0"))
        assertTrue(apache.contains("Apache License"))
        assertTrue(apache.contains("Version 2.0, January 2004"))
        assertTrue(datastoreProtobuf.contains("Redistribution and use in source and binary forms"))
        assertTrue(datastoreProtobuf.contains("Neither the name of the copyright holder"))
        assertTrue(AppLicenses.filter { it.license == "Apache-2.0" }.all { it.textAsset == "apache-2.0.txt" })
    }

    @Test
    fun `native notice rows link only to checked-in license texts`() {
        val nativeDir = File("../../core/engine/licenses")
        val entries = parseNoticeTable(File("../../core/engine/THIRD_PARTY.md").readText())
        val linked = entries.flatMap { entry ->
            licenseAssetsFor(entry).map { asset -> entry.name to asset }
        }

        assertTrue(linked.size >= 10, "linked license texts: $linked")
        linked.forEach { (component, asset) ->
            assertTrue(File(nativeDir, asset).isFile, "$component points to missing $asset")
        }
        assertTrue(entries.first { it.name.startsWith("Android zlib") }.let { licenseAssetsFor(it).isEmpty() })
        assertTrue(entries.first { it.name.startsWith("GoogleTest") }.let { licenseAssetsFor(it).isEmpty() })
    }
}
