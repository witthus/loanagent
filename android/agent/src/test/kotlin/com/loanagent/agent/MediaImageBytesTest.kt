package com.loanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImageBytesTest {
    @Test
    fun acceptsJpegPngGifWebpMagic() {
        assertTrue(MediaImageBytes.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(
            MediaImageBytes.looksLikeImage(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                ),
            ),
        )
        assertTrue(MediaImageBytes.looksLikeImage("GIF89a".toByteArray()))
        val webp = ByteArray(12)
        "RIFF".toByteArray().copyInto(webp, 0)
        "WEBP".toByteArray().copyInto(webp, 8)
        assertTrue(MediaImageBytes.looksLikeImage(webp))
    }

    @Test
    fun rejectsHtmlAndEmpty() {
        assertFalse(MediaImageBytes.looksLikeImage(ByteArray(0)))
        assertFalse(MediaImageBytes.looksLikeImage("<!DOCTYPE html>".toByteArray()))
        assertFalse(MediaImageBytes.contentTypeLooksLikeImage("text/html"))
        assertTrue(MediaImageBytes.contentTypeLooksLikeImage("image/jpeg"))
        assertTrue(MediaImageBytes.contentTypeLooksLikeImage(null))
    }
}
