package app.croptop.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import app.croptop.core.data.db.CropTopDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every schema version must migrate to the latest. Add a step per new version. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CropTopDatabase::class.java)

    @Test
    fun migrate1To2KeepsRecents() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO recent_documents (uri, name, width, height, thumbnailPath, lastOpenedAt, pinned) " +
                    "VALUES ('content://a', 'a.jpg', 10, 10, NULL, 1, 0)",
            )
        }
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT name FROM recent_documents").use { c ->
                check(c.moveToFirst() && c.getString(0) == "a.jpg")
            }
            db.query("SELECT COUNT(*) FROM document_presets").use { c -> check(c.moveToFirst() && c.getInt(0) == 0) }
        }
    }

    @Test
    fun migrate2To3AddsCatalog() {
        helper.createDatabase(DB3, 2).close()
        helper.runMigrationsAndValidate(DB3, 3, true).use { db ->
            db.query("SELECT COUNT(*) FROM catalog_images").use { c -> check(c.moveToFirst() && c.getInt(0) == 0) }
        }
    }

    @Test
    fun migrate4To5KeepsCatalogAndAddsIptc() {
        helper.createDatabase(DB5, 4).use { db ->
            db.execSQL(
                "INSERT INTO catalog_images (uri, folderUri, name, mimeType, sizeBytes, lastModified, width, height, metadataVersion, " +
                    "rating, flag, label, addedAt) VALUES ('content://a', 'content://t', 'a.jpg', 'image/jpeg', 1, 1, 0, 0, 1, 4, 0, 0, 1)",
            )
        }
        helper.runMigrationsAndValidate(DB5, 5, true).use { db ->
            db.query("SELECT rating, title FROM catalog_images").use { c ->
                check(c.moveToFirst() && c.getInt(0) == 4 && c.isNull(1))
            }
        }
    }

    @Test
    fun migrate5To6BackfillsIsRaw() {
        helper.createDatabase(DB6, 5).use { db ->
            db.execSQL(
                "INSERT INTO catalog_images (uri, folderUri, name, mimeType, sizeBytes, lastModified, width, height, metadataVersion, " +
                    "rating, flag, label, addedAt) VALUES ('content://a', 'content://t', 'A.RAF', 'image/x-fuji', 1, 1, 0, 0, 1, 0, 0, 0, 1)," +
                    " ('content://b', 'content://t', 'b.jpg', 'image/jpeg', 1, 1, 0, 0, 1, 0, 0, 0, 1)",
            )
        }
        helper.runMigrationsAndValidate(DB6, 6, true).use { db ->
            db.query("SELECT name, isRaw FROM catalog_images ORDER BY name").use { c ->
                check(c.moveToFirst() && c.getString(0) == "A.RAF" && c.getInt(1) == 1)
                check(c.moveToNext() && c.getString(0) == "b.jpg" && c.getInt(1) == 0)
            }
        }
    }

    @Test
    fun migrate6To7BackfillsSortKey() {
        helper.createDatabase(DB7, 6).use { db ->
            db.execSQL("INSERT INTO library_folders (treeUri, name, addedAt, lastScanAt) VALUES ('content://t', 'T', 1, 1)")
            db.execSQL(
                "INSERT INTO catalog_images (uri, folderUri, name, mimeType, sizeBytes, lastModified, width, height, metadataVersion, " +
                    "rating, flag, label, addedAt, isRaw, takenAt) VALUES ('content://a', 'content://t', 'a.jpg', 'image/jpeg', 1, 5, 0, 0, 1, " +
                    "0, 0, 0, 1, 0, 900), ('content://b', 'content://t', 'b.jpg', 'image/jpeg', 1, 7, 0, 0, 1, 0, 0, 0, 1, 0, NULL)",
            )
        }
        helper.runMigrationsAndValidate(DB7, 7, true).use { db ->
            db.query("SELECT name, sortKey FROM catalog_images ORDER BY name").use { c ->
                check(c.moveToFirst() && c.getLong(1) == 900L)
                check(c.moveToNext() && c.getLong(1) == 7L)
            }
        }
    }

    @Test
    fun migrate7To8AddsCollections() {
        helper.createDatabase(DB8, 7).close()
        helper.runMigrationsAndValidate(DB8, 8, true).use { db ->
            db.query("SELECT COUNT(*) FROM catalog_collections").use { c -> check(c.moveToFirst() && c.getInt(0) == 0) }
        }
    }

    @Test
    fun migrate9To10AddsVirtualCopiesAndKeepsDevelopSettings() {
        helper.createDatabase(DB10, 9).use { db ->
            db.execSQL("INSERT INTO develop_settings (uri, json, updatedAt) VALUES ('content://a', '{}', 1)")
        }
        helper.runMigrationsAndValidate(DB10, 10, true).use { db ->
            db.query("SELECT COUNT(*) FROM develop_settings").use { c -> check(c.moveToFirst() && c.getInt(0) == 1) }
            db.query("SELECT COUNT(*) FROM develop_virtual_copies").use { c -> check(c.moveToFirst() && c.getInt(0) == 0) }
        }
    }

    @Test
    fun migrate10To11AddsSnapshotsAndKeepsDevelopData() {
        helper.createDatabase(DB11, 10).use { db ->
            db.execSQL("INSERT INTO develop_settings (uri, json, updatedAt) VALUES ('content://a', '{}', 1)")
            db.execSQL("INSERT INTO develop_virtual_copies (imageUri, name, createdAt) VALUES ('content://a', 'Copy', 1)")
        }
        helper.runMigrationsAndValidate(DB11, 11, true).use { db ->
            db.query("SELECT COUNT(*) FROM develop_settings").use { c -> check(c.moveToFirst() && c.getInt(0) == 1) }
            db.query("SELECT COUNT(*) FROM develop_virtual_copies").use { c -> check(c.moveToFirst() && c.getInt(0) == 1) }
            db.query("SELECT COUNT(*) FROM develop_snapshots").use { c -> check(c.moveToFirst() && c.getInt(0) == 0) }
        }
    }

    @Test
    fun migrate8To9AddsBrushes() {
        helper.createDatabase(DB9, 8).close()
        helper.runMigrationsAndValidate(DB9, 9, true).close()
    }

    private companion object {
        const val DB9 = "migration-test-9"
        const val DB11 = "migration-test-11"
        const val DB10 = "migration-test-10"
        const val DB8 = "migration-test-8"
        const val DB7 = "migration-test-7"
        const val DB6 = "migration-test-6"
        const val DB5 = "migration-test-5"
        const val DB3 = "migration-test-3"
        const val DB = "migration-test"
    }
}
