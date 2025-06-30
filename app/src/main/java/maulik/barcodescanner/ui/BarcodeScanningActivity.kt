package maulik.barcodescanner.ui




import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maulik.barcodescanner.FileMakerApiHelper
import maulik.barcodescanner.analyzer.MLKitBarcodeAnalyzer
import maulik.barcodescanner.analyzer.ScanningResultListener
import maulik.barcodescanner.analyzer.ZXingBarcodeAnalyzer
import maulik.barcodescanner.databinding.ActivityBarcodeScanningBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val ARG_SCANNING_SDK = "scanning_SDK"
private const val ZOOM_RATIO_STANDARD = 1.0f
private const val ZOOM_RATIO_MACRO = 1.5f
private const val ZOOM_RATIO_WIDE = 0.6f
enum class CameraMode { STANDARD, MACRO, WIDE }

@OptIn(ExperimentalCamera2Interop::class)
class BarcodeScanningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BarcodeScanningActivity"
        private const val CAMERA_DIAGNOSTIC_TAG = "CameraInfo"
        private const val CUSTOM_PHOTO_ALBUM = "MyBusinessApp"
        private const val QR_PREFIX = "INV"
        private const val LOCKED_WHITE_BALANCE = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT

        @JvmStatic
        fun start(context: Context, scannerSDK: ScannerSDK) {
            val intent = Intent(context, BarcodeScanningActivity::class.java).apply {
                putExtra(ARG_SCANNING_SDK, scannerSDK)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityBarcodeScanningBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var isProcessingScan = false
    private var lastScannedValue: String? = null
    private val photoHistory = mutableListOf<Uri>()
    private var captureInProgress = 0
    private var scannerSDK: ScannerSDK = ScannerSDK.MLKIT
    private var isPortraitMode = true
    private var manualRotation = Surface.ROTATION_0
    private var currentMode = CameraMode.STANDARD
    private var teleCameraId: String? = null
    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarcodeScanningBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logAllCameraInfo()

        cameraExecutor = Executors.newSingleThreadExecutor()
        scannerSDK = intent?.getSerializableExtra(ARG_SCANNING_SDK) as? ScannerSDK ?: ScannerSDK.MLKIT

        resolvePhysicalIds()
        setupCamera()
        setupClickListeners()
        setupOrientationToggle()
        setupModeButtons()
        restoreCameraState()
    }

    private fun logAllCameraInfo() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            Log.d(CAMERA_DIAGNOSTIC_TAG, "========================================")
            Log.d(CAMERA_DIAGNOSTIC_TAG, "        AVAILABLE CAMERA REPORT         ")
            Log.d(CAMERA_DIAGNOSTIC_TAG, "========================================")

            manager.cameraIdList.forEach { cameraId ->
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_BACK -> "BACK"
                    CameraMetadata.LENS_FACING_FRONT -> "FRONT"
                    CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val isLogical = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

                Log.d(CAMERA_DIAGNOSTIC_TAG, " ")
                Log.d(CAMERA_DIAGNOSTIC_TAG, "---- Camera ID: $cameraId ----")
                Log.d(CAMERA_DIAGNOSTIC_TAG, "Facing: $facing")
                Log.d(CAMERA_DIAGNOSTIC_TAG, "Focal Lengths: ${focalLengths?.joinToString(", ")}")
                Log.d(CAMERA_DIAGNOSTIC_TAG, "Is Logical Multi-Camera: $isLogical")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isLogical) {
                    val physicalIds = characteristics.physicalCameraIds
                    Log.d(CAMERA_DIAGNOSTIC_TAG, "Physical Sub-Cameras: ${physicalIds.joinToString(", ")}")
                }
            }
            Log.d(CAMERA_DIAGNOSTIC_TAG, "========================================")
        } catch (e: Exception) {
            Log.e(CAMERA_DIAGNOSTIC_TAG, "Could not access camera characteristics.", e)
        }
    }

    private fun resolvePhysicalIds() {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val mainLogicalCameraId = mgr.cameraIdList.firstOrNull { id ->
                val chars = mgr.getCameraCharacteristics(id)
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK &&
                        capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            }

            if (mainLogicalCameraId == null) {
                Log.e(TAG, "Could not find a logical back-facing camera to query for physical IDs.")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val physicalCameraDetails = mutableListOf<Pair<String, Float>>()
                val physicalIds = mgr.getCameraCharacteristics(mainLogicalCameraId).physicalCameraIds

                for (id in physicalIds) {
                    val chars = mgr.getCameraCharacteristics(id)
                    val focalLength = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    if (focalLength != null) {
                        physicalCameraDetails.add(Pair(id, focalLength))
                    }
                }

                physicalCameraDetails.sortBy { it.second }
                Log.i(TAG, "Detected physical lenses sorted by focal length: $physicalCameraDetails")

                if (physicalCameraDetails.size >= 3) {
                    teleCameraId = physicalCameraDetails[2].first
                    val teleFocalLength = physicalCameraDetails[2].second
                    Log.i(TAG, "SUCCESS: Identified 3x Telephoto lens. ID: $teleCameraId, Focal Length: $teleFocalLength")
                } else {
                    Log.w(TAG, "Could not find enough physical cameras (need at least 3) to identify the 3x telephoto lens. Found: ${physicalCameraDetails.size}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve physical camera IDs", e)
        }
    }


    private fun setupCamera() {
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                cameraProvider = future.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val (rotation, ratioString, streamAspectRatio) = when (currentMode) {
            CameraMode.WIDE -> Triple(Surface.ROTATION_90, "9:16", AspectRatio.RATIO_16_9)
            else -> Triple(manualRotation, "1:1", AspectRatio.RATIO_4_3)
        }

        val layoutParams = binding.cameraPreview.layoutParams as ConstraintLayout.LayoutParams
        layoutParams.dimensionRatio = ratioString
        binding.cameraPreview.layoutParams = layoutParams

        binding.cameraPreview.post {
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            val previewBuilder = Preview.Builder()
                .setTargetAspectRatio(streamAspectRatio)
                .setTargetRotation(rotation)

            val imageCaptureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(streamAspectRatio)
                .setTargetRotation(rotation)

            val previewExtender = Camera2Interop.Extender(previewBuilder)
            val imageCaptureExtender = Camera2Interop.Extender(imageCaptureBuilder)

            previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, LOCKED_WHITE_BALANCE)
            imageCaptureExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, LOCKED_WHITE_BALANCE)
            Log.d(TAG, "Applied global White Balance lock: $LOCKED_WHITE_BALANCE")

            imageCaptureExtender.setCaptureRequestOption(CaptureRequest.JPEG_QUALITY, 92.toByte())
            Log.d(TAG, "Set JPEG quality to 92 for ImageCapture.")

            // Reduce post-processing latency by using faster algorithms for noise and edges.
            imageCaptureExtender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
            imageCaptureExtender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
            Log.d(TAG, "Set Noise Reduction and Edge Mode to FAST for ImageCapture to reduce latency.")

            if (currentMode == CameraMode.MACRO && teleCameraId != null) {
                previewExtender.setPhysicalCameraId(teleCameraId!!)
                imageCaptureExtender.setPhysicalCameraId(teleCameraId!!)
                applyMacroFocusSettings(previewExtender, imageCaptureExtender)
            }

            val preview = previewBuilder.build()
            imageCapture = imageCaptureBuilder.build()
            preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)

            val useCaseGroupBuilder = UseCaseGroup.Builder()

            binding.cameraPreview.viewPort?.let {
                useCaseGroupBuilder.setViewPort(it)
            }

            useCaseGroupBuilder.addUseCase(preview)
            useCaseGroupBuilder.addUseCase(imageCapture!!)

            if (currentMode != CameraMode.MACRO) {
                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(streamAspectRatio)
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor, createBarcodeAnalyzer())
                useCaseGroupBuilder.addUseCase(analysis)
            }

            try {
                camera = provider.bindToLifecycle(this, cameraSelector, useCaseGroupBuilder.build())

                val zoomRatio = when (currentMode) {
                    CameraMode.WIDE -> ZOOM_RATIO_WIDE
                    CameraMode.MACRO -> ZOOM_RATIO_MACRO
                    else -> ZOOM_RATIO_STANDARD
                }
                camera?.cameraControl?.setZoomRatio(zoomRatio)

                if (currentMode != CameraMode.MACRO) {
                    startFocusAndMetering()
                    binding.focusBox.visibility = View.GONE
                } else {
                    binding.focusBox.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                Toast.makeText(this, "Camera setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startFocusAndMetering() {
        val cameraControl = camera?.cameraControl ?: return
        binding.cameraPreview.post {
            val meteringPointFactory = binding.cameraPreview.meteringPointFactory
            val centerPoint = meteringPointFactory.createPoint(
                binding.cameraPreview.width / 2f,
                binding.cameraPreview.height / 2f
            )
            val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            cameraControl.startFocusAndMetering(action)
            Log.d(TAG, "Continuous auto-focus and metering started.")
        }
    }

    private fun createBarcodeAnalyzer(): ImageAnalysis.Analyzer {
        val listener = object : ScanningResultListener {
            override fun onScanned(result: String) {
                if (isProcessingScan || !result.startsWith(QR_PREFIX) || result == lastScannedValue) return

                isProcessingScan = true
                lastScannedValue = result
                photoHistory.clear()

                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)

                lifecycleScope.launch {
                    FileMakerApiHelper.runPrintScript(this@BarcodeScanningActivity, result)
                }

                runOnUiThread {
                    updateUiState()
                    val previewBitmap = binding.cameraPreview.bitmap
                    if (previewBitmap != null) {
                        Log.d(TAG, "Preview bitmap captured successfully. Saving...")
                        saveBitmapAsPhoto(previewBitmap, result)
                    } else {
                        Toast.makeText(this@BarcodeScanningActivity, "Could not capture from preview.", Toast.LENGTH_SHORT).show()
                        Log.e(TAG, "cameraPreview.bitmap was null, cannot save frame.")
                        isProcessingScan = false
                    }
                }
            }
        }
        return if (scannerSDK == ScannerSDK.ZXING) ZXingBarcodeAnalyzer(listener) else MLKitBarcodeAnalyzer(listener)
    }

    private fun setupModeButtons() = with(binding) {
        val highlighter: (CameraMode) -> Unit = { mode ->
            btnModeStandard.alpha = if (mode == CameraMode.STANDARD) 1f else .4f
            btnModeMacro.alpha = if (mode == CameraMode.MACRO) 1f else .4f
            btnModeWide.alpha = if (mode == CameraMode.WIDE) 1f else .4f
        }

        btnModeStandard.setOnClickListener {
            currentMode = CameraMode.STANDARD
            updateUiForModeChange()
            highlighter(currentMode)
            bindCameraUseCases()
        }
        btnModeMacro.setOnClickListener {
            if (teleCameraId == null) {
                Toast.makeText(this@BarcodeScanningActivity, "Telephoto camera not available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentMode = CameraMode.MACRO
            updateUiForModeChange()
            highlighter(currentMode)
            bindCameraUseCases()
        }
        btnModeWide.setOnClickListener {
            currentMode = CameraMode.WIDE
            updateUiForModeChange()
            highlighter(currentMode)
            bindCameraUseCases()
        }
        highlighter(currentMode)
        updateUiForModeChange()
    }

    private fun updateUiForModeChange() {
        val isOrientationToggleEnabled = currentMode != CameraMode.WIDE
        binding.btnOrientationToggle.isEnabled = isOrientationToggleEnabled
        binding.btnOrientationToggle.alpha = if (isOrientationToggleEnabled) 1.0f else 0.5f
    }

    private fun setupClickListeners() {
        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnDeleteLast.setOnClickListener { deleteLastPhoto() }
        binding.btnThumbnail.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    private fun setupOrientationToggle() {
        binding.btnOrientationToggle.setOnClickListener {
            if (currentMode == CameraMode.WIDE) {
                Toast.makeText(this, "Orientation cannot be changed in Wide mode", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isPortraitMode = !isPortraitMode
            binding.btnOrientationToggle.rotation = if (isPortraitMode) 0f else 90f
            manualRotation = if (isPortraitMode) Surface.ROTATION_0 else Surface.ROTATION_90
            bindCameraUseCases()
        }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val cam = camera ?: return
        val qr = lastScannedValue ?: return

        binding.processingIndicator.visibility = View.VISIBLE
        binding.btnShutter.isEnabled = false

        // Lock Auto-Exposure right before capture to bypass 3A delay
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            .build()
        camera2Control.setCaptureRequestOptions(options)
        Log.d(TAG, "Auto-Exposure locked for capture.")


        val suffix = photoHistory.size + captureInProgress + 1
        val fileName = "$qr-$suffix"

        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$CUSTOM_PHOTO_ALBUM")
        }

        captureInProgress++

        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                    captureInProgress--
                    out.savedUri?.let {
                        photoHistory.add(it)
                        loadThumbnail(it)
                    }
                    onCaptureFinished()
                    if (photoHistory.size == 1) {
                        isProcessingScan = false
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    captureInProgress--
                    Log.e(TAG, "Photo capture error", exc)
                    Toast.makeText(baseContext, "Photo capture failed.", Toast.LENGTH_SHORT).show()
                    onCaptureFinished()
                    isProcessingScan = false
                }
            }
        )
    }

    private fun saveBitmapAsPhoto(bitmap: Bitmap, qrCode: String) {
        binding.processingIndicator.visibility = View.VISIBLE
        binding.btnShutter.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val suffix = photoHistory.size + 1
            val fileName = "$qrCode-$suffix"

            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$CUSTOM_PHOTO_ALBUM")
                }
            }

            var savedUri: Uri? = null
            var success = false
            try {
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                            Log.e(TAG, "Failed to save bitmap.")
                        } else {
                            savedUri = uri
                            success = true
                        }
                    }
                } else {
                    Log.e(TAG, "MediaStore insert returned null URI.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save bitmap to MediaStore", e)
            }

            withContext(Dispatchers.Main) {
                if (success && savedUri != null) {
                    photoHistory.add(savedUri!!)
                    loadThumbnail(savedUri!!)
                } else {
                    Toast.makeText(baseContext, "Preview capture failed.", Toast.LENGTH_SHORT).show()
                }
                onCaptureFinished()
                isProcessingScan = false
            }
        }
    }

    private fun onCaptureFinished() {
        binding.processingIndicator.visibility = View.GONE
        updateUiState()

        // Unlock Auto-Exposure to allow the preview to resume metering.
        camera?.let {
            val camera2Control = Camera2CameraControl.from(it.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                .build()
            camera2Control.setCaptureRequestOptions(options)
            Log.d(TAG, "Auto-Exposure lock released.")
        }
    }

    private fun restoreCameraState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            } else {
                "${MediaStore.Images.Media.DATA} LIKE ?"
            }
            val selectionArgs = arrayOf("%$CUSTOM_PHOTO_ALBUM%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val allImagesInAlbum = mutableListOf<Pair<Uri, String>>()

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val uri =
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    allImagesInAlbum.add(Pair(uri, name))
                }
            }

            if (allImagesInAlbum.isNotEmpty()) {
                val mostRecentImageName = allImagesInAlbum.first().second
                val currentInvoicePrefix = mostRecentImageName.substringBeforeLast('-')

                val imagesForThisInvoice = allImagesInAlbum
                    .filter { it.second.startsWith(currentInvoicePrefix) }
                    .sortedBy { it.second }
                    .map { it.first }

                withContext(Dispatchers.Main) {
                    lastScannedValue = currentInvoicePrefix
                    photoHistory.clear()
                    photoHistory.addAll(imagesForThisInvoice)
                    updateUiState()
                    if (photoHistory.isNotEmpty()) loadThumbnail(photoHistory.last())
                }
            } else {
                withContext(Dispatchers.Main) {
                    updateUiState()
                }
            }
        }
    }

    private fun loadThumbnail(uri: Uri) {
        try {
            val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(120, 120), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    contentResolver,
                    uri.lastPathSegment!!.toLong(),
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
                )
            }
            binding.btnThumbnail.setImageBitmap(bmp)
        } catch (ex: Exception) {
            Log.e(TAG, "Thumbnail load failed", ex)
            binding.btnThumbnail.setImageBitmap(null)
        }
    }

    private fun deleteLastPhoto() {
        if (photoHistory.size <= 1) return
        val uri = photoHistory.removeLast()
        try {
            contentResolver.delete(uri, null, null)
            if (photoHistory.isNotEmpty()) {
                loadThumbnail(photoHistory.last())
            } else {
                binding.btnThumbnail.setImageBitmap(null)
            }
        } catch (ex: SecurityException) {
            photoHistory.add(uri)
            Toast.makeText(this, "Delete failed.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Delete failed", ex)
        }
        updateUiState()
    }

    private fun updateUiState() {
        val textToShow = lastScannedValue ?: "Scan a valid QR Code"
        binding.btnOrientationToggle.text = textToShow

        val hasQr = lastScannedValue != null
        binding.btnShutter.isEnabled = hasQr
        binding.btnShutter.alpha = if (hasQr) 1f else 0.4f
        binding.tvSuffixCounter.text = photoHistory.size.toString()
        binding.tvSuffixCounter.visibility = if (hasQr) View.VISIBLE else View.GONE
        val canDelete = photoHistory.size > 1
        binding.btnDeleteLast.isEnabled = canDelete
        binding.btnDeleteLast.visibility = if (canDelete) View.VISIBLE else View.INVISIBLE
        val hasAnyPhoto = photoHistory.isNotEmpty()
        binding.btnThumbnail.isEnabled = hasAnyPhoto
        binding.btnThumbnail.alpha = if (hasAnyPhoto) 1f else 0.4f

        if (!hasAnyPhoto) {
            binding.btnThumbnail.setImageBitmap(null)
        }
    }

    private fun applyMacroFocusSettings(
        previewExtender: Camera2Interop.Extender<*>,
        imageCaptureExtender: Camera2Interop.Extender<*>
    ) {
        previewExtender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_MACRO
        )
        imageCaptureExtender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_MACRO
        )
        Log.d(TAG, "Applied CONTROL_AF_MODE_MACRO to Preview and ImageCapture for continuous autofocus.")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        toneGen.release()
    }

    enum class ScannerSDK { MLKIT, ZXING }

}