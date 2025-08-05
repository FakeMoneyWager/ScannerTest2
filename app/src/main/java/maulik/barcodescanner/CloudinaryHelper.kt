package maulik.barcodescanner

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class UploadResult(
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

object CloudinaryHelper {

    private const val TAG = "CloudinaryHelper"
    private var isInitialized = false
    private const val CLOUD_NAME = "dsih0wsgt"
    private const val UPLOAD_PRESET = "app-eager-30d"

    private fun init(context: Context) {
        if (isInitialized) return
        try {
            val config = mapOf("cloud_name" to CLOUD_NAME)
            MediaManager.init(context.applicationContext, config)
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Cloudinary MediaManager", e)
        }
    }

    suspend fun uploadImage(
        context: Context,
        fileUri: Uri,
        publicId: String,
        batchTag: String // Parameter added here
    ): UploadResult {
        init(context)

        return suspendCancellableCoroutine { continuation ->
            MediaManager.get().upload(fileUri)
                .unsigned(UPLOAD_PRESET)
                .option("public_id", publicId)
                .option("tags", batchTag) // This line adds the tag to the upload
                .callback(object : UploadCallback {
                    override fun onStart(requestId: String) {}
                    override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}
                    override fun onReschedule(requestId: String, error: ErrorInfo) {}

                    override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                        // Updated log to confirm the tag was sent
                        Log.i(TAG, "Upload API call successful for public_id: $publicId with tag: $batchTag.")
                        continuation.resume(UploadResult(isSuccess = true))
                    }

                    override fun onError(requestId: String, error: ErrorInfo) {
                        Log.e(TAG, "Upload failed for public_id: $publicId. Error: ${error.description}")
                        continuation.resume(UploadResult(isSuccess = false, errorMessage = error.description))
                    }
                }).dispatch()
        }
    }
}