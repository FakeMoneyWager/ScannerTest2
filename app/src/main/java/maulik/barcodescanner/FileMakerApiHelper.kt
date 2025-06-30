package maulik.barcodescanner

import android.os.Build             // ← NEW
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
 *     1. POST /sessions  (Basic‑auth)  → token
 *     2. GET  /script/<scriptName>?script.param=<QR>  (Bearer token)
 *
 * The custom SSLSocketFactory strips SNI so Apache doesn’t return 400 for IP hosts.
 */
object FileMakerApiHelper {

    /* -------- static configuration -------- */
    private const val TAG   = "FileMakerApiHelper"
    private const val HOST  = "10.0.0.8"
    private const val DB    = "GoldV6"
    private const val LAYOUT = "List_eBayRFIDLabel"
    private const val SCRIPT = "AndroidLabelPrint"
    private const val USER  = "API_Only"
    private const val PASS  = "3344eerrddffccVV!!"
    private val   JSON     = "application/json".toMediaType()

    /* -------- singleton OkHttp client -------- */
    private lateinit var client: OkHttpClient
    private lateinit var trustManager: X509TrustManager

    /** Call once (lazy) before first request */
    private fun init(context: Context) {
        if (::client.isInitialized) return

        /* 1. Load bundled self‑signed cert (res/raw/fms_cert.crt) into a KeyStore */
        val cf  = CertificateFactory.getInstance("X.509")
        val ca  = context.resources.openRawResource(R.raw.fms_cert).use(cf::generateCertificate)
        val ks  = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null); setCertificateEntry("fms", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        trustManager = tmf.trustManagers[0] as X509TrustManager

        /* 2. TLS context */
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }

        /* 3. Custom factory that REMOVES SNI (fixes Apache 400 on IP endpoint) */
        val rawFactory = sslContext.socketFactory
        val noSniFactory = object : SSLSocketFactory() {
            override fun getDefaultCipherSuites() = rawFactory.defaultCipherSuites
            override fun getSupportedCipherSuites() = rawFactory.supportedCipherSuites
            private fun strip(s: Socket): Socket = s.apply {
                if (this is SSLSocket) {
                    try {
                        if (Build.VERSION.SDK_INT >= 24) {
                            /* API 24+ : clear the SNI serverName list */
                            val p = sslParameters
                            p.serverNames = emptyList()
                            sslParameters = p
                        }
                        // On API < 24 OkHttp already omits SNI for raw-IP hosts,
                        // so we simply do nothing.
                    } catch (_: Exception) { /* ignore – fall back to default */ }
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

        /* dumps request + response headers once per call */
        val dumper = Interceptor { chain ->
            val req = chain.request()
            Log.d(TAG, "=== REQUEST ===")
            Log.d(TAG, "${req.method} ${req.url}")
            req.headers.forEach { Log.d(TAG, "${it.first}: ${it.second}") }

            val rsp = chain.proceed(req)

            Log.d(TAG, "=== RESPONSE HEADERS ===")
            Log.d(TAG, "HTTP ${rsp.code}")
            rsp.headers.forEach { Log.d(TAG, "${it.first}: ${it.second}") }
            rsp
        }
        /* network-level dump – runs AFTER OkHttp adds Host, UA, etc. */
        val netDump = Interceptor { chain ->
            val req = chain.request()
            val rsp = chain.proceed(req)
            Log.d(TAG, "=== NETWORK REQUEST ===")
            Log.d(TAG, "${req.method} ${req.url}")
            req.headers.forEach { Log.d(TAG, "${it.first}: ${it.second}") }
            Log.d(TAG, "=== NETWORK RESPONSE HEADERS ===")
            Log.d(TAG, "HTTP ${rsp.code}")
            rsp.headers.forEach { Log.d(TAG, "${it.first}: ${it.second}") }
            rsp
        }
        /* normalize headers to match curl */
        val headerFix = Interceptor { chain ->
            val fixed = chain.request().newBuilder()
                .removeHeader("Accept-Encoding")
                .header("Accept-Encoding", "identity")   // disables gzip
                .removeHeader("Connection")              // omit Keep-Alive
                .build()
            chain.proceed(fixed)
        }
        /* 4. Wire-level logging interceptor (remove for production) */
        val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }

        /* 5. Build client – force HTTP/1.1 */
        client = OkHttpClient.Builder()
            .sslSocketFactory(noSniFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // skip hostname mismatch
            .protocols(listOf(Protocol.HTTP_1_1)) // disable HTTP/2
            .addInterceptor(dumper) // app-level headers
            .addNetworkInterceptor(headerFix)  // NEW
            .addNetworkInterceptor(netDump) // network-level headers (shows final form)
            .addInterceptor(logger) // body logger
            .build()
    }

    /** Public entry: login (if needed) then fire the label‑print script */
    suspend fun runPrintScript(ctx: Context, qrParam: String) {
        init(ctx)  // ensure client built

        withContext(Dispatchers.IO) {
            try {
                /* ------------ 1. LOGIN (Basic) ------------ */
                val basic = "Basic " + Base64.encodeToString("$USER:$PASS".toByteArray(), Base64.NO_WRAP)
                val loginReq = Request.Builder()
                    .url("https://$HOST/fmi/data/v1/databases/$DB/sessions")
                    // 1️⃣  send EMPTY body with no media‑type
                    .method("POST", "{}".toRequestBody(null))
                    .header("Authorization", basic)
                    // 2️⃣  leave a plain application/json header (no charset)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .build()

                val token = client.newCall(loginReq).execute().use { rsp ->
                    if (rsp.code != 200 && rsp.code != 201)
                        throw Exception("Login HTTP ${rsp.code} : ${rsp.body?.string()}")
                    val body = rsp.body?.string() ?: throw Exception("Empty login body")
                    JSONObject(body).getJSONObject("response").getString("token")
                }

                /* ------------ 2. SCRIPT call (Bearer) ------------ */
                val url = HttpUrl.Builder()
                    .scheme("https").host(HOST)
                    .addPathSegments("fmi/data/v1/databases/$DB/layouts/$LAYOUT/script/$SCRIPT")
                    .addQueryParameter("script.param", qrParam)
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
                    Toast.makeText(ctx, "Print script triggered for $qrParam", Toast.LENGTH_LONG).show()
                }

            } catch (ex: Exception) {
                Log.e(TAG, "Data API failure", ex)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "FileMaker API error: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
