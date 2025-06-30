package maulik.barcodescanner.ui

import android.Manifest
import android.app.Dialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.Window
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maulik.barcodescanner.CloudinaryHelper
import maulik.barcodescanner.R
import maulik.barcodescanner.databinding.ActivityGalleryBinding
import maulik.barcodescanner.databinding.DialogImagePreviewBinding
import java.util.*

class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GalleryActivity"
        private const val ALBUM = "MyBusinessApp"
        private const val BATCH_ID = "BCH00002" // Fixed batch ID for now
        private val KEY_REL_PATH = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Pictures/$ALBUM/"
        } else {
            "%$ALBUM%"
        }

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, GalleryActivity::class.java))
        }
    }

    private lateinit var binding: ActivityGalleryBinding
    private val groupAdapter = GalleryGroupAdapter()
    private var allImageGroups: MutableList<Pair<String, List<Uri>>> = mutableListOf()

    private val permLauncher = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) loadImages() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        if (hasReadPerm()) loadImages() else requestReadPerm()
    }

    private fun setupUI() {
        binding.rvGroups.apply {
            layoutManager = LinearLayoutManager(this@GalleryActivity)
            adapter = groupAdapter
        }

        binding.btnBackToCamera.setOnClickListener {
            finish() // Simply finish this activity to return to the camera
        }

        // Set up the new upload button
        binding.btnUpload.setOnClickListener {
            showUploadConfirmationDialog()
        }

        binding.etSearch.setText("INV")
        binding.etSearch.doAfterTextChanged { text ->
            filterAndDisplay(text.toString())
        }

        groupAdapter.onDeleteClickListener = { qr, uris ->
            showDeleteConfirmationDialog(qr, uris)
        }

        groupAdapter.onImageClickListener = { uri ->
            showImagePreviewDialog(uri)
        }
    }

    private fun hasReadPerm(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestReadPerm() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        permLauncher.launch(perm)
    }

    private fun loadImages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val resolver = contentResolver
            val uriBase = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            } else {
                "${MediaStore.Images.Media.DATA} LIKE ?"
            }
            val args = arrayOf(KEY_REL_PATH)
            val cursor = resolver.query(uriBase, projection, selection, args, "${MediaStore.Images.Media.DATE_TAKEN} DESC")

            val groups = LinkedHashMap<String, MutableList<Uri>>()
            val idCol = cursor?.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor?.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(idCol!!)
                    val name = it.getString(nameCol!!)
                    val qr = name.substringBeforeLast('-')
                    val uri = ContentUris.withAppendedId(uriBase, id)
                    groups.getOrPut(qr) { mutableListOf() }.add(uri)
                }
            }
            groups.forEach { (_, uris) -> uris.sortBy { uri -> uri.lastPathSegment?.substringAfterLast('-')?.toIntOrNull() ?: 0 } }
            allImageGroups = groups.map { it.key to it.value }.toMutableList()

            withContext(Dispatchers.Main) {
                filterAndDisplay(binding.etSearch.text.toString())
            }
        }
    }

    private fun filterAndDisplay(query: String) {
        val filteredList = if (query.isBlank()) {
            allImageGroups
        } else {
            allImageGroups.filter { (qr, _) -> qr.contains(query, ignoreCase = true) }
        }
        groupAdapter.submit(filteredList)
        updateStats(filteredList)
    }

    private fun updateStats(groups: List<Pair<String, List<Uri>>>) {
        val groupCount = groups.size
        val imageCount = groups.sumOf { it.second.size }
        binding.tvStats.text = "Showing $groupCount records with a total of $imageCount images."
    }

    private fun showDeleteConfirmationDialog(qr: String, uris: List<Uri>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Confirm Deletion")
            .setMessage("Delete all ${uris.size} images taken of: $qr?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                deleteImageGroup(qr, uris)
            }
            .show()
    }

    private fun showImagePreviewDialog(uri: Uri) {
        val dialogBinding = DialogImagePreviewBinding.inflate(layoutInflater)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.ivPreview.setImageURI(uri)

        dialogBinding.root.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun deleteImageGroup(qr: String, uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var deletedCount = 0
            try {
                for (uri in uris) {
                    val rowsDeleted = contentResolver.delete(uri, null, null)
                    if (rowsDeleted > 0) deletedCount++
                }
                if (deletedCount == uris.size) {
                    allImageGroups.removeAll { it.first == qr }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@GalleryActivity, "$deletedCount images for $qr deleted.", Toast.LENGTH_SHORT).show()
                        filterAndDisplay(binding.etSearch.text.toString())
                    }
                } else {
                    throw Exception("Failed to delete all files. Only $deletedCount of ${uris.size} were removed.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting images for QR: $qr", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GalleryActivity, "Error: Could not complete deletion.", Toast.LENGTH_LONG).show()
                    loadImages()
                }
            }
        }
    }

    // --- NEW CLOUDINARY UPLOAD LOGIC ---

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
            .setPositiveButton("Upload") { _, _ ->
                startImageUpload()
            }
            .show()
    }

    private fun startImageUpload() {
        val groupsToUpload = groupAdapter.getCurrentData()
        val folder = "batch-$BATCH_ID"

        // Use a coroutine to perform network operations off the main thread
        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            var errorCount = 0
            val totalImages = groupsToUpload.sumOf { it.second.size }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@GalleryActivity, "Starting upload of $totalImages images...", Toast.LENGTH_SHORT).show()
            }

            for ((qr, uris) in groupsToUpload) {
                for ((index, uri) in uris.withIndex()) {
                    //
                    // --- THIS IS THE FIX ---
                    // Naming convention: INV<ID>-<frame#>
                    // The frame number is 1-based.
                    // This now correctly handles QR codes that might already have a trailing hyphen.
                    val cleanQr = qr.removeSuffix("-")
                    val publicId = "${cleanQr}-${index + 1}"
                    //
                    // --- END FIX ---
                    //

                    try {
                        val (success, message) = CloudinaryHelper.uploadImage(this@GalleryActivity, uri, folder, publicId)
                        if (success) {
                            successCount++
                        } else {
                            errorCount++
                            // Log the publicId in the error for easier debugging
                            Log.e(TAG, "Upload failed for $publicId: $message")
                        }
                    } catch (e: Exception) {
                        errorCount++
                        Log.e(TAG, "Exception during upload for $publicId", e)
                    }
                }
            }

            // Report the result on the main thread
            withContext(Dispatchers.Main) {
                val resultMessage = "Upload complete. Success: $successCount, Failed: $errorCount."
                // Use a longer duration Toast to make sure the message is seen
                Toast.makeText(this@GalleryActivity, resultMessage, Toast.LENGTH_LONG).show()
            }
        }
    }
}