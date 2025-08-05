package maulik.barcodescanner.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScanLogDao {
    /**
     * Inserts a new scan log. If a log with the same inventoryId already exists,
     * it will be ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(scanLog: ScanLog)

    /**
     * Updates the status of a specific inventory ID after a successful upload.
     */
    @Query("UPDATE scan_log SET status = :status, upload_timestamp = :timestamp, uploaded_image_count = :count WHERE inventory_id = :inventoryId")
    suspend fun updateUploadStatus(inventoryId: String, status: String, timestamp: Long, count: Int)

    /**
     * Called when the user chooses “Yes” on a duplicate-scan dialog.
     *  • marks the record as *PENDING_UPLOAD* again
     *  • updates the scan timestamp so it shows up as “new” in the Gallery
     *  • clears any previous upload info
     */
    @Query("""
        UPDATE scan_log
           SET status               = :status,
               scan_timestamp       = :timestamp,
               upload_timestamp     = NULL,
               uploaded_image_count = 0,
               is_duplicate         = :isDuplicate
         WHERE inventory_id = :inventoryId
    """)
    suspend fun resetForDuplicate(
        inventoryId: String,
        status: String,
        isDuplicate: Boolean,
        timestamp: Long
    )

    /**
     * Retrieves all scan logs that are pending upload, ordered by the most recent first.
     */
    @Query("SELECT * FROM scan_log WHERE status = :status ORDER BY scan_timestamp DESC")
    suspend fun getLogsWithStatus(status: String): List<ScanLog>

    /**
     * Deletes a specific log from the database. Used when the user manually deletes a group.
     */
    @Query("DELETE FROM scan_log WHERE inventory_id = :inventoryId")
    suspend fun deleteById(inventoryId: String)

    /**
     * Retrieves a single scan log by its unique inventory ID.
     * Returns null if no log is found.
     */
    @Query("SELECT * FROM scan_log WHERE inventory_id = :inventoryId LIMIT 1")
    suspend fun getLogById(inventoryId: String): ScanLog?

    /**
     * Retrieves all scan logs from the database, ordered by the most recent scan first.
     */
    @Query("SELECT * FROM scan_log ORDER BY scan_timestamp DESC")
    suspend fun getAllLogs(): List<ScanLog>

    /**
     * Retrieves all scan logs where the inventory ID starts with a given base ID.
     * This is used to detect duplicates (e.g., find "INV123" and "INV123-DUP-12345").
     */
    @Query("SELECT * FROM scan_log WHERE inventory_id LIKE :baseId || '%'")
    suspend fun getLogsStartingWith(baseId: String): List<ScanLog>

    // --- THIS IS THE FUNCTION THE COMPILER CANNOT FIND ---
    /**
     * Returns the total number of records in the scan log table, regardless of status.
     * This is used for the "Lifetime Total" display.
     */
    @Query("SELECT COUNT(inventory_id) FROM scan_log")
    suspend fun getLogCount(): Int

    /**
     * Returns the number of records that match a given upload status (e.g., "PENDING_UPLOAD").
     * This is used for the "Current Batch" display.
     */
    @Query("SELECT COUNT(inventory_id) FROM scan_log WHERE status = :status")
    suspend fun getLogCountByStatus(status: String): Int
}