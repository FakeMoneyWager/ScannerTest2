/* Photo.kt --------------------------------------------------------------- */
package maulik.barcodescanner.database

import androidx.room.*

@Entity(
    tableName = "photo_table",
    foreignKeys = [ForeignKey(
        entity         = ScanLog::class,
        parentColumns  = ["inventory_id"],
        childColumns   = ["inventory_id"],
        onDelete       = ForeignKey.CASCADE
    )],
    // An index on inventory_id will speed up queries for photos of a specific item.
    indices = [Index("inventory_id")]
)
data class Photo(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "inventory_id")
    val inventoryId: String,

    /** MediaStore URI as a plain string */
    @ColumnInfo(name = "uri")
    val uri: String,

    /**
     * The timestamp when the photo was saved.
     * This value MUST be provided when creating a Photo object.
     * It is not set automatically.
     */
    @ColumnInfo(name = "taken_at")
    val takenAt: Long
)