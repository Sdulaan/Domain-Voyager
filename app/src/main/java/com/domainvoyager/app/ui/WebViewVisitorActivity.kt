package com.domainvoyager.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.domainvoyager.app.service.DomainVisitorService
import java.io.File
import java.io.FileOutputStream

class WebViewVisitorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewVisitorActivity"
        const val EXTRA_URL = "extra_url"

        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        private const val SETTLE_DELAY_MS = 6_000L

        // ✅ Telegram-safe max height for image
        private const val MAX_CAPTURE_HEIGHT_PX = 4096

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())

    private var screenshotTaken = false
    private var firstPageLoaded = false
    private var saveDir: File? = null

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "Hard timeout reached — forcing screenshot")
        takeScreenshotAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val inputUrl = intent.getStringExtra(EXTRA_URL) ?: run {
            reportResult("")
            finish()
            return
        }

        saveDir = File(filesDir, "screenshots").also { if (!it.exists()) it.mkdirs() }

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                userAgentString = USER_AGENT
            }

            webViewClient = object : WebViewClient() {

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "onPageFinished: $url")
                    if (firstPageLoaded) return
                    firstPageLoaded = true

                    handler.postDelayed({
                        view.evaluateJavascript("(function(){ return document.readyState; })();") {
                            Log.d(TAG, "readyState=$it -> screenshot")
                            takeScreenshotAndFinish()
                        }
                    }, SETTLE_DELAY_MS)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Log.e(TAG, "Main frame error: ${error.description}")
                        handler.postDelayed({ takeScreenshotAndFinish() }, 2_000)
                    }
                }
            }
        }

        setContentView(webView)

        val fullUrl = if (inputUrl.startsWith("http")) inputUrl else "https://$inputUrl"
        Log.d(TAG, "Loading: $fullUrl")
        webView.loadUrl(fullUrl)

        handler.postDelayed(timeoutRunnable, PAGE_LOAD_TIMEOUT_MS)
    }

    private fun takeScreenshotAndFinish() {
        if (screenshotTaken) return
        screenshotTaken = true
        handler.removeCallbacksAndMessages(null)
        doScreenshot()
    }

    private fun doScreenshot() {
        val path = try {
            // Force layout measurement
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            // ✅ Clamp height so Telegram accepts
            val width = webView.measuredWidth.coerceAtLeast(1)
            val fullHeight = webView.measuredHeight.coerceAtLeast(1)
            val height = minOf(fullHeight, MAX_CAPTURE_HEIGHT_PX)

            webView.layout(0, 0, width, height)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(bitmap))

            val safeName = (webView.url ?: "screenshot")
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .take(50)

            val file = File(saveDir, "${safeName}_${System.currentTimeMillis()}.jpg")

            // ✅ Save as JPEG (smaller + Telegram friendly)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()

            Log.i(TAG, "Screenshot saved: ${file.absolutePath} (${file.length()} bytes) " +
                    "w=$width h=$height fullH=$fullHeight")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed: ${e.message}")
            ""
        }

        reportResult(path)
        finish()
    }

    private fun reportResult(path: String) {
        DomainVisitorService.screenshotDeferred?.complete(path)
        Log.d(TAG, "Reported result: $path")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}