package com.domainvoyager.app.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.domainvoyager.app.data.AppDatabase
import com.domainvoyager.app.data.Domain
import com.domainvoyager.app.databinding.ActivityDomainListBinding
import com.domainvoyager.app.databinding.ItemDomainBinding
import com.domainvoyager.app.utils.CsvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DomainListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDomainListBinding
    private lateinit var database: AppDatabase
    private lateinit var adapter: DomainAdapter

    private val csvPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importCsv(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDomainListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Manage Domains"

        database = AppDatabase.getDatabase(this)
        adapter = DomainAdapter(
            onDelete = { domain -> deleteDomain(domain) },
            onToggle = { domain -> toggleDomain(domain) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnAddDomain.setOnClickListener { showAddDomainDialog() }
        binding.btnImportCsv.setOnClickListener { csvPickerLauncher.launch("*/*") }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }

        observeDomains()
    }

    private fun observeDomains() {
        database.domainDao().getAllDomains().observe(this) { domains ->
            adapter.submitList(domains)
            binding.tvEmptyState.visibility =
                if (domains.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvDomainCount.text = "${domains.size} domains"
        }
    }

    private fun showAddDomainDialog() {
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val input = android.widget.EditText(this).apply {
            hint = "Enter domain (e.g. google.com)"
            setPadding(48, 32, 48, 32)
            setTextColor(android.graphics.Color.BLACK)
            setHintTextColor(android.graphics.Color.GRAY)
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Domain")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!CsvParser.isValidUrl(url)) {
                    Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addDomain(CsvParser.normalizeUrl(url))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addDomain(url: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.domainDao().insertDomain(Domain(url = url))
            }
            Toast.makeText(this@DomainListActivity, "Domain added!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteDomain(domain: Domain) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.domainDao().deleteDomain(domain)
            }
        }
    }

    private fun toggleDomain(domain: Domain) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.domainDao().updateDomain(domain.copy(isActive = !domain.isActive))
            }
        }
    }

    private fun importCsv(uri: Uri) {
        lifecycleScope.launch {
            val domains = withContext(Dispatchers.IO) {
                CsvParser.parseDomainsFromUri(this@DomainListActivity, uri)
            }
            if (domains.isEmpty()) {
                Toast.makeText(this@DomainListActivity, "No valid domains found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            withContext(Dispatchers.IO) {
                database.domainDao().insertDomains(domains.map { Domain(url = it) })
            }
            Toast.makeText(this@DomainListActivity, "Imported ${domains.size} domains", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Domains")
            .setMessage("Are you sure you want to delete all domains?")
            .setPositiveButton("Delete All") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        database.domainDao().deleteAllDomains()
                    }
                    Toast.makeText(this@DomainListActivity, "All domains cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class DomainAdapter(
    private val onDelete: (Domain) -> Unit,
    private val onToggle: (Domain) -> Unit
) : RecyclerView.Adapter<DomainAdapter.ViewHolder>() {

    private var domains = listOf<Domain>()

    fun submitList(list: List<Domain>) {
        domains = list
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemDomainBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDomainBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val domain = domains[position]
        with(holder.binding) {
            tvDomainUrl.text = domain.url
            tvStatus.text = domain.status
            tvVisitCount.text = "Visits: ${domain.visitCount}"

            val statusColor = when (domain.status) {
                "Success" -> android.graphics.Color.parseColor("#4CAF50")
                "Failed" -> android.graphics.Color.parseColor("#F44336")
                "Visiting" -> android.graphics.Color.parseColor("#2196F3")
                else -> android.graphics.Color.parseColor("#9E9E9E")
            }
            tvStatus.setTextColor(statusColor)

            switchActive.isChecked = domain.isActive
            switchActive.setOnCheckedChangeListener(null)
            switchActive.setOnCheckedChangeListener { _, _ -> onToggle(domain) }

            btnDelete.setOnClickListener { onDelete(domain) }
        }
    }

    override fun getItemCount() = domains.size
}
