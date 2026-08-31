package com.algorist.markflow.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit tests for [FontFamilyOptions]: the pure logic that builds and resolves the font-family
 * dropdown. Takes installed families as input so it does not depend on the fonts present on the
 * host machine (spec #18).
 */
class FontFamilyOptionsTest : BasePlatformTestCase() {

    fun testBuildPutsDefaultFirstThenSortsAlphabeticallyCaseInsensitively() {
        val options = FontFamilyOptions.build(listOf("Roboto", "Arial", "Inter"))
        assertEquals(
            listOf(
                FontFamilyOption.DEFAULT,
                FontFamilyOption("Arial", "Arial"),
                FontFamilyOption("Inter", "Inter"),
                FontFamilyOption("Roboto", "Roboto"),
            ),
            options
        )
    }

    fun testBuildRemovesDuplicatesAndBlankNames() {
        val options = FontFamilyOptions.build(listOf("Inter", "Inter", "  ", "Arial"))
        // Exact duplicates collapse to one; blank names are dropped; result is sorted.
        assertEquals(
            listOf(
                FontFamilyOption.DEFAULT,
                FontFamilyOption("Arial", "Arial"),
                FontFamilyOption("Inter", "Inter"),
            ),
            options
        )
    }

    fun testBuildWithEmptyInstalledListYieldsOnlyDefault() {
        val options = FontFamilyOptions.build(emptyList())
        assertEquals(listOf(FontFamilyOption.DEFAULT), options)
    }

    fun testResolveMatchesPersistedFamilyCaseSensitively() {
        val options = FontFamilyOptions.build(listOf("Roboto", "Arial", "Inter"))
        val resolved = FontFamilyOptions.resolve(options, "Inter")
        assertEquals(FontFamilyOption("Inter", "Inter"), resolved)
    }

    fun testResolveEmptyOrUnknownFallsBackToDefault() {
        val options = FontFamilyOptions.build(listOf("Roboto", "Arial", "Inter"))
        // Empty (MarkFlow Default) and an installed-but-unavailable family both resolve to the default.
        assertEquals(FontFamilyOption.DEFAULT, FontFamilyOptions.resolve(options, ""))
        assertEquals(FontFamilyOption.DEFAULT, FontFamilyOptions.resolve(options, "  "))
        assertEquals(FontFamilyOption.DEFAULT, FontFamilyOptions.resolve(options, "Nonexistent Font"))
    }
}
