package maulik.barcodescanner.analyzer

import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode // <-- Make sure to import this
import com.google.mlkit.vision.common.InputImage

class MLKitBarcodeAnalyzer(private val listener: ScanningResultListener) : ImageAnalysis.Analyzer {

    private var isScanning: Boolean = false

    // 1. Configure barcode scanner options to only detect QR codes
    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    // 2. Get an instance of the scanner with the specified options
    private val scanner = BarcodeScanning.getClient(options)

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isScanning) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            isScanning = true
            // 3. Process the image with the pre-configured scanner
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    // Task completed successfully
                    // Since we specified only QR_CODE, the list will only contain QR codes.
                    barcodes.firstOrNull()?.rawValue?.let { qrContent ->
                        Log.d("Barcode", "QR Code detected: $qrContent")
                        listener.onScanned(qrContent)
                    }

                    isScanning = false
                    imageProxy.close()
                }
                .addOnFailureListener { exception ->
                    // Task failed with an exception
                    Log.e("Barcode", "Barcode scanning failed", exception)
                    isScanning = false
                    imageProxy.close()
                }
        }
    }
}