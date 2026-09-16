package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage

class NativeRawHtmlRendererTest : BasePlatformTestCase() {
    fun testSanitizedStaticHtmlRasterizesToBoundedInertImage() {
        val source = """<div class="callout"><p>Safe <strong>content</strong>.</p></div>"""
        val sanitized = NativeRawHtmlSanitizer.sanitize(source)
        assertTrue(sanitized is NativeRawHtmlSanitizationResult.Safe)
        val html = (sanitized as NativeRawHtmlSanitizationResult.Safe).html
        assertFalse(html.contains("class="))
        assertFalse(html.contains("href="))
        assertFalse(html.contains("src="))
        assertFalse(html.contains("style="))

        val image = onEdtResult { NativeSwingRawHtmlRenderer.rasterizeSanitized(html) }

        assertTrue(image.width in 1..NativeSwingRawHtmlRenderer.MAX_WIDTH)
        assertTrue(image.height in 1..NativeSwingRawHtmlRenderer.MAX_HEIGHT)
        assertTrue(image.width.toLong() * image.height.toLong() <= NativeSwingRawHtmlRenderer.MAX_PIXELS)
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.type)
    }

    fun testBlockedHostileHtmlNeverProducesRasterInput() {
        listOf(
            "<script>alert(1)</script>",
            "<img src=\"https://example.com/x.png\">",
            "<div onclick=\"alert(1)\">x</div>",
            "<div style=\"background:url(https://example.com/x)\">x</div>",
            "<a href=\"https://example.com\">x</a>",
        ).forEach { source ->
            assertTrue(
                "hostile source was not rejected before rasterization: $source",
                NativeRawHtmlSanitizer.sanitize(source) is NativeRawHtmlSanitizationResult.Blocked,
            )
        }
    }

    private fun <T> onEdtResult(action: () -> T): T {
        var result: T? = null
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            result = action()
        } else {
            application.invokeAndWait { result = action() }
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
