package com.domainvoyager.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.domainvoyager.app.R
import com.domainvoyager.app.data.AppDatabase
import com.domainvoyager.app.data.Domain
import com.domainvoyager.app.databinding.ActivityMainBinding
import com.domainvoyager.app.service.DomainVisitorService
import com.domainvoyager.app.utils.CsvParser
import com.domainvoyager.app.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.NotificationManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var database: AppDatabase

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private val csvPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importCsvFromUri(it) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startDomainVisitorService()
        else Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs    = PreferencesManager(this)
        database = AppDatabase.getDatabase(this)

        setupUI()
        observeData()
        updateUIState()

        // Overlay permission — MIUI safe
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                overlayPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: ActivityNotFoundException) {
                try {
                    overlayPermissionLauncher.launch(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e2: Exception) { }
            } catch (e: Exception) { }
        }

        // Android 14+ full screen intent permission — OEM safe
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                try {
                    startActivity(
                        Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENTS").apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: ActivityNotFoundException) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    } catch (e2: Exception) { }
                } catch (e: Exception) { }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerServiceCallbacks()
        updateUIState()
    }

    override fun onPause() {
        super.onPause()
        DomainVisitorService.onProgressUpdate = null
        DomainVisitorService.onComplete = null
    }

    private fun setupUI() {
        binding.btnStartVisiting.setOnClickListener {
            if (DomainVisitorService.isRunning) {
                stopService(Intent(this, DomainVisitorService::class.java).apply {
                    action = DomainVisitorService.ACTION_STOP
                })
                updateRunningState(false)
            } else {
                checkAndStartService()
            }
        }

        binding.btnManageDomains.setOnClickListener {
            startActivity(Intent(this, DomainListActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        binding.btnImportCsv.setOnClickListener {
            csvPickerLauncher.launch("*/*")
        }
    }

    private fun observeData() {
        database.domainDao().getAllDomains().observe(this) { domains ->
            val active = domains.count { it.isActive }
            binding.tvDomainCount.text = active.toString()
            binding.tvTotalDomains.text = "${domains.size} total"
        }

        database.visitLogDao().getRecentLogs().observe(this) { logs ->
            binding.tvTotalVisits.text = logs.size.toString()
            val success = logs.count { it.status == "Success" }
            val failed  = logs.count { it.status == "Failed" }
            binding.tvSuccessCount.text = success.toString()
            binding.tvFailedCount.text  = failed.toString()
        }
    }

    private fun registerServiceCallbacks() {
        DomainVisitorService.onProgressUpdate = { domain, current, total ->
            runOnUiThread {
                binding.tvCurrentDomain.text = domain
                binding.progressBar.max      = total
                binding.progressBar.progress = current
                binding.tvProgress.text      = "$current / $total"
                updateRunningState(true)
            }
        }

        DomainVisitorService.onComplete = {
            runOnUiThread {
                updateRunningState(false)
                binding.tvCurrentDomain.text = "Completed!"
                Toast.makeText(this, "✅ All domains visited!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUIState() {
        updateRunningState(DomainVisitorService.isRunning)
    }

    private fun updateRunningState(running: Boolean) {
        if (running) {
            binding.btnStartVisiting.text = "⏹ Stop Visiting"
            binding.btnStartVisiting.setBackgroundColor(ContextCompat.getColor(this, R.color.error_red))
            binding.cardProgress.visibility = View.VISIBLE
            binding.lottieStatus.text = "🔄"
        } else {
            binding.btnStartVisiting.text = "🚀 Start Visiting"
            binding.btnStartVisiting.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_blue))
            binding.cardProgress.visibility = View.GONE
            binding.lottieStatus.text = "✅"
        }
    }

    private fun checkAndStartService() {
        val botToken = prefs.getTelegramBotToken()
        val chatId   = prefs.getTelegramChatId()

        if (botToken.isBlank() || chatId.isBlank()) {
            Toast.makeText(this, "⚠️ Please configure Telegram settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { database.domainDao().getDomainCount() }
            if (count == 0) {
                Toast.makeText(this@MainActivity, "⚠️ No domains added. Please add domains first.", Toast.LENGTH_LONG).show()
                return@launch
            }
            requestNotificationPermissionThenStart()
        }
    }

    private fun requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startDomainVisitorService()
    }

    private fun startDomainVisitorService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, DomainVisitorService::class.java).apply {
                action = DomainVisitorService.ACTION_START
            }
        )
        updateRunningState(true)
    }

    private fun importCsvFromUri(uri: Uri) {
        lifecycleScope.launch {
            val domains = withContext(Dispatchers.IO) {
                CsvParser.parseDomainsFromUri(this@MainActivity, uri)
            }
            if (domains.isEmpty()) {
                Toast.makeText(this@MainActivity, "No valid domains found in CSV", Toast.LENGTH_SHORT).show()
                return@launch
            }
            withContext(Dispatchers.IO) {
                database.domainDao().insertDomains(domains.map { Domain(url = it) })
            }
            Toast.makeText(this@MainActivity, "✅ Imported ${domains.size} domains", Toast.LENGTH_LONG).show()
        }
    }
}