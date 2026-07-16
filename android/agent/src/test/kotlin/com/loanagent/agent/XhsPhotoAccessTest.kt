package com.loanagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XhsPhotoAccessTest {
    @Test
    fun acceptsReadMediaImages() {
        assertTrue(
            XhsPhotoAccess.isGranted(setOf("android.permission.READ_MEDIA_IMAGES")),
        )
    }

    @Test
    fun acceptsLegacyStorage() {
        assertTrue(
            XhsPhotoAccess.isGranted(setOf("android.permission.READ_EXTERNAL_STORAGE")),
        )
    }

    @Test
    fun rejectsPartialOrVideoOnly() {
        assertFalse(
            XhsPhotoAccess.isGranted(
                setOf("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"),
            ),
        )
        assertFalse(
            XhsPhotoAccess.isGranted(setOf("android.permission.READ_MEDIA_VIDEO")),
        )
    }

    @Test
    fun rejectsUnrelatedOrEmpty() {
        assertFalse(XhsPhotoAccess.isGranted(emptySet()))
        assertFalse(XhsPhotoAccess.isGranted(setOf("android.permission.CAMERA")))
    }

    @Test
    fun isGrantedByCheckUsesPredicate() {
        assertTrue(
            XhsPhotoAccess.isGrantedByCheck { it == "android.permission.READ_MEDIA_IMAGES" },
        )
        assertFalse(XhsPhotoAccess.isGrantedByCheck { false })
    }
}
