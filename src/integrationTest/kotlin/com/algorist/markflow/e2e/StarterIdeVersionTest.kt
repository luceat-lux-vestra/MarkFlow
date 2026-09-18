package com.algorist.markflow.e2e

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StarterIdeVersionTest {
    @Test
    fun recognizesPinnedEapBuildNumber() {
        assertTrue(isStarterEapBuildNumber("263.4732.28"))
    }

    @Test
    fun keepsStableReleaseOnReleaseChannel() {
        assertFalse(isStarterEapBuildNumber("2026.2.3"))
    }
}
