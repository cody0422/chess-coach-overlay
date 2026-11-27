package com.chesscoach.overlay.vision

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestConfigTest {

    private fun resolveAppFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath)
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relativePath from cwd=${File(".").absolutePath}")
    }

    @Test
    fun accessibilityServiceMetaDataUsesCanonicalServiceMetaDataKey() {
        val manifest = resolveAppFile("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must declare android:name=\"android.accessibilityservice\" for ChessAccessibilityService",
            manifest.contains("android:name=\"android.accessibilityservice\"")
        )
        assertFalse(
            "AndroidManifest.xml must NOT contain doubled android.accessibilityservice.accessibilityservice",
            manifest.contains("android:name=\"android.accessibilityservice.accessibilityservice\"")
        )
        assertTrue(
            "AndroidManifest.xml must reference @xml/accessibility_service_config",
            manifest.contains("android:resource=\"@xml/accessibility_service_config\"")
        )
    }

    @Test
    fun accessibilityServiceConfigEnablesScreenshotsAndWindowContent() {
        val config = resolveAppFile("src/main/res/xml/accessibility_service_config.xml").readText()
        assertTrue(
            "accessibility_service_config.xml must declare android:canTakeScreenshot=\"true\"",
            config.contains("android:canTakeScreenshot=\"true\"")
        )
        assertTrue(
            "accessibility_service_config.xml must declare android:canRetrieveWindowContent=\"true\"",
            config.contains("android:canRetrieveWindowContent=\"true\"")
        )
    }

    @Test
    fun autoReadStatusSurfacesAccessibilityCaptureErrorBeforeFirstFrame() {
        val status = AutoReadStatusFormatter.formatStatus(
            autoReadEnabled = true,
            autoReadLive = true,
            hasMediaProjectionGrant = true,
            boardVisibleOrLocked = false,
            playingAsWhite = true,
            fullMoveNumber = 1,
            framesCaptured = 0L,
            bestScore = 0f,
            lastCaptureError = "No accessibility screenshot permission",
            tapInputMode = false,
            frameMaxLum = -1,
            usingAccessibilityMode = true
        )
        assertEquals("No-Red-Box error: No accessibility screenshot permission", status)
    }
}
