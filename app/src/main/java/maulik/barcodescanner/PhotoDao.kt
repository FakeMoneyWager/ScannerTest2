/* PhotoDao.kt ----------------------------------------------------------- */
package maulik.barcodescanner.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Insert
    suspend fun insert(photo: Photo)

    @Query("DELETE FROM photo_table WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("SELECT * FROM photo_table WHERE inventory_id = :inv ORDER BY taken_at")
    fun photosForInventory(inv: String): Flow<List<Photo>>

    /* Groups + photos for the Gallery screen ---------------------------- */
    @Transaction
    @Query("""
        SELECT * FROM scan_log
        WHERE status = :status
        ORDER BY scan_timestamp DESC
    """)
    fun groupsWithPhotos(status: String = UploadStatus.PENDING_UPLOAD)
            : Flow<List<ScanWithPhotos>>

    // ⬇️ START OF ADDED FUNCTION
    /**
     * Returns the total number of photos in the photos table.
     */
    @Query("SELECT COUNT(uri) FROM photo_table")
    suspend fun getPhotoCount(): Int
    // ⬆️ END OF ADDED FUNCTION
    @Query("""
        SELECT COUNT(p.uri)
        FROM photo_table p
        INNER JOIN scan_log s ON p.inventory_id = s.inventory_id
        WHERE s.status = :status
    """)
    suspend fun getPhotoCountByStatus(status: String): Int
}

/* Projection used above -------------------------------------------------- */
data class ScanWithPhotos(
    @Embedded val log: ScanLog,
    @Relation(
        parentColumn = "inventory_id",
        entityColumn = "inventory_id"
    )
    val photos: List<Photo>
)