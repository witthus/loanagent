package com.loanagent.agent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debug-only media prep: download signed URLs into MediaStore before publish_note.
 * Lives in debug because main PlaybookEngine must not use INTERNET.
 */
object MediaBridge {
    private const val TAG = "MediaBridge"

    /**
     * @return updated params, unchanged params when no media_urls, or null on failure.
     */
    fun preparePublishParams(
        context: Context,
        params: Map<String, Any?>,
        openConnection: (String) -> HttpURLConnection = { url ->
            (URL(url).openConnection() as HttpURLConnection)
        },
    ): Map<String, Any?>? {
        if (!params.containsKey("media_urls")) return params
        val entries = parseMediaEntries(params["media_urls"]) ?: return null
        if (entries.isEmpty()) return params

        return try {
            for ((index, entry) in entries.withIndex()) {
                val url = entry.url
                if (url.isBlank()) return null
                val filename = entry.filename?.takeIf { it.isNotBlank() }
                    ?: defaultFilename(url, index)
                val cacheFile = downloadToCache(context, url, filename, openConnection)
                    ?: return null
                try {
                    if (!insertIntoMediaStore(context, cacheFile, filename)) {
                        return null
                    }
                } finally {
                    cacheFile.delete()
                }
            }
            mergePrepared(params)
        } catch (error: Exception) {
            Log.w(TAG, "preparePublishParams failed", error)
            null
        }
    }

    /** Package-visible helper for unit tests: mark params as media-prepared. */
    fun mergePrepared(params: Map<String, Any?>): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out.putAll(params)
        out["media_prepared"] = true
        return out
    }

    private data class MediaEntry(val url: String, val filename: String?)

    private fun parseMediaEntries(raw: Any?): List<MediaEntry>? {
        if (raw == null) return null
        return when (raw) {
            is JSONArray -> {
                buildList {
                    for (i in 0 until raw.length()) {
                        add(parseEntry(raw.get(i)) ?: return null)
                    }
                }
            }
            is List<*> -> {
                buildList {
                    for (item in raw) {
                        add(parseEntry(item) ?: return null)
                    }
                }
            }
            else -> null
        }
    }

    private fun parseEntry(raw: Any?): MediaEntry? {
        return when (raw) {
            is JSONObject -> {
                val url = raw.optString("url").trim()
                if (url.isEmpty()) return null
                val filename = raw.optString("filename").trim().ifEmpty { null }
                MediaEntry(url, filename)
            }
            is Map<*, *> -> {
                val url = raw["url"]?.toString()?.trim().orEmpty()
                if (url.isEmpty()) return null
                val filename = raw["filename"]?.toString()?.trim()?.ifEmpty { null }
                MediaEntry(url, filename)
            }
            else -> null
        }
    }

    private fun defaultFilename(url: String, index: Int): String {
        val path = try {
            URL(url).path.substringAfterLast('/').takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        return path ?: "loanagent_media_$index.jpg"
    }

    private fun downloadToCache(
        context: Context,
        url: String,
        filename: String,
        openConnection: (String) -> HttpURLConnection,
    ): File? {
        val connection = openConnection(url)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "download HTTP $code for $url")
                return null
            }
            val cacheDir = File(context.cacheDir, "media_bridge").also { it.mkdirs() }
            val outFile = File(cacheDir, filename)
            connection.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!outFile.exists() || outFile.length() == 0L) null else outFile
        } catch (error: Exception) {
            Log.w(TAG, "download failed for $url", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun insertIntoMediaStore(context: Context, file: File, displayName: String): Boolean {
        val resolver = context.contentResolver
        val mime = guessMime(displayName)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/Camera",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri: Uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(file).use { input ->
                    input.copyTo(output)
                }
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (error: Exception) {
            Log.w(TAG, "MediaStore insert failed", error)
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun guessMime(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
    }
}
