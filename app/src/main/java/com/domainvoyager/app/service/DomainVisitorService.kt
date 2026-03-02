package com.domainvoyager.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.domainvoyager.app.data.AppDatabase
import com.domainvoyager.app.data.VisitLog
import com.domainvoyager.app.ui.MainActivity
import com.domainvoyager.app.ui.WebViewVisitorActivity
import com.domainvoyager.app.utils.PreferencesManager
import com.domainvoyager.app.utils.TelegramHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Bitmap
import android.graphics.Canvas

class DomainVisitorService : Service() {

    companion object {
        private const val TAG = "DomainVisitorService"

        const val CHANNEL_ID      = "domain_voyager_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START    = "com.domainvoyager.START"
        const val ACTION_STOP     = "com.domainvoyager.STOP"

        var isRunning = false
        var currentDomain = ""
        var progress = 0
        var total    = 0

        var onProgressUpdate: ((String, Int, Int) -> Unit)? = null
        var onComplete: (() -> Unit)? = null

        // Activity completes this with screenshot path (removes race conditions)
        @Volatile
        var screenshotDeferred: CompletableDeferred<String>? = null

        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }

        private val DATE_FORMAT = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }

        private const val WEBVIEW_TIMEOUT_MS = 45_000L

        // Summary tracks: screenshot saved + telegram sent
        data class DomainResult(
            val index: Int,
            val url: String,
            val screenshotSaved: Boolean,
            val telegramSent: Boolean
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: PreferencesManager
    private lateinit var telegramHelper: TelegramHelper
    private lateinit var database: AppDatabase

    private val cycleResults = mutableListOf<DomainResult>()

    override fun onCreate() {
        super.onCreate()
        prefs          = PreferencesManager(this)
        telegramHelper = TelegramHelper()
        database       = AppDatabase.getDatabase(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isRunning = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) return START_STICKY // avoid double-start
                isRunning = true
                startForeground(NOTIFICATION_ID, buildNotification("Starting Domain Voyager..."))
                serviceScope.launch { runVisitCycleLoop() }
            }
        }
        return START_STICKY
    }

    // No recursion (safe for long auto-repeat)
    private suspend fun runVisitCycleLoop() {
        do {
            runSingleCycle()

            if (!prefs.isAutoRepeatEnabled()) break
            if (!isRunning) break

            val intervalMs = prefs.getRepeatIntervalMinutes() * 60 * 1000L
            Log.i(TAG, "Auto-repeat in ${prefs.getRepeatIntervalMinutes()} min")
            delay(intervalMs)
        } while (isRunning)

        isRunning = false
        stopSelf()
    }

    private suspend fun runSingleCycle() {
        val domains = withContext(Dispatchers.IO) {
            database.domainDao().getActiveDomains()
        }

        if (domains.isEmpty()) {
            Log.w(TAG, "No active domains — stopping.")
            onComplete?.invoke()
            return
        }

        total    = domains.size
        progress = 0
        cycleResults.clear()

        val botToken = prefs.getTelegramBotToken()
        val chatId   = prefs.getTelegramChatId()

        Log.i(TAG, "=== CYCLE START === $total domains")

        for ((index, domain) in domains.withIndex()) {
            if (!isRunning) break

            progress      = index + 1
            currentDomain = domain.url

            Log.i(TAG, "┌─ [$progress/$total] ${domain.url}")
            onProgressUpdate?.invoke(domain.url, progress, total)
            updateNotification("Visiting ($progress/$total): ${domain.url}")

            withContext(Dispatchers.IO) {
                database.domainDao().updateDomainStatus(domain.id, "Visiting", System.currentTimeMillis())
            }

            var screenshotPath = ""
            var telegramSent   = false
            var errorMsg       = ""

            // Create deferred for this domain
            val deferred = CompletableDeferred<String>()
            screenshotDeferred = deferred

            // Launch WebView activity
            val webViewIntent = Intent(this, WebViewVisitorActivity::class.java).apply {
                putExtra(WebViewVisitorActivity.EXTRA_URL, domain.url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(webViewIntent)
            Log.d(TAG, "├─ [WEBVIEW] Launched for ${domain.url}")

            // Wait for result from Activity (or timeout)
            screenshotPath = withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
                deferred.await()
            } ?: run {
                errorMsg = "WebView timed out after ${WEBVIEW_TIMEOUT_MS / 1000}s"
                Log.e(TAG, "├─ [WEBVIEW] TIMEOUT: $errorMsg")
                ""
            }

            // Determine screenshotSaved (exists + >0 bytes) with small flush-wait retry
            val screenshotSaved = waitForValidFile(screenshotPath).also { ok ->
                if (ok) {
                    val f = File(screenshotPath)
                    Log.i(TAG, "├─ [SCREENSHOT] ✅ ${f.length()} bytes")
                } else {
                    if (errorMsg.isEmpty()) errorMsg = "Screenshot missing or empty file"
                    Log.e(TAG, "├─ [SCREENSHOT] ❌ $errorMsg (path=$screenshotPath)")
                }
            }

            // Send to Telegram (only if screenshotSaved)
            if (botToken.isNotBlank() && chatId.isNotBlank() && screenshotSaved) {
                val file = File(screenshotPath)
                val fileToSend = if (file.length() > 10 * 1024 * 1024) {
                    Log.w(TAG, "├─ [TELEGRAM] Compressing...")
                    compressScreenshot(file) ?: file
                } else file

                val caption = "$progress. ${domain.url}\n${getCurrentTime()} WIB"
                telegramSent = withContext(Dispatchers.IO) {
                    telegramHelper.sendPhoto(botToken, chatId, fileToSend, caption)
                }
                Log.i(TAG, "├─ [TELEGRAM] Sent: $telegramSent")
            }

            // Persist result (status based on screenshotSaved)
            val status = if (screenshotSaved) "Success" else "Failed"
            withContext(Dispatchers.IO) {
                database.domainDao().updateDomainStatus(domain.id, status, System.currentTimeMillis())
                database.visitLogDao().insertLog(
                    VisitLog(
                        domainUrl      = domain.url,
                        status         = status,
                        screenshotPath = screenshotPath,
                        telegramSent   = telegramSent,
                        errorMessage   = errorMsg
                    )
                )
            }

            // Summary row: Shot = saved, TG = sent
            cycleResults.add(
                DomainResult(
                    index = progress,
                    url = domain.url,
                    screenshotSaved = screenshotSaved,
                    telegramSent = telegramSent
                )
            )

            Log.i(TAG, "└─ DONE: status=$status | shot=$screenshotSaved | telegram=$telegramSent")

            // breathing room between WebViews
            delay(2_000)
        }

        Log.i(TAG, "=== CYCLE COMPLETE === $progress/$total")

        // Send summary report
        if (botToken.isNotBlank() && chatId.isNotBlank()) {
            val summary = buildSummaryReport()
            withContext(Dispatchers.IO) {
                telegramHelper.sendMessage(botToken, chatId, summary)
            }
            Log.i(TAG, "Summary report sent")
        }

        onComplete?.invoke()
    }

    // waits up to ~2 seconds for file to appear and be non-zero
    private suspend fun waitForValidFile(path: String): Boolean {
        if (path.isBlank()) return false
        val f = File(path)
        repeat(10) {
            if (f.exists() && f.length() > 0L) return true
            delay(200)
        }
        return f.exists() && f.length() > 0L
    }

    private fun buildSummaryReport(): String {
        val totalShot = cycleResults.count { it.screenshotSaved }
        val totalTG   = cycleResults.count { it.telegramSent }
        val timestamp = DATE_FORMAT.format(Date())

        val COL_NO     = 3
        val COL_DOMAIN = 35
        val COL_SHOT   = 5
        val COL_TG     = 5

        fun fix(s: String, width: Int): String =
            if (s.length > width) s.take(width - 2) + ".."
            else s.padEnd(width)

        fun divider() =
            "+${"-".repeat(COL_NO+2)}+${"-".repeat(COL_DOMAIN+2)}+${"-".repeat(COL_SHOT+2)}+${"-".repeat(COL_TG+2)}+"

        fun row(no: String, domain: String, shot: String, tg: String) =
            "| ${fix(no, COL_NO)} | ${fix(domain, COL_DOMAIN)} | ${fix(shot, COL_SHOT)} | ${fix(tg, COL_TG)} |"

        val sb = StringBuilder()
        sb.appendLine("📊 *Scan Complete*")
        sb.appendLine("Domains : ${cycleResults.size}  |  $timestamp WIB")
        sb.appendLine("Screenshots: $totalShot/${cycleResults.size}  |  Telegram: $totalTG/${cycleResults.size}")
        sb.appendLine()

        sb.appendLine("```")
        sb.appendLine(divider())
        sb.appendLine(row("No", "Domain", "Shot", "TG"))
        sb.appendLine(divider())

        for (r in cycleResults) {
            val no = "${r.index}"
            val domain = r.url
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
            val shot = if (r.screenshotSaved) "✅" else "❌"
            val tg   = if (r.telegramSent) "✅" else "❌"
            sb.appendLine(row(no, domain, shot, tg))
        }

        sb.appendLine(divider())
        sb.append("```")

        return sb.toString()
    }

    private fun compressScreenshot(file: File): File? {
        return try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val out = File(file.parent, "compressed_${file.name.replace(".png", ".jpg")}")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 75, it) }
            bmp.recycle()
            out
        } catch (e: Exception) {
            Log.e(TAG, "compressScreenshot: ${e.message}")
            null
        }
    }

    private fun getCurrentTime(): String = TIME_FORMAT.format(Date())

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Domain Voyager", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Domain visiting progress" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DomainVisitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Domain Voyager")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}