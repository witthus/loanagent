package com.loanagent.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

class DiagnosticCache(context: android.content.Context) {
    val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    fun newFile(prefix: String, extension: String): File {
        deleteExpired()
        return File(directory, "${prefix}-${System.currentTimeMillis()}.$extension")
    }

    fun writeSnapshot(snapshot: UiSnapshot): File {
        val file = newFile("snapshot", "json")
        file.writeText(SnapshotJson.encode(snapshot), Charsets.UTF_8)
        return file
    }

    fun deleteExpired(
        nowMillis: Long = System.currentTimeMillis(),
        expiryMillis: Long = EXPIRY_MILLIS,
    ) {
        directory.listFiles()?.forEach { file ->
            if (nowMillis - file.lastModified() > expiryMillis) file.delete()
        }
    }

    fun clear(): Int {
        val files = directory.listFiles().orEmpty()
        files.forEach(File::delete)
        return files.size
    }

    fun isManagedFile(path: String): Boolean = try {
        val canonicalDirectory = directory.canonicalPath + File.separator
        File(path).canonicalPath.startsWith(canonicalDirectory)
    } catch (_: java.io.IOException) {
        false
    }

    companion object {
        const val DIRECTORY = "m0-diagnostics"
        private const val EXPIRY_MILLIS = 24 * 60 * 60 * 1_000L
    }
}

object SnapshotJson {
    fun encode(snapshot: UiSnapshot): String = buildString {
        append('{')
        field("schema_version", "m0-1")
        append(',')
        field("package", snapshot.packageName)
        append(',')
        field("class", snapshot.className)
        append(',')
        field("page_hint", snapshot.pageHint.name)
        append(",\"key_elements\":[")
        snapshot.keyElements.forEachIndexed { index, value ->
            if (index > 0) append(',')
            summary(value)
        }
        append("],\"truncated\":${snapshot.truncated}")
        append(",\"nodes\":[")
        snapshot.nodes.forEachIndexed { index, node ->
            if (index > 0) append(',')
            append('{')
            nullableField("view_id", node.viewId)
            append(',')
            append("\"text_summary\":")
            summary(if (node.password) "[REDACTED_PASSWORD]" else node.text)
            append(',')
            append("\"content_description_summary\":")
            summary(if (node.password) "[REDACTED_PASSWORD]" else node.contentDescription)
            append(',')
            nullableField("class", node.className)
            append(",\"clickable\":${node.clickable},\"editable\":${node.editable}")
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.field(name: String, value: String) {
        quoted(name)
        append(':')
        quoted(value)
    }

    private fun StringBuilder.nullableField(name: String, value: String?) {
        quoted(name)
        append(':')
        if (value == null) append("null") else quoted(value)
    }

    private fun StringBuilder.summary(value: String?) {
        if (value == null) {
            append("null")
            return
        }
        val redacted = SensitiveTextRedactor().redact(value, value == "[REDACTED_PASSWORD]").orEmpty()
        val classificationTerms = M0PageRules.ordered.asSequence()
            .flatMap { it.anyTerms.asSequence() }
            .filter(redacted::contains)
            .distinct()
            .take(3)
            .toList()
        append('{')
        field(
            "masked",
            if (classificationTerms.isEmpty()) {
                "[TEXT_REDACTED:length=${redacted.length}]"
            } else {
                classificationTerms.joinToString("|")
            },
        )
        append(',')
        field("sha256", sha256(redacted).take(16))
        append('}')
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun StringBuilder.quoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

class DiagnosticExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "json" -> "application/json"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only provider")
        val file = resolve(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(file.name, file.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun resolve(uri: Uri): File {
        val context = context ?: throw FileNotFoundException("Provider unavailable")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("Missing file")
        if (name.contains('/') || name.contains('\\')) throw FileNotFoundException("Invalid file")
        val file = File(DiagnosticCache(context).directory, name)
        if (!file.isFile || !DiagnosticCache(context).isManagedFile(file.path)) {
            throw FileNotFoundException("Unknown diagnostic")
        }
        return file
    }
}
