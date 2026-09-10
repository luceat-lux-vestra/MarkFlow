package com.algorist.markflow.editor.native

import com.algorist.markflow.trust.NativeLocalImageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImageImportPolicyTest {
    @Test
    fun encodesUtf8PathSegmentsAndRoundTripsThroughNativeTargetDecoder() {
        val relative = "images/한 글+plus_(1).png"
        val encoded = NativeImageImportPolicy.encodeRelativeTarget(relative)

        assertEquals("images/%ED%95%9C%20%EA%B8%80%2Bplus_%281%29.png", encoded)
        assertEquals(relative, NativeLocalImageResolver.decodeRelativeTarget(encoded))
    }

    @Test
    fun sanitizesCharactersThatCouldEscapeOrConfuseThePresentationTarget() {
        val sanitized = NativeImageImportPolicy.sanitizeFilename("../bad%2e?#\\name.png")

        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
        assertFalse(sanitized.contains('%'))
        assertFalse(sanitized.contains('?'))
        assertFalse(sanitized.contains('#'))
        assertTrue(sanitized.endsWith(".png"))
        val target = "assets/${NativeImageImportPolicy.encodePathSegment(sanitized)}"
        assertEquals("assets/$sanitized", NativeLocalImageResolver.decodeRelativeTarget(target))
    }

    @Test
    fun avoidsPortableReservedNamesAndFallsBackForEmptyNames() {
        assertEquals("image-CON.png", NativeImageImportPolicy.sanitizeFilename("CON.png"))
        assertEquals("image.png", NativeImageImportPolicy.sanitizeFilename("%%%.png"))
    }

    @Test
    fun allocatesCollisionSuffixesDeterministicallyAndCaseInsensitively() {
        val reserved = setOf("diagram.png", "diagram-2.png", "other.png")
        assertEquals("diagram-3.png", NativeImageImportPolicy.firstAvailableName("diagram.png", reserved))
        assertEquals("Diagram-3.png", NativeImageImportPolicy.firstAvailableName("Diagram.png", reserved))
        assertEquals("fresh.png", NativeImageImportPolicy.firstAvailableName("fresh.png", reserved))
    }

    @Test
    fun derivesSafeAltTextFromOriginalFilenameRatherThanDestinationName() {
        assertEquals("my\\[diagram\\]", NativeImageImportPolicy.altTextFromFilename("my[diagram].png"))
        assertEquals("a\\\\b", NativeImageImportPolicy.altTextFromFilename("a\\b.jpg"))
        assertEquals("image", NativeImageImportPolicy.altTextFromFilename("\n.png"))
    }
}
