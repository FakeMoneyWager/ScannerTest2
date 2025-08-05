package maulik.barcodescanner.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ScanLog::class, Photo::class], version =5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scanLogDao(): ScanLogDao
    abstract fun photoDao(): PhotoDao

    companion object {
        // Volatile annotation ensures that the instance is always up-to-date and the same for all execution threads.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            // synchronized block ensures that only one thread can execute this block at a time.
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scan_log_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5) // Add the new migration here
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_log ADD COLUMN is_duplicate INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 2. DEFINE THE MIGRATION FROM VERSION 4 TO 5
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the new 'batch_number' column to the 'scan_log' table.
                // We provide a default value for existing rows.
                db.execSQL("ALTER TABLE scan_log ADD COLUMN batch_number TEXT NOT NULL DEFAULT 'BX00000'")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Migration logic from version 1 to 2, if any.
            }
        }

        /* one-line migration: create the new table */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS photo_table (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        inventory_id TEXT    NOT NULL,
                        uri          TEXT    NOT NULL,
                        taken_at     INTEGER NOT NULL,
                        FOREIGN KEY(inventory_id) REFERENCES scan_log(inventory_id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photo_table_inventory_id ON photo_table(inventory_id)")
            }
        }
    }
}