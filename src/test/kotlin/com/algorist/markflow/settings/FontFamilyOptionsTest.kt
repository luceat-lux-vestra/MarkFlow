package com.algorist.markflow.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit tests for [FontFamilyOptions]: the pure logic that builds and resolves the font-family
 * dropdown. Takes installed families as input so it does not depend on the fonts present on the
 * host machine (spec #18).
 */
class FontFamilyOptionsTest : BasePlatformTestCase() {

    fun testBuildPutsIdeFontFirstUsingItsActualName() {
        val options = FontFamilyOptions.build(listOf("Roboto", "Arial", "Inter"), "JetBrains Mono")
        assertEquals(
            listOf(
                FontFamilyOption("", "JetBrains Mono"),
                FontFamilyOption("Arial", "Arial"),
                FontFamilyOption("Inter", "Inter"),
                FontFamilyOption("Roboto", "Roboto"),
            ),
            options
        )
    }

    fun testBuildRemovesDuplicatesAndBlankNames() {
        val options = FontFamilyOptions.build(
            listOf("Inter", "Inter", "  ", "Arial", "jetbrains mono"),
            "JetBrains Mono"
        )
        // The IDE default is not repeated in the installed-font list; exact duplicates still collapse.
        assertEquals(
            listOf(
                FontFamilyOption("", "JetBrains Mono"),
                FontFamilyOption("Arial", "Arial"),
                FontFamilyOption("Inter", "Inter"),
            ),
            options
        )
    }

    fun testBuildWithEmptyInstalledListYieldsOnlyDefault() {
        val options = FontFamilyOptions.build(emptyList(), "JetBrains Mono")
        assertEquals(listOf(FontFamilyOption("", "JetBrains Mono")), options)
    }

    fun testResolveMatchesPersistedFamilyCaseSensitively() {
        val options = FontFamilyOptions.build(listOf("Roboto", "Arial", "Inter"), "JetBrains Mono")
        val resolved = FontFamilyOptions.resolve(options, "Inter")
        assertEquals(FontFamilyOption("Inter", "Inter"), resolved)
    }

    fun testResolveEmptyOrUnknownFallsBackToIdeDefault() {
        val options = FontFamilyOptions.build(listOf("Roboto", "Arial", "Inter"), "JetBrains Mono")
        val expected = FontFamilyOption("", "JetBrains Mono")
        assertEquals(expected, FontFamilyOptions.resolve(options, ""))
        assertEquals(expected, FontFamilyOptions.resolve(options, "  "))
        assertEquals(expected, FontFamilyOptions.resolve(options, "Nonexistent Font"))
    }
}
