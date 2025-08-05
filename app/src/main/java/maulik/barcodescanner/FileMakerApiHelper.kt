package maulik.barcodescanner

import android.os.Build
import android.content.Context
import android.util.Base64
import android.util.Log
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import javax.net.ssl.*

/**
 * Handles login + script call to FileMaker Data API.
 */
object FileMakerApiHelper {

    /* -------- static configuration -------- */
    private const val TAG   = "FileMakerApiHelper"
    private const val HOST  = "10.0.0.8"
    private const val DB    = "GoldV6"
    private const val LAYOUT = "List_eBayRFIDLabel"
    private const val SCRIPT = "AndroidLabelPrint"
    private const val SCRIPT_CLOUDINARY = "Cloudinary"
    private const val USER  = "API_Only"
    private const val PASS  = "3344eerrddffccVV!!"
    private val   JSON     = "application/json".toMediaType()

    /* -------- singleton OkHttp client -------- */
    private lateinit var client: OkHttpClient
    private lateinit var trustManager: X509TrustManager

    /** Call once (lazy) before first request */
    private fun init(context: Context) {
        if (::client.isInitialized) return

        /* 1. Load bundled self‑signed cert */
        val cf  = CertificateFactory.getInstance("X.509")
        val ca  = context.resources.openRawResource(R.raw.fms_cert).use(cf::generateCertificate)
        val ks  = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null); setCertificateEntry("fms", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        trustManager = tmf.trustManagers[0] as X509TrustManager

        /* 2. TLS context */
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }

        /* 3. Custom factory that REMOVES SNI */
        val rawFactory = sslContext.socketFactory
        val noSniFactory = object : SSLSocketFactory() {
            override fun getDefaultCipherSuites() = rawFactory.defaultCipherSuites
            override fun getSupportedCipherSuites() = rawFactory.supportedCipherSuites
            private fun strip(s: Socket): Socket = s.apply {
                if (this is SSLSocket) {
                    try {
                        if (Build.VERSION.SDK_INT >= 24) {
                            val p = sslParameters
                            p.serverNames = emptyList()
                            sslParameters = p
                        }
                    } catch (_: Exception) { }
                }
            }
            override fun createSocket(s: Socket?, h: String?, p: Int, a: Boolean) =
                strip(rawFactory.createSocket(s, h, p, a))
            override fun createSocket(h: String?, p: Int)               = strip(rawFactory.createSocket(h, p))
            override fun createSocket(h: String?, p: Int, l: InetAddress?, lp: Int) =
                strip(rawFactory.createSocket(h, p, l, lp))
            override fun createSocket(addr: InetAddress?, p: Int)       = strip(rawFactory.createSocket(addr, p))
            override fun createSocket(addr: InetAddress?, p: Int, l: InetAddress?, lp: Int) =
                strip(rawFactory.createSocket(addr, p, l, lp))
        }

        /* 4. Logging and header interceptors */
        val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }

        /* 5. Build client */
        client = OkHttpClient.Builder()
            .sslSocketFactory(noSniFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(logger)
            .build()
    }

    /** Public entry: login (if needed) then fire the label‑print script */
    // START OF FIX: Modified function to accept batchId and send JSON
    suspend fun runPrintScript(ctx: Context, qrParam: String, batchId: String) {
        init(ctx)  // ensure client built

        withContext(Dispatchers.IO) {
            try {
                val token = getFmsToken()

                // Create a JSON object for the script parameter
                val scriptParamJson = JSONObject()
                scriptParamJson.put("inventoryId", qrParam)
                scriptParamJson.put("batchId", batchId)
                val jsonParamString = scriptParamJson.toString()

                /* ------------ 2. SCRIPT call (Bearer) ------------ */
                val url = HttpUrl.Builder()
                    .scheme("https").host(HOST)
                    .addPathSegments("fmi/data/v1/databases/$DB/layouts/$LAYOUT/script/$SCRIPT")
                    // Use the new JSON string as the parameter
                    .addQueryParameter("script.param", jsonParamString)
                    .build()

                val scriptReq = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(scriptReq).execute().use { rsp ->
                    if (rsp.code !in 200..299)
                        throw Exception("Script HTTP ${rsp.code} : ${rsp.body?.string()}")
                }

                withContext(Dispatchers.Main) {
                    // Updated toast message for clarity
                    Toast.makeText(ctx, "Print script for $qrParam (Batch: $batchId) triggered", Toast.LENGTH_LONG).show()
                }

            } catch (ex: Exception) {
                Log.e(TAG, "Data API failure", ex)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "FileMaker API error: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    // END OF FIX

    /** Public entry: login (if needed) then fire the Cloudinary log script */
    suspend fun runCloudinaryUploadLogScript(ctx: Context, jsonParam: String) {
        init(ctx)

        withContext(Dispatchers.IO) {
            try {
                val token = getFmsToken()
                val url = HttpUrl.Builder()
                    .scheme("https").host(HOST)
                    .addPathSegments("fmi/data/v1/databases/$DB/layouts/$LAYOUT/script/$SCRIPT_CLOUDINARY")
                    .addQueryParameter("script.param", jsonParam)
                    .build()

                val scriptReq = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(scriptReq).execute().use { rsp ->
                    if (rsp.code !in 200..299)
                        throw Exception("Cloudinary Log Script HTTP ${rsp.code} : ${rsp.body?.string()}")
                    Log.i(TAG, "Cloudinary log script triggered successfully.")
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Cloudinary log sent to FileMaker.", Toast.LENGTH_LONG).show()
                }

            } catch (ex: Exception) {
                Log.e(TAG, "Data API failure", ex)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "FileMaker API error for Cloudinary log: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Authenticates with FMS and returns a session token. */
    @Throws(Exception::class)
    private fun getFmsToken(): String {
        val basic = "Basic " + Base64.encodeToString("$USER:$PASS".toByteArray(), Base64.NO_WRAP)
        val loginReq = Request.Builder()
            .url("https://$HOST/fmi/data/v1/databases/$DB/sessions")
            .method("POST", "{}".toRequestBody(null))
            .header("Authorization", basic)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        return client.newCall(loginReq).execute().use { rsp ->
            if (rsp.code != 200 && rsp.code != 201)
                throw Exception("Login HTTP ${rsp.code} : ${rsp.body?.string()}")
            val body = rsp.body?.string() ?: throw Exception("Empty login body")
            JSONObject(body).getJSONObject("response").getString("token")
        }
    }
}