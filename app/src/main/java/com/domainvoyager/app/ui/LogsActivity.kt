package com.domainvoyager.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.domainvoyager.app.data.AppDatabase
import com.domainvoyager.app.data.VisitLog
import com.domainvoyager.app.databinding.ActivityLogsBinding
import com.domainvoyager.app.databinding.ItemLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private lateinit var database: AppDatabase
    private lateinit var adapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Visit Logs"

        database = AppDatabase.getDatabase(this)
        adapter = LogAdapter()

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnClearLogs.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Logs")
                .setMessage("Delete all visit logs?")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { database.visitLogDao().clearAllLogs() }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        observeLogs()
    }

    private fun observeLogs() {
        database.visitLogDao().getRecentLogs().observe(this) { logs ->
            adapter.submitList(logs)
            binding.tvLogCount.text = "${logs.size} entries"
            binding.tvEmptyState.visibility =
                if (logs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

            val success = logs.count { it.status == "Success" }
            val failed = logs.count { it.status == "Failed" }
            binding.tvSuccessRate.text = "✅ $success success  ❌ $failed failed"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private var logs = listOf<VisitLog>()
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())

    fun submitList(list: List<VisitLog>) {
        logs = list
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = logs[position]
        with(holder.binding) {
            tvDomain.text = log.domainUrl
            tvTime.text = dateFormat.format(Date(log.visitTime))

            val isSuccess = log.status == "Success"
            tvStatus.text = if (isSuccess) "✅ Success" else "❌ Failed"
            tvStatus.setTextColor(
                if (isSuccess) android.graphics.Color.parseColor("#4CAF50")
                else android.graphics.Color.parseColor("#F44336")
            )

            tvTelegramStatus.text = if (log.telegramSent) "📤 Sent" else "📵 Not sent"

            if (log.errorMessage.isNotBlank()) {
                tvError.text = log.errorMessage
                tvError.visibility = android.view.View.VISIBLE
            } else {
                tvError.visibility = android.view.View.GONE
            }
        }
    }

    override fun getItemCount() = logs.size
}
