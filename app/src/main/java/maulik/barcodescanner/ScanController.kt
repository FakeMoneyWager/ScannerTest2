package maulik.barcodescanner.ui
import maulik.barcodescanner.database.UploadStatus
import maulik.barcodescanner.database.AppDatabase
import maulik.barcodescanner.BatchNumberManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maulik.barcodescanner.FileMakerApiHelper
import maulik.barcodescanner.analyzer.MLKitBarcodeAnalyzer
import maulik.barcodescanner.analyzer.ScanningResultListener
import maulik.barcodescanner.analyzer.ZXingBarcodeAnalyzer

import maulik.barcodescanner.database.Photo
import maulik.barcodescanner.database.ScanLog
import maulik.barcodescanner.databinding.ActivityBarcodeScanningBinding

/* ─────────────────────────  constants  ───────────────────────── */

private const val QR_PREFIX          = "INV"
private const val CUSTOM_PHOTO_ALBUM = "MyBusinessApp"

class ScanController(
    private val ctx: Context,
    private val binding: ActivityBarcodeScanningBinding,
    private val scope: LifecycleCoroutineScope
) : ScanningResultListener {

    /* -------- state -------- */

    var lastScannedValue: String? = null
        private set
    val photoHistory: MutableList<Uri> = mutableListOf()

    private var isProcessingScan  = false
    private var photoObserverJob: Job? = null

    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
    private val TAG     = "ScanController"

    /* ─────────────────────────  analyzer factory  ───────────────────────── */

    fun createAnalyzer(sdk: BarcodeScanningActivity.ScannerSDK) =
        if (sdk == BarcodeScanningActivity.ScannerSDK.ZXING)
            ZXingBarcodeAnalyzer(this)
        else
            MLKitBarcodeAnalyzer(this)

    /* ─────────────────────────  result callback  ───────────────────────── */

    override fun onScanned(result: String) {
        if (result == lastScannedValue) {
            return
        }
        if (!result.startsWith(QR_PREFIX)) {
            toneGen.startTone(ToneGenerator.TONE_SUP_ERROR, 200); return
        }
        if (isProcessingScan) return
        isProcessingScan = true

        val previewBmp = binding.cameraPreview.bitmap
        if (previewBmp == null) { isProcessingScan = false; return }

        scope.launch {
            val shotUri = saveBitmapAsPhoto(previewBmp, result, isFirst = true)
            if (shotUri == null) { isProcessingScan = false; return@launch }

            val isSameAsCurrent = result == lastScannedValue
            val isInLog         = AppDatabase.getInstance(ctx)
                .scanLogDao().getLogById(result) != null

            if (isSameAsCurrent || isInLog) {
                withContext(Dispatchers.Main) { showDuplicateAndRevert(shotUri) }
                return@launch
            }

            if (lastScannedValue != null && photoHistory.size < 3) {
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Too Few Photos")
                        .setMessage(
                            "A minimum of 3 photos must be taken for the last item " +
                                    "before scanning a new one."
                        )
                        .setPositiveButton("OK") { _, _ -> isProcessingScan = false }
                        .setCancelable(false)
                        .show()
                }
                deleteJustCaptured(shotUri)
                return@launch
            }
            toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 200)
            withContext(Dispatchers.Main) { handleNewScan(result, shotUri) }
        }
    }

    /* ─────────────────────────  duplicate / new logic  ─────────────────── */

    private fun showDuplicateAndRevert(shotUri: Uri) {
        toneGen.startTone(ToneGenerator.TONE_SUP_ERROR, 200)
        // Immediately delete the invalid image
        scope.launch(Dispatchers.IO) {
            ctx.contentResolver.delete(shotUri, null, null)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Duplicate QR")
            .setMessage("This QR code has already been scanned.\nPlease scan a different item.")
            .setPositiveButton("OK") { _, _ ->
                if (photoHistory.isNotEmpty())
                    loadThumbnail(photoHistory.last())
                else
                    binding.btnThumbnail.setImageBitmap(null)

                isProcessingScan = false
                updateUiState()
            }
            .setCancelable(false)
            .show()
    }


    private fun handleNewScan(inv: String, firstShotUri: Uri) {
        lastScannedValue = inv
        startPhotoObserver(inv)
        updateUiState()

        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(ctx)

            // Get the current batch ID from our new manager
            val currentBatchId = BatchNumberManager.getCurrentBatchId(ctx)

            // Update the ScanLog constructor call to include the batch ID
            db.scanLogDao().insert(
                ScanLog(
                    inventoryId = inv,
                    batchNumber = currentBatchId, // <- HERE
                    scanTimestamp = System.currentTimeMillis()
                )
            )
            db.photoDao().insert(
                Photo(
                    inventoryId = inv,
                    uri = firstShotUri.toString(),
                    takenAt = System.currentTimeMillis()
                )
            )
            FileMakerApiHelper.runPrintScript(ctx, inv, currentBatchId)

            withContext(Dispatchers.Main) {
                isProcessingScan = false
                updateUiState()
            }
        }
    }

    /* ─────────────────────────  media helpers  ───────────────────────── */

    private fun deleteJustCaptured(uri: Uri) = scope.launch(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(ctx).photoDao()
        try   { ctx.contentResolver.delete(uri, null, null) }
        finally { dao.deleteByUri(uri.toString()) }
    }

    private fun startPhotoObserver(inv: String) {
        photoObserverJob?.cancel()
        photoObserverJob = scope.launch {
            AppDatabase.getInstance(ctx).photoDao()
                .photosForInventory(inv)
                .collect { list ->
                    val uris = list.map { Uri.parse(it.uri) }
                    withContext(Dispatchers.Main) {
                        photoHistory.clear(); photoHistory.addAll(uris)
                        if (photoHistory.isNotEmpty())
                            loadThumbnail(photoHistory.last())
                        else
                            binding.btnThumbnail.setImageBitmap(null)
                        updateUiState()
                    }
                }
        }
    }

    /* save preview bitmap (no DB write here) */
    private suspend fun saveBitmapAsPhoto(
        bmp: Bitmap,
        qr: String,
        isFirst: Boolean
    ): Uri? = withContext(Dispatchers.IO) {

        val name = "${qr.removeSuffix("-")}-${if (isFirst) 1 else photoHistory.size + 1}"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE,  "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$CUSTOM_PHOTO_ALBUM")
        }

        var saved: Uri? = null
        try {
            val uri = ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            uri?.let {
                ctx.contentResolver.openOutputStream(it)?.use { out ->
                    if (bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) saved = it
                }
            }
        } catch (e: Exception) { Log.e(TAG, "saveBitmap failed", e) }

        withContext(Dispatchers.Main) { updateUiState() }
        saved
    }

    /* ─────────────────────────  UI helpers  ───────────────────────── */

    fun loadThumbnail(uri: Uri) {
        try {
            val bmp: Bitmap =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ctx.contentResolver.loadThumbnail(uri, Size(120, 120), null)
                else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        ctx.contentResolver,
                        uri.lastPathSegment!!.toLong(),
                        MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                    )
                }
            binding.btnThumbnail.setImageBitmap(bmp)
        } catch (_: Exception) {
            binding.btnThumbnail.setImageBitmap(null)
        }
    }

    fun deleteLastPhoto() {
        if (photoHistory.size <= 1) return
        deleteJustCaptured(photoHistory.last())
    }

    fun updateUiState() {
        val hasQr = lastScannedValue != null

        binding.tvStatusDisplay.text = lastScannedValue ?: "Scan QR 1ST!"
        binding.tvInvSuffix.text     = if (hasQr) lastScannedValue!!.removeSuffix("-").takeLast(3) else ""
        binding.tvInvSuffix.visibility =
            if (hasQr) android.view.View.VISIBLE else android.view.View.INVISIBLE

        binding.btnShutter.isEnabled = hasQr && !isProcessingScan
        binding.btnShutter.alpha     = if (binding.btnShutter.isEnabled) 1f else 0.4f

        binding.tvSuffixCounter.text =
            maxOf(photoHistory.size - 1, 0).toString()
        binding.tvSuffixCounter.visibility =
            if (hasQr) android.view.View.VISIBLE else android.view.View.GONE

        val canDelete = photoHistory.size > 1
        binding.btnDeleteLast.isEnabled  = canDelete
        binding.btnDeleteLast.visibility =
            if (canDelete) android.view.View.VISIBLE else android.view.View.INVISIBLE

        val hasPhoto = photoHistory.isNotEmpty()
        binding.btnThumbnail.isEnabled = hasPhoto
        binding.btnThumbnail.alpha     = if (hasPhoto) 1f else 0.4f
        if (!hasPhoto) binding.btnThumbnail.setImageBitmap(null)
    }

    /* ─────────────────────────  restore state  ───────────────────────── */

    fun restoreCameraState() {
        scope.launch(Dispatchers.IO) {
            val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            else
                "${MediaStore.Images.Media.DATA} LIKE ?"
            val args = arrayOf("%$CUSTOM_PHOTO_ALBUM%")

            val latest = ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                sel, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst())
                    c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                else null
            }

            withContext(Dispatchers.Main) {
                if (latest != null) {
                    val invBase = latest.substringBeforeLast('-')
                    val invId = "$invBase-"

                    scope.launch {
                        val log = AppDatabase.getInstance(ctx).scanLogDao().getLogById(invId)

                        if (log != null && log.status == UploadStatus.PENDING_UPLOAD) {
                            withContext(Dispatchers.Main) {
                                lastScannedValue = invId
                                startPhotoObserver(invId)
                            }
                        } else {
                            updateUiState()
                        }
                    }
                } else {
                    updateUiState()
                }
            }
        }
    }

    /* ─────────────────────────  cleanup  ───────────────────────── */

    fun dispose() {
        photoObserverJob?.cancel()
        toneGen.release()
    }
}