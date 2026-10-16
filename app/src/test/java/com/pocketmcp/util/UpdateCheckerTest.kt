package com.pocketmcp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun parsesValidManifest() {
        val manifest = UpdateChecker.parseManifest(
            """{"versionName":"1.0.0-beta.8","versionCode":8,"apkUrl":"http://192.0.2.10:8123/PocketMCP-debug.apk","notes":"faster"}"""
        )
        assertEquals("1.0.0-beta.8", manifest?.versionName)
        assertEquals(8, manifest?.versionCode)
        assertEquals("faster", manifest?.notes)
    }

    @Test
    fun ignoresUnknownFieldsAndMissingNotes() {
        val manifest = UpdateChecker.parseManifest(
            """{"versionName":"1.0.0-beta.9","versionCode":9,"apkUrl":"http://host/apk","extra":"x"}"""
        )
        assertEquals(9, manifest?.versionCode)
        assertNull(manifest?.notes)
    }

    @Test
    fun rejectsInvalidPayloads() {
        assertNull(UpdateChecker.parseManifest("not json"))
        assertNull(UpdateChecker.parseManifest("""{"versionName":"x","versionCode":0,"apkUrl":""}"""))
        assertNull(UpdateChecker.parseManifest("""{"versionName":"x","versionCode":5}"""))
    }

    @Test
    fun comparesVersionCodes() {
        val manifest = UpdateManifest("1.0.0-beta.8", 8, "http://host/apk")
        assertTrue(UpdateChecker.isNewer(manifest, 7))
        assertFalse(UpdateChecker.isNewer(manifest, 8))
        assertFalse(UpdateChecker.isNewer(manifest, 9))
    }
}
