package maulik.barcodescanner.ui

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import maulik.barcodescanner.BatchNumberManager
import android.view.Window
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maulik.barcodescanner.CloudinaryHelper
import maulik.barcodescanner.FileMakerApiHelper
import maulik.barcodescanner.database.AppDatabase
import maulik.barcodescanner.database.ScanLogDao
import maulik.barcodescanner.database.UploadStatus
import maulik.barcodescanner.databinding.ActivityGalleryBinding
import maulik.barcodescanner.databinding.DialogZoomableImagePreviewBinding // New import
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GalleryActivity"

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, GalleryActivity::class.java))
        }
    }

    private lateinit var binding: ActivityGalleryBinding
    private val groupAdapter = GalleryGroupAdapter()
    private lateinit var scanLogDao: ScanLogDao
    private var pendingGroups: MutableList<Pair<String, List<Uri>>> = mutableListOf()

    private val fileSaveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val csvContent = generateCsvContent()
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(csvContent.toByteArray())
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@GalleryActivity, "Log successfully exported.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error writing to file", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@GalleryActivity, "Error exporting log.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        scanLogDao = AppDatabase.getInstance(this).scanLogDao()
        setupUI()
        observePendingGroups()
    }

    private fun setupUI() {
        binding.rvGroups.apply {
            layoutManager = LinearLayoutManager(this@GalleryActivity)
            adapter = groupAdapter
        }
        binding.btnBackToCamera.setOnClickListener { finish() }
        binding.btnUpload.setOnClickListener { showUploadConfirmationDialog() }
        binding.btnExportLog.setOnClickListener { exportScanLog() }
        binding.etSearch.setText("INV")
        binding.etSearch.doAfterTextChanged { text -> filterAndDisplay(text.toString()) }
        groupAdapter.onDeleteClickListener = { qr, uris -> showDeleteConfirmationDialog(qr, uris) }
        groupAdapter.onImageClickListener = { uri -> showImagePreviewDialog(uri) }
    }

    private fun observePendingGroups() {
        lifecycleScope.launch {
            AppDatabase.getInstance(this@GalleryActivity)
                .photoDao()
                .groupsWithPhotos(UploadStatus.PENDING_UPLOAD)
                .collect { groups ->
                    val mapped = groups.map { g ->
                        g.log.inventoryId to g.photos.map { Uri.parse(it.uri) }
                    }
                    pendingGroups = mapped.toMutableList()
                    filterAndDisplay(binding.etSearch.text.toString())
                }
        }
    }

    private fun filterAndDisplay(query: String) {
        val filteredList = if (query.isBlank()) {
            pendingGroups
        } else {
            pendingGroups.filter { (qr, _) -> qr.contains(query, ignoreCase = true) }
        }
        // Use submitList instead of submit
        groupAdapter.submitList(filteredList)
        updateStats(filteredList)
    }

    private fun updateStats(groups: List<Pair<String, List<Uri>>>) {
        binding.tvStats.text =
            "Showing ${groups.size} pending records · ${groups.sumOf { it.second.size }} images"
    }

    private fun showDeleteConfirmationDialog(qr: String, uris: List<Uri>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Confirm Deletion")
            .setMessage("Delete all ${uris.size} images and the scan log for: $qr?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteImageGroup(qr, uris) }
            .show()
    }

    private fun deleteImageGroup(qr: String, uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var deletedCount = 0
                for (uri in uris) {
                    if (contentResolver.delete(uri, null, null) > 0) deletedCount++
                }
                scanLogDao.deleteById(qr)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GalleryActivity, "$deletedCount images for $qr deleted.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GalleryActivity, "Error: Could not complete deletion.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showUploadConfirmationDialog() {
        val imagesToUpload = groupAdapter.getCurrentImageCount()
        if (imagesToUpload == 0) {
            Toast.makeText(this, "No images to upload.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Confirm Upload")
            .setMessage("Upload $imagesToUpload images for editing?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Upload") { _, _ -> startImageUpload() }
            .show()
    }

    private fun startImageUpload() {
        val groupsToUpload = groupAdapter.getCurrentData()
        lifecycleScope.launch(Dispatchers.IO) {
            var totalUploaded = 0
            var totalFailed = 0
            val totalImages = groupsToUpload.sumOf { it.second.size }
            val successfullyUploadedGroups = mutableMapOf<String, Int>()

            val currentBatchId = BatchNumberManager.getCurrentBatchId(this@GalleryActivity)
            Log.d(TAG, "Starting upload for batch: $currentBatchId")

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@GalleryActivity,
                    "Starting upload of $totalImages images for batch $currentBatchId...",
                    Toast.LENGTH_SHORT
                ).show()
            }

            for ((inventoryId, uris) in groupsToUpload) {
                var groupSuccessCount = 0
                var groupHasFailures = false

                for (uri in uris) {
                    val displayName = getFileName(uri) ?: "unknown_file"
                    val publicId = displayName.substringBeforeLast('.')
                    try {
                        val result = CloudinaryHelper.uploadImage(
                            this@GalleryActivity,
                            uri,
                            publicId,
                            currentBatchId
                        )
                        if (result.isSuccess) {
                            groupSuccessCount++
                        } else {
                            groupHasFailures = true
                        }
                    } catch (e: Exception) {
                        groupHasFailures = true
                        Log.e(TAG, "Exception during upload for $publicId", e)
                    }
                }

                if (!groupHasFailures && groupSuccessCount == uris.size) {
                    scanLogDao.updateUploadStatus(
                        inventoryId,
                        UploadStatus.UPLOAD_COMPLETE,
                        System.currentTimeMillis(),
                        groupSuccessCount
                    )
                    // ─── START OF FIX: Replace deletion with file moving logic ───
                    var movedCount = 0
                    for (uri in uris) {
                        try {
                            if (moveImageToBackupFolder(uri, currentBatchId)) {
                                movedCount++
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to move URI $uri to backup.", e)
                        }
                    }
                    totalUploaded += groupSuccessCount
                    successfullyUploadedGroups[inventoryId] = groupSuccessCount
                    Log.i(TAG, "Successfully uploaded and moved $movedCount files for $inventoryId to batch folder '$currentBatchId'.")
                    // ─── END OF FIX ───
                } else {
                    totalFailed += (uris.size - groupSuccessCount)
                    totalUploaded += groupSuccessCount
                    Log.e(TAG, "Upload failed for some images in group $inventoryId. Will not move files or update log.")
                }
            }

            if (totalFailed == 0 && successfullyUploadedGroups.isNotEmpty()) {
                Log.d(TAG, "All uploads successful. Preparing data for FileMaker.")
                try {
                    val rootJson = JSONObject()
                    val uploadsArray = JSONArray()
                    successfullyUploadedGroups.forEach { (invId, count) ->
                        val itemJson = JSONObject()
                        itemJson.put("inventoryId", invId)
                        itemJson.put("imageCount", count)
                        uploadsArray.put(itemJson)
                    }
                    rootJson.put("cloudinaryUploads", uploadsArray)
                    rootJson.put("batchTag", currentBatchId)
                    val jsonString = rootJson.toString()

                    FileMakerApiHelper.runCloudinaryUploadLogScript(this@GalleryActivity, jsonString)

                    BatchNumberManager.incrementBatchNumber(this@GalleryActivity)
                    Log.i(TAG, "Successfully incremented batch number.")

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to build JSON or call FileMaker API", e)
                }
            } else {
                Log.w(TAG, "Uploads had failures (Total Failed: $totalFailed). Skipping FileMaker API call and batch increment.")
            }

            withContext(Dispatchers.Main) {
                val summaryMessage = "Batch '$currentBatchId' Upload Complete\n" +
                        "Total Images: $totalImages\n" +
                        "Successfully Uploaded: $totalUploaded\n" +
                        "Failed: $totalFailed"
                MaterialAlertDialogBuilder(this@GalleryActivity)
                    .setTitle("Upload Complete")
                    .setMessage(summaryMessage)
                    .setPositiveButton("OK", null)
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /**
     * Moves an image to a batch-specific backup folder.
     * Returns true if the move was successful.
     */
    private fun moveImageToBackupFolder(uri: Uri, batchId: String): Boolean {
        val backupFolderPath = "Pictures/INVBackUp/$batchId"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 and above, update the MediaStore directly.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, backupFolderPath)
            }
            contentResolver.update(uri, values, null, null) > 0
        } else {
            // For Android 9 (API 28), use the legacy file I/O method.
            val fileName = getFileName(uri) ?: return false
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val backupDir = File(picturesDir, "INVBackUp")
            val batchDir = File(backupDir, batchId)
            if (!batchDir.exists()) {
                batchDir.mkdirs()
            }
            val destinationFile = File(batchDir, fileName)

            try {
                // Copy the file's contents to the new location.
                contentResolver.openInputStream(uri)?.use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Delete the original file from its old location.
                contentResolver.delete(uri, null, null)
                // Notify the system about the new file so it's visible in galleries.
                MediaScannerConnection.scanFile(this, arrayOf(destinationFile.absolutePath), null, null)
                true
            } catch (e: IOException) {
                Log.e(TAG, "Error during legacy file move for $fileName", e)
                false
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                }
            }
        }
        if (result == null) {
            result = uri.path?.let { File(it).name }
        }
        return result
    }

    // REPLACE the old showImagePreviewDialog with this new version
    private fun showImagePreviewDialog(uri: Uri) {
        val dialogBinding = DialogZoomableImagePreviewBinding.inflate(layoutInflater)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogBinding.root)

        // Load the image into the PhotoView using Coil
        dialogBinding.ivZoomablePreview.load(uri) {
            crossfade(true)
            // You can add a listener to dismiss the dialog when the image is clicked
        }

        // The PhotoView library automatically handles dismiss gestures (like swiping)
        // but you can also set a simple click listener to close it.
        dialogBinding.ivZoomablePreview.setOnPhotoTapListener { _, _, _ ->
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun exportScanLog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            putExtra(Intent.EXTRA_TITLE, "scan_log_$timeStamp.csv")
        }
        fileSaveLauncher.launch(intent)
    }

    private suspend fun generateCsvContent(): String {
        val logs = scanLogDao.getAllLogs()
        if (logs.isEmpty()) return "No logs found."

        val csvBuilder = StringBuilder()
        csvBuilder.append("Inventory ID,Scan Timestamp,Status,Upload Timestamp,Image Count\n")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        logs.forEach { log ->
            val scanDate = dateFormat.format(Date(log.scanTimestamp))
            val uploadDate = log.uploadTimestamp?.let { dateFormat.format(Date(it)) } ?: "N/A"
            csvBuilder.append("\"${log.inventoryId}\",")
            csvBuilder.append("\"$scanDate\",")
            csvBuilder.append("\"${log.status}\",")
            csvBuilder.append("\"$uploadDate\",")
            csvBuilder.append("${log.uploadedImageCount}\n")
        }
        return csvBuilder.toString()
    }
}