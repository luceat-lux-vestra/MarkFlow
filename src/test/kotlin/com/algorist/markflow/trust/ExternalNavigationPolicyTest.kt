package com.algorist.markflow.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExternalNavigationPolicyTest {
    @Test
    fun acceptsOnlyAbsoluteHttpAndHttpsWithoutCredentials() {
        assertEquals("https://example.com/docs?q=1#part", ExternalNavigationPolicy.validateHttpUrl("https://example.com/docs?q=1#part")?.toString())
        assertEquals("http://example.com/", ExternalNavigationPolicy.validateHttpUrl("http://example.com/")?.toString())

        listOf(
            "javascript:alert(1)",
            "file:///tmp/a",
            "mailto:user@example.com",
            "//example.com/path",
            "/relative",
            "relative.md",
            "https://user:secret@example.com/",
            "https://example.com/has space",
            "https://example.com/\nnext",
            "https:///missing-host",
        ).forEach { raw -> assertNull(raw, ExternalNavigationPolicy.validateHttpUrl(raw)) }
    }

    @Test
    fun rejectsEmptyAndOversizeUrls() {
        assertNull(ExternalNavigationPolicy.validateHttpUrl(""))
        assertNotNull(ExternalNavigationPolicy.validateHttpUrl("https://example.com/"))
        assertNull(
            ExternalNavigationPolicy.validateHttpUrl(
                "https://example.com/" + "a".repeat(ExternalNavigationPolicy.MAX_URL_LENGTH)
            )
        )
    }
}
