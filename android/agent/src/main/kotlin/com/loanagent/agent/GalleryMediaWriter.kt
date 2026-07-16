package com.loanagent.agent

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/**
 * Shared MediaStore write path used by publish prep and status-page self-check.
 * Writes into [Environment.DIRECTORY_DCIM]/Camera (same as MediaBridge).
 */
object GalleryMediaWriter {
    private const val TAG = "GalleryMediaWriter"

    fun insertImageBytes(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "image/png",
    ): Uri? {
        if (bytes.isEmpty()) return null
        return insertWithWriter(context, displayName, mimeType) { output ->
            output.write(bytes)
        }
    }

    fun insertImageFile(
        context: Context,
        file: java.io.File,
        displayName: String,
        mimeType: String,
    ): Uri? {
        if (!file.exists() || file.length() == 0L) return null
        return insertWithWriter(context, displayName, mimeType) { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
    }

    private fun insertWithWriter(
        context: Context,
        displayName: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
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
        val uri = resolver.insert(collection, values) ?: run {
            Log.w(TAG, "insert returned null for $displayName")
            return null
        }
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                write(output)
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (error: Exception) {
            Log.w(TAG, "insert failed for $displayName", error)
            resolver.delete(uri, null, null)
            null
        }
    }

    fun findByDisplayName(context: Context, displayName: String): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME}=?"
        context.contentResolver.query(
            collection,
            projection,
            selection,
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(0)
            return ContentUris.withAppendedId(collection, id)
        }
        return null
    }

    fun deleteUri(context: Context, uri: Uri): Boolean =
        try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (error: Exception) {
            Log.w(TAG, "delete failed $uri", error)
            false
        }
}
