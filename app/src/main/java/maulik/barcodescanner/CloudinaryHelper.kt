package maulik.barcodescanner

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cloudinary.Cloudinary
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object CloudinaryHelper {

    private const val TAG = "CloudinaryHelper"
    private var isInitialized = false

    // Configuration constants from your spec
    private const val CLOUD_NAME = "mycloud"
    private const val UPLOAD_PRESET = "app-eager-30d"

    /**
     * Initializes the MediaManager. Must be called once, typically in the Application class,
     * but we'll call it lazily from the upload function for this implementation.
     */
    private fun init(context: Context) {
        if (isInitialized) return
        try {
            // Using a simple configuration with just the cloud name for unsigned uploads
            val config = mapOf("cloud_name" to CLOUD_NAME)
            MediaManager.init(context, config)
            isInitialized = true
            Log.d(TAG, "Cloudinary MediaManager initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Cloudinary MediaManager", e)
        }
    }

    /**
     * Uploads a single image to Cloudinary using an unsigned preset.
     *
     * @param context The Android context.
     * @param fileUri The content URI of the file to upload.
     * @param folder The folder name to use in Cloudinary.
     * @param publicId The public_id for the asset in Cloudinary.
     * @return A Pair containing the result status (Boolean) and a message (String).
     */
    suspend fun uploadImage(
        context: Context,
        fileUri: Uri,
        folder: String,
        publicId: String
    ): Pair<Boolean, String> {
        // Lazily initialize the SDK
        init(context)

        return suspendCancellableCoroutine { continuation ->
            val requestId = MediaManager.get().upload(fileUri)
                .unsigned(UPLOAD_PRESET)
                .option("folder", folder)
                .option("public_id", publicId)
                .callback(object : UploadCallback {
                    override fun onStart(requestId: String) {
                        Log.d(TAG, "Upload started for public_id: $publicId")
                    }

                    override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {
                        // Not used in this implementation
                    }

                    override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                        val token = resultData["delete_token"]
                        Log.i(TAG, "Upload successful for public_id: $publicId. Response: $resultData")
                        if (token != null) {
                            continuation.resume(Pair(true, "Success: $publicId"))
                        } else {
                            // This case is important per your spec to ensure deletion is possible later
                            Log.e(TAG, "Upload succeeded but delete_token was NOT returned for $publicId.")
                            continuation.resume(Pair(false, "Error: Upload for $publicId succeeded but missing delete token."))
                        }
                    }

                    override fun onError(requestId: String, error: ErrorInfo) {
                        Log.e(TAG, "Upload failed for public_id: $publicId. Error: ${error.description}")
                        continuation.resume(Pair(false, "Error: ${error.description}"))
                    }

                    override fun onReschedule(requestId: String, error: ErrorInfo) {
                        Log.w(TAG, "Upload rescheduled for public_id: $publicId. Error: ${error.description}")
                    }
                })
                .dispatch()

            continuation.invokeOnCancellation {
                MediaManager.get().cancelRequest(requestId)
            }
        }
    }
}