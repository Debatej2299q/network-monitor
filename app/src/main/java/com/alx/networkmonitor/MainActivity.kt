package com.alx.networkmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var bridge: NetworkBridge
    private val permissionRequest = 1001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bridge = NetworkBridge(this)

        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(15, 23, 42))
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                builtInZoomControls = false
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            addJavascriptInterface(bridge, "AndroidNetwork")
        }

        setContentView(webView)
        MainActivityBridge.webView = webView
        webView.loadUrl("file:///android_asset/index.html")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), permissionRequest)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        MainActivityBridge.webView = null
        webView.destroy()
        super.onDestroy()
    }
}

class NetworkBridge(private val context: Context) {

    @JavascriptInterface
    fun getNetworkInfo(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        var type = "Offline"
        var connected = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null) {
                connected = true
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Other"
                }
            }
        }

        var rssi = -999
        var linkMbps = 0
        var frequency = 0
        var ssid = "Unknown"

        try {
            val info: WifiInfo = wm.connectionInfo
            if (info.networkId != -1) {
                rssi = info.rssi
                linkMbps = info.linkSpeed
                frequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) info.frequency else 0
                ssid = info.ssid?.trim('"') ?: "Unknown"
            }
        } catch (_: Exception) {}

        return JSONObject()
            .put("connected", connected)
            .put("type", type)
            .put("rssi", rssi)
            .put("linkMbps", linkMbps)
            .put("frequencyMHz", frequency)
            .put("ssid", ssid)
            .toString()
    }

    @JavascriptInterface
    fun runDownloadTest(bytes: Long, callbackName: String) {
        Thread {
            val result = speedTest(
                url = "https://speed.cloudflare.com/__down?bytes=$bytes",
                upload = false
            )
            val js = "window.$callbackName(${JSONObject.quote(result)})"
            (context as Activity).runOnUiThread {
                MainActivityBridge.evaluate(js)
            }
        }.start()
    }

    @JavascriptInterface
    fun runUploadTest(bytes: Int, callbackName: String) {
        Thread {
            val result = speedTest(
                url = "https://speed.cloudflare.com/__up",
                upload = true,
                uploadBytes = bytes
            )
            val js = "window.$callbackName(${JSONObject.quote(result)})"
            (context as Activity).runOnUiThread {
                // MainActivity exposes the WebView through a static-safe helper.
                MainActivityBridge.evaluate(js)
            }
        }.start()
    }

    private fun speedTest(url: String, upload: Boolean, uploadBytes: Int = 0): String {
        var conn: HttpURLConnection? = null
        try {
            val start = System.nanoTime()
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.useCaches = false
            conn.requestMethod = if (upload) "POST" else "GET"
            conn.setRequestProperty("Cache-Control", "no-cache")

            if (upload) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                val chunk = ByteArray(64 * 1024) { (it * 31).toByte() }
                var remaining = uploadBytes
                conn.outputStream.use { out ->
                    while (remaining > 0) {
                        val n = minOf(remaining, chunk.size)
                        out.write(chunk, 0, n)
                        remaining -= n
                    }
                    out.flush()
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            var transferred = 0L
            if (!upload && stream != null) {
                stream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        transferred += n
                    }
                }
            }

            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            val bytes = if (upload) uploadBytes.toLong() else transferred
            val mbps = if (seconds > 0) (bytes * 8.0 / seconds / 1_000_000.0) else 0.0

            return JSONObject()
                .put("ok", code in 200..399)
                .put("code", code)
                .put("mbps", (mbps * 10).roundToInt() / 10.0)
                .put("seconds", (seconds * 100).roundToInt() / 100.0)
                .put("bytes", bytes)
                .toString()
        } catch (e: Exception) {
            return JSONObject()
                .put("ok", false)
                .put("error", e.javaClass.simpleName)
                .put("message", e.message ?: "Network test failed")
                .toString()
        } finally {
            conn?.disconnect()
        }
    }
}

object MainActivityBridge {
    var webView: WebView? = null
    fun evaluate(js: String) {
        webView?.post { webView?.evaluateJavascript(js, null) }
    }
}
