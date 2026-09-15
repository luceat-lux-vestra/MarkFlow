package com.algorist.markflow.editor.native

import junit.framework.TestCase

class NativeRawHtmlSanitizerTest : TestCase() {
    fun testSafeStaticHtmlRendersWithoutSourceAttributesOrCapabilities() {
        val source = """<div class="callout"><p>Safe <span data-kind="safe">content</span>.</p></div>"""
        val result = NativeRawHtmlSanitizer.sanitize(source)

        assertTrue(result is NativeRawHtmlSanitizationResult.Safe)
        val html = (result as NativeRawHtmlSanitizationResult.Safe).html
        assertTrue(html.contains("Safe"))
        assertTrue(html.contains("content"))
        assertTrue(html.contains("<div>"))
        assertTrue(html.contains("<span>"))
        assertFalse(html.contains("class="))
        assertFalse(html.contains("data-kind="))
    }

    fun testScriptFailsClosedWithoutEchoingHostileSource() {
        val source = "<script>window.markflowHostile = true;</script>"
        val result = NativeRawHtmlSanitizer.sanitize(source)

        assertBlocked(result)
        assertFalse((result as NativeRawHtmlSanitizationResult.Blocked).code.contains("markflowHostile"))
    }

    fun testEventHandlerAndResourceElementFailClosed() {
        assertBlocked(NativeRawHtmlSanitizer.sanitize("<img src=\"image.png\" onerror=\"alert(1)\">"))
        assertBlocked(NativeRawHtmlSanitizer.sanitize("<div onclick=\"alert(1)\">x</div>"))
    }

    fun testStyleAndNavigationCapabilitiesFailClosed() {
        assertBlocked(
            NativeRawHtmlSanitizer.sanitize(
                "<div style=\"background-image:url(javascript:alert(1))\">x</div>"
            )
        )
        assertBlocked(NativeRawHtmlSanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>"))
        assertBlocked(NativeRawHtmlSanitizer.sanitize("<a href=\"https://example.com\">x</a>"))
    }

    fun testEmbeddedAndFormCapabilitiesFailClosed() {
        listOf(
            "<iframe src=\"https://example.com\"></iframe>",
            "<object data=\"payload\"></object>",
            "<form action=\"https://example.com\"><input></form>",
            "<svg><script>alert(1)</script></svg>",
        ).forEach { source -> assertBlocked(NativeRawHtmlSanitizer.sanitize(source)) }
    }

    fun testSourceAndOutputAreBounded() {
        val oversized = "x".repeat(NativeRawHtmlSanitizer.MAX_SOURCE_CHARS + 1)
        val result = NativeRawHtmlSanitizer.sanitize(oversized)

        assertTrue(result is NativeRawHtmlSanitizationResult.Blocked)
        assertEquals("SOURCE_TOO_LARGE", (result as NativeRawHtmlSanitizationResult.Blocked).code)
    }

    fun testMalformedOrUnsupportedTagFailsClosedRatherThanGuessing() {
        assertBlocked(NativeRawHtmlSanitizer.sanitize("<marquee>legacy</marquee>"))
    }

    private fun assertBlocked(result: NativeRawHtmlSanitizationResult) {
        assertTrue("expected blocked preview, got $result", result is NativeRawHtmlSanitizationResult.Blocked)
    }
}
