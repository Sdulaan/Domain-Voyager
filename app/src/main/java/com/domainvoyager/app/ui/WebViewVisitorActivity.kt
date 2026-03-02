package com.domainvoyager.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.domainvoyager.app.service.DomainVisitorService
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class WebViewVisitorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewVisitorActivity"
        const val EXTRA_URL = "extra_url"

        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        private const val SETTLE_DELAY_MS = 6_000L

        // Telegram-safe max height for image
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
    private var lastProgress = 0

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
            // ✅ IMPORTANT: real size so WebView actually renders
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // ✅ keep rendering but visually hidden
            alpha = 0.01f
            setBackgroundColor(android.graphics.Color.WHITE)

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

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    lastProgress = newProgress
                    Log.d(TAG, "progress=$newProgress url=${view.url}")
                }
            }

            webViewClient = object : WebViewClient() {

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "onPageFinished: $url")
                    if (firstPageLoaded) return
                    firstPageLoaded = true

                    // Let it settle + paint
                    handler.postDelayed({
                        view.evaluateJavascript("(function(){ return document.readyState; })();") { rs ->
                            Log.d(TAG, "readyState=$rs progress=$lastProgress -> screenshot soon")
                            // extra delay if progress still low
                            val extra = if (lastProgress < 80) 2500L else 800L
                            handler.postDelayed({ waitForSizeThenScreenshot() }, extra)
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
                        handler.postDelayed({ takeScreenshotAndFinish() }, 1500)
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

    private fun waitForSizeThenScreenshot() {
        if (screenshotTaken) return

        // ✅ wait until WebView is actually laid out
        if (webView.width <= 1 || webView.height <= 1) {
            Log.w(TAG, "WebView size not ready (${webView.width}x${webView.height}), waiting...")
            handler.postDelayed({ waitForSizeThenScreenshot() }, 300)
            return
        }

        takeScreenshotAndFinish()
    }

    private fun takeScreenshotAndFinish() {
        if (screenshotTaken) return
        screenshotTaken = true
        handler.removeCallbacksAndMessages(null)

        val path = doScreenshot()
        reportResult(path)
        finish()
    }

    private fun doScreenshot(): String {
        return try {
            val w = webView.width.coerceAtLeast(1)

            // ✅ capture viewport height (stable). clamp for Telegram anyway.
            val fullH = webView.height.coerceAtLeast(1)
            val h = min(fullH, MAX_CAPTURE_HEIGHT_PX)

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)

            val safeName = (webView.url ?: "screenshot")
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .take(50)

            val file = File(saveDir, "${safeName}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()

            Log.i(TAG, "Screenshot saved: ${file.absolutePath} (${file.length()} bytes) w=$w h=$h")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed: ${e.message}", e)
            ""
        }
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