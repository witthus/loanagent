package com.loanagent.agent

/**
 * Detects whether downloaded bytes are a real image (not HTML/ICP block pages).
 */
object MediaImageBytes {
    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        // JPEG
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return true
        }
        // PNG
        if (
            bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return true
        }
        // GIF
        if (
            bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            return true
        }
        // WebP: RIFF....WEBP
        if (
            bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return true
        }
        return false
    }

    fun mimeForBytes(bytes: ByteArray, fallbackFilename: String): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
            return "image/png"
        }
        if (bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
            return "image/gif"
        }
        if (bytes.size >= 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte()) {
            return "image/webp"
        }
        val lower = fallbackFilename.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
    }

    fun contentTypeLooksLikeImage(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return true // some CDNs omit; rely on magic
        val primary = contentType.substringBefore(';').trim().lowercase()
        return primary.startsWith("image/")
    }
}
