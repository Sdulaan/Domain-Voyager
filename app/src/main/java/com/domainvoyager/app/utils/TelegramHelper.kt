package com.domainvoyager.app.utils

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramHelper {

    companion object {
        private const val TAG = "TelegramHelper"
        private const val BASE_URL = "https://api.telegram.org/bot"
        private const val MAX_LOG_BODY_CHARS = 2000
    }

    // ✅ Add timeouts (uploads can take time)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun sendMessage(botToken: String, chatId: String, message: String): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false

        val url = "$BASE_URL$botToken/sendMessage"
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", message)
            // Your message contains Markdown-like formatting, not HTML.
            // If you want MarkdownV2, change this and escape properly.
            put("parse_mode", "HTML")
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "sendMessage failed: code=${resp.code} body=${trim(respBody)}")
                    return false
                }

                // Telegram returns {"ok":true,...} when successful
                val ok = try {
                    JSONObject(respBody).optBoolean("ok", resp.isSuccessful)
                } catch (_: Exception) {
                    resp.isSuccessful
                }

                if (!ok) Log.e(TAG, "sendMessage not ok: body=${trim(respBody)}")
                ok
            }
        } catch (e: IOException) {
            Log.e(TAG, "sendMessage IOException: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage Exception: ${e.message}")
            false
        }
    }

    fun sendPhoto(
        botToken: String,
        chatId: String,
        photoFile: File,
        caption: String
    ): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false
        if (!photoFile.exists() || photoFile.length() <= 0L) {
            Log.e(TAG, "sendPhoto: file missing/empty: ${photoFile.absolutePath}")
            return false
        }

        // ✅ Correct MIME by extension (important if you compress to .jpg)
        val mime = when (photoFile.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }.toMediaType()

        // Attempt #1 with parse_mode=HTML (your current behavior)
        val ok1 = sendPhotoInternal(
            botToken = botToken,
            chatId = chatId,
            photoFile = photoFile,
            caption = caption,
            mime = mime,
            parseMode = "HTML"
        )
        if (ok1) return true

        // ✅ Retry once WITHOUT parse_mode (often fixes "can't parse entities" errors)
        Log.w(TAG, "sendPhoto retrying without parse_mode (caption parsing may have failed)")
        return sendPhotoInternal(
            botToken = botToken,
            chatId = chatId,
            photoFile = photoFile,
            caption = caption,
            mime = mime,
            parseMode = null
        )
    }

    private fun sendPhotoInternal(
        botToken: String,
        chatId: String,
        photoFile: File,
        caption: String,
        mime: okhttp3.MediaType,
        parseMode: String?
    ): Boolean {
        val url = "$BASE_URL$botToken/sendPhoto"

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", caption)
            .addFormDataPart(
                "photo",
                photoFile.name,
                photoFile.asRequestBody(mime)
            )

        if (!parseMode.isNullOrBlank()) {
            builder.addFormDataPart("parse_mode", parseMode)
        }

        val request = Request.Builder()
            .url(url)
            .post(builder.build())
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()

                if (!resp.isSuccessful) {
                    Log.e(
                        TAG,
                        "sendPhoto failed: code=${resp.code} file=${photoFile.name} size=${photoFile.length()} body=${trim(respBody)}"
                    )
                    return false
                }

                val ok = try {
                    JSONObject(respBody).optBoolean("ok", true)
                } catch (_: Exception) {
                    true
                }

                if (!ok) {
                    Log.e(TAG, "sendPhoto response ok=false body=${trim(respBody)}")
                }

                ok
            }
        } catch (e: IOException) {
            Log.e(TAG, "sendPhoto IOException: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "sendPhoto Exception: ${e.message}")
            false
        }
    }

    fun testConnection(botToken: String, chatId: String): Pair<Boolean, String> {
        return try {
            val url = "$BASE_URL$botToken/getMe"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Pair(false, "❌ Connection failed: ${response.code}")
                } else {
                    val json = JSONObject(body.ifBlank { "{}" })
                    if (json.optBoolean("ok", false)) Pair(true, "✅ Connected successfully!")
                    else Pair(false, "❌ Invalid bot token")
                }
            }
        } catch (e: Exception) {
            Pair(false, "❌ Error: ${e.message}")
        }
    }

    private fun trim(s: String): String =
        if (s.length <= MAX_LOG_BODY_CHARS) s
        else s.take(MAX_LOG_BODY_CHARS) + "...(trimmed)"
}