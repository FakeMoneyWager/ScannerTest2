package maulik.barcodescanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maulik.barcodescanner.BatchNumberManager
import maulik.barcodescanner.database.AppDatabase
import maulik.barcodescanner.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private val cameraPermissionRequestCode = 1
    private var selectedScanningSDK = BarcodeScanningActivity.ScannerSDK.MLKIT
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        // Refresh the database stats every time the user returns to this screen
        updateDashboardStats()
    }

    private fun setupClickListeners() = with(binding) {
        cardMlKit.setOnClickListener {
            selectedScanningSDK = BarcodeScanningActivity.ScannerSDK.MLKIT
            startScanning()
        }
        cardViewPhotos.setOnClickListener {
            startActivity(Intent(this@MainActivity, GalleryActivity::class.java))
        }
    }

    /**
     * Fetches counts for the current batch and the lifetime total, then updates the UI.
     */
    private fun updateDashboardStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(applicationContext)
            val pendingStatus = "PENDING_UPLOAD"

            // --- Background Thread Work ---
            // Fetch all counts from the database and the batch ID from SharedPreferences
            val pendingRecordCount = db.scanLogDao().getLogCountByStatus(pendingStatus)
            val pendingImageCount = db.photoDao().getPhotoCountByStatus(pendingStatus)
            val totalRecordCount = db.scanLogDao().getLogCount()
            val currentBatchId = BatchNumberManager.getCurrentBatchId(this@MainActivity)

            // --- Switch to Main Thread to Update All UI ---
            withContext(Dispatchers.Main) {
                // Build the multi-line string for the Current Batch
                binding.tvBatchStats.text =
                    "Current Batch Totals:\nItem Count: $pendingRecordCount\nImage Count: $pendingImageCount"

                // Set the text for the new Batch ID display
                binding.tvCurrentBatchId.text = "Current Batch ID: $currentBatchId"

                // Build the string for the Lifetime Total
                binding.tvLifetimeStats.text = "All-Time Item Count: $totalRecordCount"
            }
        }
    }

    private fun startScanning() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openCameraWithScanner()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                cameraPermissionRequestCode
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraPermissionRequestCode && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCameraWithScanner()
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.CAMERA
                )
            ) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri: Uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivityForResult(intent, cameraPermissionRequestCode)
            }
        }
    }

    private fun openCameraWithScanner() {
        BarcodeScanningActivity.start(this, selectedScanningSDK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == cameraPermissionRequestCode) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                openCameraWithScanner()
            }
        }
    }
}
