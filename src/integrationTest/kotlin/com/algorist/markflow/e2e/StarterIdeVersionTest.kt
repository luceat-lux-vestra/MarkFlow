package com.algorist.markflow.e2e

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class StarterIdeVersionTest {
    @Test
    fun recognizesPinnedEapBuildNumber() {
        assertTrue(isStarterEapBuildNumber("263.4732.28"))
    }

    @Test
    fun keepsStableReleaseOnReleaseChannel() {
        assertFalse(isStarterEapBuildNumber("2026.3"))
    }

    @Test
    fun prefersNativeStarterLauncherWhenBothLaunchersExist(@TempDir root: Path) {
        val bin = Files.createDirectories(root.resolve("bin"))
        val native = Files.createFile(bin.resolve("idea"))
        val shell = Files.createFile(bin.resolve("idea.sh"))
        assertTrue(native.toFile().setExecutable(true))
        assertTrue(shell.toFile().setExecutable(true))

        assertEquals(native.toRealPath(), starterIdeExecutable(root, "idea"))
    }

    @Test
    fun fallsBackToStarterShellLauncherWhenNativeLauncherIsUnavailable(@TempDir root: Path) {
        val bin = Files.createDirectories(root.resolve("bin"))
        val shell = Files.createFile(bin.resolve("idea.sh"))
        assertTrue(shell.toFile().setExecutable(true))

        assertEquals(shell.toRealPath(), starterIdeExecutable(root, "idea"))
    }

    @Test
    fun matchesOnlyTheExactStarterAppArmorProfile(@TempDir root: Path) {
        val executable = Files.createFile(root.resolve("idea")).toRealPath()
        assertTrue(starterAppArmorProfileMatches(
            "idea_apparmor_current=$executable (unconfined)\n",
            executable,
        ))
        assertTrue(starterAppArmorProfileMatches(
            "idea_apparmor_current=$executable\n",
            executable,
        ))
        assertFalse(starterAppArmorProfileMatches(
            "idea_apparmor_current=$executable-shadow (unconfined)\n",
            executable,
        ))
        assertFalse(starterAppArmorProfileMatches(
            "idea_apparmor_current=$executable (unconfined)\n" +
                "idea_apparmor_current=$executable (unconfined)\n",
            executable,
        ))
        assertFalse(starterAppArmorProfileMatches(
            "idea_apparmor_current=$executable ()\n",
            executable,
        ))
        assertFalse(starterAppArmorProfileMatches(
            "idea_apparmor_current=$executable (unconfined) trailing\n",
            executable,
        ))
        assertFalse(starterAppArmorProfileMatches(
            "idea_apparmor_current=$executable (unconfined)(shadow)\n",
            executable,
        ))
    }
}
