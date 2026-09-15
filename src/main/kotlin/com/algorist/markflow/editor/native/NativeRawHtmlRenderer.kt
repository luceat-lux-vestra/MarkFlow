package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JEditorPane
import javax.swing.UIManager

internal sealed interface NativeRawHtmlRenderResult {
    data class Success(val image: BufferedImage) : NativeRawHtmlRenderResult
    data class Blocked(val code: String) : NativeRawHtmlRenderResult
    data class Failure(val code: String) : NativeRawHtmlRenderResult
}

internal fun interface NativeRawHtmlRenderer {
    fun render(source: String, callback: (NativeRawHtmlRenderResult) -> Unit)
}

/**
 * Browser-free #149 renderer.
 *
 * Sanitization happens off the IDE UI thread. Only already-sanitized, capability-free HTML reaches
 * the Swing HTML renderer, where it is painted into a bounded inert raster on the EDT. No URL,
 * filesystem, navigation, script, style or browser execution authority is available to the source.
 */
internal object NativeSwingRawHtmlRenderer : NativeRawHtmlRenderer {
    override fun render(source: String, callback: (NativeRawHtmlRenderResult) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            when (val sanitized = NativeRawHtmlSanitizer.sanitize(source)) {
                is NativeRawHtmlSanitizationResult.Blocked -> {
                    callback(NativeRawHtmlRenderResult.Blocked(sanitized.code))
                }
                is NativeRawHtmlSanitizationResult.Safe -> {
                    ApplicationManager.getApplication().invokeLater {
                        val result = runCatching { rasterize(sanitized.html) }
                            .fold(
                                onSuccess = { image -> NativeRawHtmlRenderResult.Success(image) },
                                onFailure = { NativeRawHtmlRenderResult.Failure("RASTER_FAILED") },
                            )
                        callback(result)
                    }
                }
            }
        }
    }

    private fun rasterize(sanitizedHtml: String): BufferedImage {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val pane = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            isOpaque = false
            border = null
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = UIManager.getFont("TextArea.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 14)
            text = "<html><body>$sanitizedHtml</body></html>"
            setSize(MAX_WIDTH, MAX_HEIGHT)
        }
        val preferred = pane.preferredSize
        val width = preferred.width.coerceAtLeast(1)
        val height = preferred.height.coerceAtLeast(1)
        if (width > MAX_WIDTH || height > MAX_HEIGHT || width.toLong() * height.toLong() > MAX_PIXELS) {
            error("raw HTML raster exceeds bounded artifact dimensions")
        }
        pane.setSize(width, height)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            pane.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    internal const val MAX_WIDTH = 1200
    internal const val MAX_HEIGHT = 4096
    internal const val MAX_PIXELS = 8L * 1024L * 1024L
}
