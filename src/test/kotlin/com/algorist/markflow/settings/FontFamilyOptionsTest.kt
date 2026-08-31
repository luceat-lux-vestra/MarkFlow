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
                FontFamilyOption("", "IDE Default (JetBrains Mono)"),
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
        // The IDE default remains a separate inheritance option; exact installed-name duplicates
        // still collapse while the explicit font choice stays available.
        assertEquals(
            listOf(
                FontFamilyOption("", "IDE Default (JetBrains Mono)"),
                FontFamilyOption("Arial", "Arial"),
                FontFamilyOption("Inter", "Inter"),
                FontFamilyOption("jetbrains mono", "jetbrains mono"),
            ),
            options
        )
    }

    fun testBuildWithEmptyInstalledListYieldsOnlyDefault() {
        val options = FontFamilyOptions.build(emptyList(), "JetBrains Mono")
        assertEquals(listOf(FontFamilyOption("", "IDE Default (JetBrains Mono)")), options)
    }

    fun testExplicitIdeFontRemainsDistinctFromInheritanceOption() {
        val options = FontFamilyOptions.build(listOf("JetBrains Mono"), "JetBrains Mono")

        assertEquals(FontFamilyOption("", "IDE Default (JetBrains Mono)"), options.first())
        assertEquals(FontFamilyOption("JetBrains Mono", "JetBrains Mono"), options[1])
        assertEquals(FontFamilyOption("JetBrains Mono", "JetBrains Mono"), FontFamilyOptions.resolve(options, "JetBrains Mono"))
        assertEquals(FontFamilyOption("", "IDE Default (JetBrains Mono)"), FontFamilyOptions.resolve(options, ""))
    }

    fun testResolveMatchesPersistedFamilyIgnoringCase() {
        val options = FontFamilyOptions.build(listOf("Roboto", "Arial", "Inter"), "JetBrains Mono")
        val resolved = FontFamilyOptions.resolve(options, "inter")
        assertEquals(FontFamilyOption("Inter", "Inter"), resolved)
    }

    fun testResolveEmptyOrUnknownFallsBackToIdeDefault() {
        val options = FontFamilyOptions.build(listOf("Roboto", "Arial", "Inter"), "JetBrains Mono")
        val expected = FontFamilyOption("", "IDE Default (JetBrains Mono)")
        assertEquals(expected, FontFamilyOptions.resolve(options, ""))
        assertEquals(expected, FontFamilyOptions.resolve(options, "  "))
        assertEquals(expected, FontFamilyOptions.resolve(options, "Nonexistent Font"))
    }
}
