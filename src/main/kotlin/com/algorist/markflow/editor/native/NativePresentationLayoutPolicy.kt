package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.Editor
import kotlin.math.max

/**
 * Shared live-editor authority for native presentation spacing and available width.
 *
 * This policy deliberately stores no derived pixel values. Every query reads the current editor
 * font, tab settings and layout so font/theme/DPI/viewport changes cannot leave stale geometry.
 * Renderer-specific intrinsic content measurement stays with the renderer.
 */
internal class NativePresentationLayoutPolicy(
    private val editor: Editor,
) {
    fun typographicGutter(): Int {
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        val spaceWidth = max(1, metrics.charWidth(' '))
        return max(spaceWidth, metrics.height / 2)
    }

    fun indentPixels(depth: Int): Int {
        require(depth >= 0)
        val metrics = editor.contentComponent.getFontMetrics(editor.contentComponent.font)
        val spaceWidth = max(1, metrics.charWidth(' '))
        val tabColumns = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        val tabWidth = spaceWidth * tabColumns
        return depth * (tabWidth + typographicGutter())
    }

    fun availableContentWidth(
        horizontalInset: Int,
        intrinsicWidth: () -> Int,
    ): Int {
        require(horizontalInset >= 0)
        val viewportWidth = editor.scrollingModel.visibleArea.width
        val componentWidth = editor.contentComponent.width
        return resolveNativePresentationWidth(
            viewportWidth = viewportWidth,
            componentWidth = componentWidth,
            horizontalInset = horizontalInset,
            intrinsicWidth = intrinsicWidth(),
        )
    }
}

internal fun resolveNativePresentationWidth(
    viewportWidth: Int,
    componentWidth: Int,
    horizontalInset: Int,
    intrinsicWidth: Int,
): Int {
    require(horizontalInset >= 0)
    val measuredWidth = when {
        viewportWidth > 0 -> viewportWidth
        componentWidth > 0 -> componentWidth
        else -> 0
    }
    return if (measuredWidth > 0) {
        max(1, measuredWidth - horizontalInset * 2)
    } else {
        max(1, intrinsicWidth)
    }
}
