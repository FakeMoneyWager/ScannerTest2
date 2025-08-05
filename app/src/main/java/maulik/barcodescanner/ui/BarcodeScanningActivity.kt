package maulik.barcodescanner.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.lifecycleScope
import maulik.barcodescanner.databinding.ActivityBarcodeScanningBinding

/**
 * Pure UI / lifecycle shell that owns the two controllers.
 */
@ExperimentalCamera2Interop
class BarcodeScanningActivity : AppCompatActivity() {

    /* ───────────────────────── companion ───────────────────────── */

    companion object {
        /** Intent extra name used to tell the activity which SDK to use */
        const val ARG_SCANNING_SDK = "scanning_SDK"

        /**
         * Convenience launcher:
         *     BarcodeScanningActivity.start(context, ScannerSDK.MLKIT)
         */
        @JvmStatic
        fun start(context: Context, scannerSDK: ScannerSDK) {
            val i = Intent(context, BarcodeScanningActivity::class.java).apply {
                putExtra(ARG_SCANNING_SDK, scannerSDK)
            }
            context.startActivity(i)
        }
    }

    /* ───────────────────────── members ───────────────────────── */

    private lateinit var binding    : ActivityBarcodeScanningBinding
    private lateinit var cameraCtrl : CameraController
    private lateinit var scanCtrl   : ScanController

    private var currentMode : CameraMode = CameraMode.STANDARD
    private var scannerSDK  : ScannerSDK = ScannerSDK.MLKIT   // default

    /* ───────────────────────── lifecycle ───────────────────────── */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarcodeScanningBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /* caller’s choice of SDK (MLKit vs ZXing) */
        scannerSDK = intent.getSerializableExtra(ARG_SCANNING_SDK)
                as? ScannerSDK ?: ScannerSDK.MLKIT

        /* create controllers */
        scanCtrl   = ScanController(this, binding, lifecycleScope)
        cameraCtrl = CameraController(this, this, binding, scanCtrl, scannerSDK)

        setupButtons()
        cameraCtrl.startCamera(currentMode)
        scanCtrl.restoreCameraState()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraCtrl.release()
        scanCtrl.dispose()
    }

    /* ───────────────────────── UI glue ───────────────────────── */

    private fun setupButtons() = with(binding) {
        /* shutter / delete / gallery */
        btnShutter.setOnClickListener   { cameraCtrl.takePhotoForCurrentItem() }
        btnDeleteLast.setOnClickListener{ scanCtrl.deleteLastPhoto() }
        btnThumbnail.setOnClickListener {
            startActivity(Intent(this@BarcodeScanningActivity, GalleryActivity::class.java))
        }

        /* mode switching */
        btnModeStandard.setOnClickListener { switchMode(CameraMode.STANDARD) }
        btnModeMacro.setOnClickListener    { switchMode(CameraMode.MACRO)    }
        btnModeWide.setOnClickListener     { switchMode(CameraMode.WIDE)     }
        btnOrientationToggle.setOnClickListener { cameraCtrl.toggleOrientation() }
    }

    private fun switchMode(newMode: CameraMode) {
        if (newMode == currentMode) return
        currentMode = newMode
        cameraCtrl.startCamera(currentMode)
    }

    /* ───────────────────────── enums ───────────────────────── */

    /** duplicated here so callers don’t have to know about CameraController */
    enum class ScannerSDK { MLKIT, ZXING }
}