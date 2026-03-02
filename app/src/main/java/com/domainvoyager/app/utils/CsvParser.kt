package com.domainvoyager.app.utils

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

object CsvParser {

    fun parseDomainsFromUri(context: Context, uri: Uri): List<String> {
        val domains = mutableListOf<String>()
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var isFirstLine = true

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue

                // Skip header row if it contains "domain" or "url"
                if (isFirstLine && (trimmed.lowercase().contains("domain") ||
                            trimmed.lowercase().contains("url"))) {
                    isFirstLine = false
                    continue
                }
                isFirstLine = false

                // Handle CSV with multiple columns - take first column
                val url = trimmed.split(",").firstOrNull()?.trim()?.removeSurrounding("\"") ?: continue
                if (url.isNotBlank()) {
                    domains.add(normalizeUrl(url))
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return domains.filter { it.isNotBlank() }.distinct()
    }

    fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "https://$url"
        }
    }

    fun isValidUrl(url: String): Boolean {
        return try {
            val normalized = normalizeUrl(url)
            android.util.Patterns.WEB_URL.matcher(normalized).matches()
        } catch (e: Exception) {
            false
        }
    }
}
