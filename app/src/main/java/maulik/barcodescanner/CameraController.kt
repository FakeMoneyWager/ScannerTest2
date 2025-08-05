package maulik.barcodescanner.ui

import maulik.barcodescanner.R
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect // Added for crop calculation
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics // Added for sensor characteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maulik.barcodescanner.database.AppDatabase
import maulik.barcodescanner.database.Photo
import maulik.barcodescanner.databinding.ActivityBarcodeScanningBinding
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/* ─────────────────────────  constants  ───────────────────────── */

private const val ZOOM_RATIO_STANDARD = 1.0f
// NEW: This factor controls the sensor crop for macro mode. 2.0f = 2x magnification.
private const val MACRO_CROP_FACTOR = 1.25f
private const val ZOOM_RATIO_WIDE = 0.6f

private const val LOCKED_WHITE_BALANCE = android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
private const val CUSTOM_PHOTO_ALBUM = "MyBusinessApp"

enum class CameraMode { STANDARD, MACRO, WIDE }

/* ─────────────────────────  class  ───────────────────────── */

@ExperimentalCamera2Interop
class CameraController(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val binding: ActivityBarcodeScanningBinding,
    private val scanCtrl: ScanController,
    private val scannerSDK: BarcodeScanningActivity.ScannerSDK
) {

    /* -------- state -------- */

    private val TAG = "CameraController"
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var mediaActionSound: MediaActionSound? = null

    /* camera behaviour */
    private var currentMode: CameraMode = CameraMode.STANDARD
    private var isPortraitMode = true
    private var manualRotation = Surface.ROTATION_0
    private var ultrawideCameraId: String? = null
    // NEW: Stores the full dimensions of the ultrawide sensor to calculate the crop.
    private var ultrawideActiveArraySize: Rect? = null
    private var captureInProgress = 0



    /* ─────────────────────────  lifecycle  ───────────────────────── */

    fun startCamera(initialMode: CameraMode = CameraMode.STANDARD) {
        currentMode = initialMode

        if (mediaActionSound == null) {
            mediaActionSound = MediaActionSound()
            mediaActionSound?.load(MediaActionSound.SHUTTER_CLICK)
        }

        resolvePhysicalIds()
        ProcessCameraProvider.getInstance(ctx).also { future ->
            future.addListener(
                {
                    cameraProvider = future.get()
                    bindUseCases()
                },
                ContextCompat.getMainExecutor(ctx)
            )
        }
    }

    fun takePhotoForCurrentItem() {
        if (scanCtrl.lastScannedValue == null || captureInProgress > 0) return
        mediaActionSound?.play(MediaActionSound.SHUTTER_CLICK)
        takePhotoInternal()
    }

    fun release() {
        cameraExecutor.shutdown()
        mediaActionSound?.release()
    }

    fun toggleOrientation() {
        if (currentMode == CameraMode.WIDE) return
        isPortraitMode = !isPortraitMode
        manualRotation = if (isPortraitMode) Surface.ROTATION_0 else Surface.ROTATION_90
        val newRotation = if (isPortraitMode) 0f else 90f
        binding.btnOrientationToggle.animate().rotation(newRotation).setDuration(300).start()
        binding.tvInvSuffix.animate().rotation(newRotation).setDuration(300).start()
        bindUseCases()
    }

    /* ─────────────────────────  use‑case binding  ───────────────────────── */

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        /* --- preview view constraints ------------------------------------ */
        val lp = binding.cameraPreview.layoutParams as ConstraintLayout.LayoutParams
        val streamRatio: Int
        val rotation: Int

        when (currentMode) {
            CameraMode.WIDE -> {
                streamRatio = AspectRatio.RATIO_16_9
                rotation = Surface.ROTATION_90
                lp.width = 0
                lp.height = 0
                lp.dimensionRatio = null
                lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                binding.cameraPreview.scaleType = PreviewView.ScaleType.FIT_CENTER
                lp.topMargin = 0
                binding.btnOrientationToggle.animate().rotation(90f).setDuration(300).start()
                binding.tvInvSuffix.animate().rotation(90f).setDuration(300).start()
                binding.btnOrientationToggle.isEnabled = false
                binding.btnOrientationToggle.alpha = 0.4f
            }
            else -> {
                lp.dimensionRatio = "1:1"
                streamRatio = AspectRatio.RATIO_4_3
                rotation = manualRotation
                binding.cameraPreview.scaleType = PreviewView.ScaleType.FILL_CENTER
                lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                lp.topToTop = ConstraintLayout.LayoutParams.UNSET
                lp.topToBottom = R.id.orientationControlContainer
                val topMarginInDp = 16
                lp.topMargin = (topMarginInDp * ctx.resources.displayMetrics.density).toInt()
                val newRotation = if (isPortraitMode) 0f else 90f
                binding.btnOrientationToggle.animate().rotation(newRotation).setDuration(300).start()
                binding.tvInvSuffix.animate().rotation(newRotation).setDuration(300).start()
                binding.btnOrientationToggle.isEnabled = true
                binding.btnOrientationToggle.alpha = 1.0f
            }
        }
        binding.cameraPreview.layoutParams = lp

        /* --- create use‑cases -------------------------------------------- */
        binding.cameraPreview.post {
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            val previewBuilder = Preview.Builder()
                .setTargetAspectRatio(streamRatio)
                .setTargetRotation(rotation)

            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(streamRatio)
                .setTargetRotation(rotation)

            val previewExt = Camera2Interop.Extender(previewBuilder)
            val captureExt = Camera2Interop.Extender(captureBuilder)

            val sessionCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    owner.lifecycleScope.launch(Dispatchers.Main) {
                        if (currentMode == CameraMode.WIDE) {
                            binding.focusBox.visibility = View.GONE
                            scanCtrl.updateUiState()
                        } else {
                            binding.focusBox.visibility = View.VISIBLE
                            when (afState) {
                                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> {
                                    binding.focusBox.setBackgroundResource(R.drawable.focus_ring_focused)
                                    scanCtrl.updateUiState()
                                }
                                CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
                                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                                    binding.focusBox.setBackgroundResource(R.drawable.focus_ring_error)
                                    binding.tvStatusDisplay.text = "Too Close For Focus"
                                    binding.focusBox.postDelayed({ startAfMetering() }, 500)
                                }
                                else -> { // Searching states
                                    binding.focusBox.setBackgroundResource(R.drawable.focus_ring_searching)
                                    scanCtrl.updateUiState()
                                }
                            }
                        }
                    }
                }
            }
            previewExt.setSessionCaptureCallback(sessionCaptureCallback)

            previewExt.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, LOCKED_WHITE_BALANCE)
            captureExt.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, LOCKED_WHITE_BALANCE)
            captureExt.setCaptureRequestOption(CaptureRequest.JPEG_QUALITY, 92.toByte())

            // MODIFIED: This block now applies a sensor crop in MACRO mode instead of using zoom.
            if (currentMode == CameraMode.MACRO && ultrawideCameraId != null) {
                Log.d(TAG, "Binding MACRO mode to ultrawide lens ID: $ultrawideCameraId")
                previewExt.setPhysicalCameraId(ultrawideCameraId!!)
                captureExt.setPhysicalCameraId(ultrawideCameraId!!)

                // Apply sensor crop based on the new crop factor.
                ultrawideActiveArraySize?.let { activeArray ->
                    val cropWidth = (activeArray.width() / MACRO_CROP_FACTOR).toInt()
                    val cropHeight = (activeArray.height() / MACRO_CROP_FACTOR).toInt()
                    val left = (activeArray.width() - cropWidth) / 2
                    val top = (activeArray.height() - cropHeight) / 2
                    val cropRect = Rect(left, top, left + cropWidth, top + cropHeight)

                    Log.d(TAG, "Applying custom crop region for MACRO: $cropRect from active array: $activeArray")
                    previewExt.setCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, cropRect)
                    captureExt.setCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, cropRect)
                }
            }

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            imageCapture = captureBuilder.build()

            val group = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture!!)
                .apply {
                    if (currentMode != CameraMode.MACRO) {
                        addUseCase(buildAnalyzer(streamRatio, rotation))
                    }
                    binding.cameraPreview.viewPort?.let { setViewPort(it) }
                }
                .build()

            try {
                camera = provider.bindToLifecycle(owner, selector, group)

                // MODIFIED: Zoom is now handled conditionally.
                if (currentMode == CameraMode.MACRO) {
                    // For MACRO mode, ensure zoom is 1.0f as cropping is handled by SCALER_CROP_REGION.
                    camera?.cameraControl?.setZoomRatio(1.0f)
                } else {
                    // For other modes, use the existing zoom constants.
                    val zoom = when (currentMode) {
                        CameraMode.WIDE -> ZOOM_RATIO_WIDE
                        else -> ZOOM_RATIO_STANDARD
                    }
                    camera?.cameraControl?.setZoomRatio(zoom)
                }
                startAfMetering()
            } catch (e: Exception) {
                Log.e(TAG, "Bind failed", e)
            }
        }
    }

    /**
     * THIS IS THE NEW, SIMPLER FUNCTION.
     * Updates the alpha of the mode buttons to highlight the active mode.
     */
    private fun updateModeButtonStates() {
        // Set the alpha for each button: 1.0f for active, 0.4f for inactive.
        binding.btnModeStandard.alpha = if (currentMode == CameraMode.STANDARD) 1.0f else 0.4f
        binding.btnModeMacro.alpha    = if (currentMode == CameraMode.MACRO) 1.0f else 0.4f
        binding.btnModeWide.alpha     = if (currentMode == CameraMode.WIDE) 1.0f else 0.4f
    }

    private fun buildAnalyzer(ratio: Int, rotation: Int): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(ratio)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor, scanCtrl.createAnalyzer(scannerSDK))
        return analysis
    }

    /* ─────────────────────────  focus helpers  ───────────────────────── */

    private fun startAfMetering() {
        val ctrl = camera?.cameraControl ?: return
        binding.cameraPreview.post {
            val factory = binding.cameraPreview.meteringPointFactory
            val p = factory.createPoint(
                binding.cameraPreview.width / 2f,
                binding.cameraPreview.height / 2f
            )
            val action = FocusMeteringAction.Builder(p, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(5, TimeUnit.SECONDS)
                .build()
            ctrl.startFocusAndMetering(action)
        }
    }

    /* ─────────────────────────  photo capture  ───────────────────────── */

    private fun takePhotoInternal() {
        val ic = imageCapture ?: return
        val qr = scanCtrl.lastScannedValue ?: return

        binding.processingIndicator.visibility = View.VISIBLE
        binding.btnShutter.isEnabled = false

        val cleanQr = qr.removeSuffix("-")
        val suffix = scanCtrl.photoHistory.size + 1
        val name = "$cleanQr-$suffix"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$CUSTOM_PHOTO_ALBUM")
        }

        captureInProgress++
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(
                ctx.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                    captureInProgress--
                    res.savedUri?.let { uri ->
                        owner.lifecycleScope.launch {
                            if (currentMode != CameraMode.WIDE) cropCenterSquare(uri)

                            val tmpBmp = ctx.contentResolver.openInputStream(uri)?.use { istr ->
                                BitmapFactory.decodeStream(istr)
                            }
                            val isSharp = tmpBmp?.let { isBitmapSharp(it) } ?: true
                            tmpBmp?.recycle()

                            if (!isSharp) {
                                withContext(Dispatchers.IO) {
                                    ctx.contentResolver.delete(uri, null, null)
                                }
                                tripleBeep()
                                binding.focusBox.setBackgroundResource(R.drawable.focus_ring_error)
                                binding.focusBox.postDelayed({
                                    binding.focusBox.setBackgroundResource(R.drawable.focus_ring_focused)
                                }, 500)
                                finishCapture()
                                return@launch
                            }

                            withContext(Dispatchers.IO) {
                                AppDatabase.getInstance(ctx)
                                    .photoDao()
                                    .insert(
                                        Photo(
                                            inventoryId = scanCtrl.lastScannedValue ?: return@withContext,
                                            uri         = uri.toString(),
                                            takenAt     = System.currentTimeMillis()
                                        )
                                    )
                            }
                        }
                    }
                    finishCapture()
                }

                override fun onError(e: ImageCaptureException) {
                    captureInProgress--
                    Log.e(TAG, "Capture error", e)
                    finishCapture()
                }
            }
        )
    }

    private fun finishCapture() {
        binding.processingIndicator.visibility = View.GONE
        scanCtrl.updateUiState()
        val camCtrl = camera?.let { Camera2CameraControl.from(it.cameraControl) } ?: return
        camCtrl.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                .build()
        )
    }

    /* ─────────────────────────  physical lens helpers  ───────────────────────── */

    private fun resolvePhysicalIds() {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val mainLogical = mgr.cameraIdList.firstOrNull { id ->
                val c = mgr.getCameraCharacteristics(id)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                        caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val list = mutableListOf<Pair<String, Float>>()
                val phys = mgr.getCameraCharacteristics(mainLogical).physicalCameraIds
                for (id in phys) {
                    val focal = mgr.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.firstOrNull() ?: continue
                    list.add(id to focal)
                }
                list.sortBy { it.second }
                ultrawideCameraId = list.firstOrNull()?.first

                // NEW: Get and store the active array size for the identified ultrawide lens.
                ultrawideCameraId?.let { id ->
                    val characteristics = mgr.getCameraCharacteristics(id)
                    ultrawideActiveArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    Log.i(TAG, "Resolved ultrawide lens for macro mode: $id with active array size: $ultrawideActiveArraySize")
                } ?: Log.w(TAG, "Could not resolve ultrawide camera ID.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolvePhysicalIds failed", e)
        }
    }

    /* ─────────────────────────  crop helper  ───────────────────────── */

    private suspend fun cropCenterSquare(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val bytes: ByteArray =
                ctx.contentResolver.openInputStream(uri)?.use(InputStream::readBytes)
                    ?: return@withContext
            val exif = ExifInterface(bytes.inputStream())
            val rot = when (
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext
            if (rot != 0) {
                val originalBmp = bmp
                bmp = Bitmap.createBitmap(
                    originalBmp, 0, 0, originalBmp.width, originalBmp.height,
                    Matrix().apply { postRotate(rot.toFloat()) },
                    true
                )
                originalBmp.recycle()
            }

            val side = minOf(bmp.width, bmp.height)
            val x = (bmp.width - side) / 2
            val y = (bmp.height - side) / 2
            var sq = Bitmap.createBitmap(bmp, x, y, side, side)

            if (sq.width > 3000) {
                val originalSq = sq
                sq = Bitmap.createScaledBitmap(originalSq, 3000, 3000, true)
                originalSq.recycle()
            }

            ctx.contentResolver.openOutputStream(uri, "w")?.use {
                sq.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }

            ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val finalExif = ExifInterface(pfd.fileDescriptor)
                finalExif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
                finalExif.saveAttributes()
            }
            bmp.recycle()
            sq.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "cropCenterSquare failed", e)
        }
    }


    /* ─────────────────────────  Image Analysis & Feedback  ───────────────────────── */

    companion object {
        /**
         * Defines the percentage of the image's shortest side to use for blur detection.
         * Set to 20% as requested.
         */
        private const val ANALYSIS_AREA_PERCENTAGE = 0.17f

        /**
         * The MAXIMUM dimension (width or height) for the analysis bitmap.
         * Set to 768 as requested for a good balance of accuracy and performance.
         */
        private const val MAX_ANALYSIS_DIMENSION = 768
    }

    /**
     * Checks if a bitmap is sharp by analyzing the variance of edge strengths in a
     * dynamically sized, but resolution-capped, center crop of the image.
     * The threshold is kept at 250.0 as requested.
     */
    private fun isBitmapSharp(src: Bitmap, threshold: Double = 220.0): Boolean {
        // 1. Calculate the initial crop size based on the 20% area requirement.
        val percentBasedSideLength = (minOf(src.width, src.height) * ANALYSIS_AREA_PERCENTAGE).toInt()

        // 2. Cap the crop size to the 768px maximum dimension to ensure good performance.
        val finalCropSideLength = minOf(percentBasedSideLength, MAX_ANALYSIS_DIMENSION)

        if (finalCropSideLength < 32) {
            Log.w(TAG, "isBitmapSharp: Analysis area is too small, skipping check.")
            return true
        }

        // 3. Crop the center of the bitmap to analyze at full resolution.
        val x = (src.width - finalCropSideLength) / 2
        val y = (src.height - finalCropSideLength) / 2
        val crop = Bitmap.createBitmap(src, x, y, finalCropSideLength, finalCropSideLength)

        val w = crop.width
        val h = crop.height
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        crop.recycle()

        // 4. Convert the crop to grayscale for edge detection.
        val gray = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        // 5. Apply the Sobel operator to find edge gradients.
        var sum = 0.0
        var sqSum = 0.0
        val n = ((w - 2) * (h - 2)).toDouble()

        for (i in 1 until h - 1) {
            for (j in 1 until w - 1) {
                val gx = (-1 * gray[(i - 1) * w + (j - 1)]) + (0 * gray[(i - 1) * w + j]) + (1 * gray[(i - 1) * w + (j + 1)]) +
                        (-2 * gray[i * w + (j - 1)])       + (0 * gray[i * w + j])       + (2 * gray[i * w + (j + 1)]) +
                        (-1 * gray[(i + 1) * w + (j - 1)]) + (0 * gray[i * w + j])       + (1 * gray[i * w + (j + 1)])

                val gy = (-1 * gray[(i - 1) * w + (j - 1)]) + (-2 * gray[(i - 1) * w + j]) + (-1 * gray[(i - 1) * w + (j + 1)]) +
                        (0 * gray[i * w + (j - 1)])        + (0 * gray[i * w + j])        + (0 * gray[i * w + (j + 1)]) +
                        (1 * gray[(i + 1) * w + (j - 1)])  + (2 * gray[i * w + j])  + (1 * gray[i * w + (j + 1)])

                val g = kotlin.math.sqrt((gx * gx + gy * gy).toDouble())
                sum += g
                sqSum += g * g
            }
        }

        // 6. Calculate the variance of the gradients.
        val variance = if (n > 0) (sqSum - (sum * sum) / n) / n else 0.0

        Log.d(TAG, "isBitmapSharp: Analysis Area: ${finalCropSideLength}x${finalCropSideLength}. Sharpness Variance: $variance (Threshold: $threshold)")
        return variance > threshold
    }

    /**
     * Plays a distinct, three-beep error sound.
     * This is a 'suspend' function to avoid blocking the main UI thread.
     */
    private suspend fun tripleBeep() {
        val volume = 100 // ToneGenerator.MAX_VOLUME
        val toneType = android.media.ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE
        val pauseBetweenTonesMs = 250L
        val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, volume)
        try {
            repeat(3) {
                tg.startTone(toneType, -1)
                kotlinx.coroutines.delay(pauseBetweenTonesMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "tripleBeep failed", e)
        } finally {
            tg.release()
        }
    }
}