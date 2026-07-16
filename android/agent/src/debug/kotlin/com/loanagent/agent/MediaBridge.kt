package com.loanagent.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-only media prep: download signed URLs into MediaStore before publish_note.
 * Lives in debug because main PlaybookEngine must not use INTERNET.
 */
object MediaBridge {
    private const val TAG = "MediaBridge"
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_SLEEP_MS = 400L
    private const val MAX_DOWNLOAD_BYTES = 40L * 1024L * 1024L

    private val lastError = AtomicReference<String?>(null)

    fun lastErrorCode(): String? = lastError.get()

    fun clearLastError() {
        lastError.set(null)
    }

    /**
     * @return updated params, unchanged params when no media_urls, or null on failure.
     */
    fun preparePublishParams(
        context: Context,
        params: Map<String, Any?>,
        execution: TaskExecutionContext? = null,
        openConnection: (String) -> HttpURLConnection = { url ->
            (URL(url).openConnection() as HttpURLConnection)
        },
    ): Map<String, Any?>? {
        clearLastError()
        if (!params.containsKey("media_urls")) return params
        val entries = parseMediaEntries(params["media_urls"])
        if (entries == null) {
            fail("MEDIA_MISSING", "media_urls parse failed")
            return null
        }
        if (entries.isEmpty()) return params

        return try {
            for ((index, entry) in entries.withIndex()) {
                checkExecution(execution)
                val url = entry.url
                if (url.isBlank()) {
                    fail("MEDIA_MISSING", "blank url at index=$index")
                    return null
                }
                val baseName = entry.filename?.takeIf { it.isNotBlank() }
                    ?: defaultFilename(url, index)
                // Unique display names avoid MediaStore collisions across multi-image publishes.
                val filename = "la_${index}_${System.currentTimeMillis()}_$baseName"
                val cacheFile = downloadToCacheWithRetry(
                    context,
                    url,
                    filename,
                    index,
                    entries.size,
                    execution,
                    openConnection,
                ) ?: return null
                try {
                    if (!insertIntoMediaStoreWithRetry(context, cacheFile, filename, index, execution)) {
                        return null
                    }
                } finally {
                    cacheFile.delete()
                }
                Log.i(TAG, "prepared index=$index/${entries.size} name=$filename")
            }
            checkExecution(execution)
            clearLastError()
            mergePrepared(params)
        } catch (cancelled: TaskExecutionCancelledException) {
            throw cancelled
        } catch (error: Exception) {
            fail("MEDIA_MISSING", "preparePublishParams failed: ${error.message}")
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

    private fun downloadToCacheWithRetry(
        context: Context,
        url: String,
        filename: String,
        index: Int,
        total: Int,
        execution: TaskExecutionContext?,
        openConnection: (String) -> HttpURLConnection,
    ): File? {
        var lastDetail = "unknown"
        repeat(MAX_ATTEMPTS) { attempt ->
            checkExecution(execution)
            val file = downloadToCache(context, url, filename, execution, openConnection)
            if (file != null) return file
            lastDetail = "download failed index=$index/$total attempt=${attempt + 1}/$MAX_ATTEMPTS"
            Log.w(TAG, lastDetail)
            if (attempt < MAX_ATTEMPTS - 1) {
                sleepRetry(execution)
            }
        }
        fail("MEDIA_DOWNLOAD_FAILED", lastDetail)
        return null
    }

    private fun downloadToCache(
        context: Context,
        url: String,
        filename: String,
        execution: TaskExecutionContext?,
        openConnection: (String) -> HttpURLConnection,
    ): File? {
        checkExecution(execution)
        val connection = openConnection(url)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = boundedTimeout(execution, 20_000)
            connection.readTimeout = boundedTimeout(execution, 60_000)
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            checkExecution(execution)
            if (code !in 200..299) {
                Log.w(TAG, "download HTTP $code for $url")
                return null
            }
            val contentType = connection.contentType
            if (!MediaImageBytes.contentTypeLooksLikeImage(contentType)) {
                Log.w(TAG, "download non-image Content-Type=$contentType for $url")
                return null
            }
            val cacheDir = File(context.cacheDir, "media_bridge").also { it.mkdirs() }
            val outFile = File(cacheDir, filename)
            connection.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    copyCooperatively(input, output, execution, MAX_DOWNLOAD_BYTES)
                }
            }
            if (!outFile.exists() || outFile.length() == 0L) {
                Log.w(TAG, "download empty file for $url")
                outFile.delete()
                return null
            }
            val header = readPrefix(outFile, 16)
            if (!MediaImageBytes.looksLikeImage(header)) {
                Log.w(TAG, "download body is not an image (magic) for $url")
                outFile.delete()
                return null
            }
            outFile
        } catch (cancelled: TaskExecutionCancelledException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "download failed for $url", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun insertIntoMediaStoreWithRetry(
        context: Context,
        file: File,
        displayName: String,
        index: Int,
        execution: TaskExecutionContext?,
    ): Boolean {
        repeat(MAX_ATTEMPTS) { attempt ->
            checkExecution(execution)
            if (insertIntoMediaStore(context, file, displayName, execution)) return true
            Log.w(TAG, "MediaStore insert failed index=$index attempt=${attempt + 1}/$MAX_ATTEMPTS")
            if (attempt < MAX_ATTEMPTS - 1) {
                sleepRetry(execution)
            }
        }
        fail("MEDIA_STORE_FAILED", "MediaStore insert failed index=$index name=$displayName")
        return false
    }

    private fun insertIntoMediaStore(
        context: Context,
        file: File,
        displayName: String,
        execution: TaskExecutionContext?,
    ): Boolean {
        checkExecution(execution)
        val header = readPrefix(file, 16)
        if (!MediaImageBytes.looksLikeImage(header)) {
            Log.w(TAG, "refuse MediaStore insert for non-image $displayName")
            return false
        }
        val mime = MediaImageBytes.mimeForBytes(header, displayName)
        val uri = GalleryMediaWriter.insertImageFile(context, file, displayName, mime)
        return uri != null
    }

    private fun readPrefix(file: File, max: Int): ByteArray {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(max)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun copyCooperatively(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        execution: TaskExecutionContext?,
        maxBytes: Long = Long.MAX_VALUE,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            checkExecution(execution)
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            if (total > maxBytes) {
                throw java.io.IOException("download exceeded maxBytes=$maxBytes")
            }
            output.write(buffer, 0, count)
        }
    }

    private fun checkExecution(execution: TaskExecutionContext?) {
        execution?.check()
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("media preparation interrupted")
        }
    }

    private fun sleepRetry(execution: TaskExecutionContext?) {
        checkExecution(execution)
        try {
            Thread.sleep(RETRY_SLEEP_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedException("media preparation interrupted")
        }
        checkExecution(execution)
    }

    private fun boundedTimeout(execution: TaskExecutionContext?, maximumMs: Int): Int {
        val remaining = execution?.remainingMillis() ?: return maximumMs
        execution.check()
        // Keep a usable floor so multi-image prep is not starved by a near-deadline clock.
        return remaining.coerceIn(5_000L, maximumMs.toLong()).toInt()
    }

    private fun fail(code: String, detail: String) {
        lastError.set(code)
        Log.w(TAG, "$code: $detail")
    }
}
