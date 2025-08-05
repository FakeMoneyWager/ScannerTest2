package maulik.barcodescanner.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

object UploadStatus {
    const val PENDING_UPLOAD = "PENDING_UPLOAD"
    const val UPLOAD_COMPLETE = "UPLOAD_COMPLETE"
}

@Entity(tableName = "scan_log")
data class ScanLog(
    @PrimaryKey
    @ColumnInfo(name = "inventory_id")
    val inventoryId: String,

    @ColumnInfo(name = "batch_number")
    val batchNumber: String, // e.g., "BX00001"

    @ColumnInfo(name = "scan_timestamp")
    val scanTimestamp: Long,

    @ColumnInfo(name = "status")
    var status: String = UploadStatus.PENDING_UPLOAD,

    @ColumnInfo(name = "upload_timestamp")
    var uploadTimestamp: Long? = null,

    @ColumnInfo(name = "uploaded_image_count")
    var uploadedImageCount: Int = 0,

    @ColumnInfo(name = "is_duplicate", defaultValue = "0") // Explicitly set default value
    var isDuplicate: Boolean = false,


)