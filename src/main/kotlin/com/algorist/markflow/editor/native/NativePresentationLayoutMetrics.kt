package com.algorist.markflow.editor.native

import com.intellij.openapi.editor.Editor
import java.awt.FontMetrics
import kotlin.math.max

/**
 * Shared native presentation geometry derived from the active editor.
 *
 * Device pixels are never product policy here. The primitive authorities are the current editor
 * font metrics, line height, tab width and allocated viewport/component width.
 */
internal data class NativePresentationLayoutMetrics(
    val fontMetrics: FontMetrics,
    val lineHeight: Int,
    val spaceWidth: Int,
    val tabWidth: Int,
    val viewportWidth: Int,
) {
    val rowHeight: Int = max(lineHeight, fontMetrics.height)
    val rhythm: Int = max(spaceWidth, rowHeight / 2)
    val halfRhythm: Int = max(1, rhythm / 2)
    val horizontalPadding: Int = rhythm
    val verticalPadding: Int = rhythm
    val surfaceMargin: Int = halfRhythm
    val surfaceArc: Int = rhythm
    val markerGap: Int = rhythm
    val barWidth: Int = max(1, spaceWidth / 2)
    val nestedIndent: Int = tabWidth + rhythm

    fun surfaceWidth(): Int =
        max(rowHeight, viewportWidth - surfaceMargin * 2)

    fun tableCellMinimumWidth(ellipsisWidth: Int): Int =
        max(1, ellipsisWidth) + horizontalPadding * 2
}

internal fun nativePresentationLayoutMetrics(editor: Editor): NativePresentationLayoutMetrics {
    val component = editor.contentComponent
    val metrics = component.getFontMetrics(component.font)
    val spaceWidth = max(1, metrics.charWidth(' '))
    val tabColumns = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    val tabWidth = spaceWidth * tabColumns
    val fallbackWidth = max(editor.lineHeight, metrics.height)
    val viewportWidth = sequenceOf(
        editor.scrollingModel.visibleArea.width,
        component.visibleRect.width,
        component.width,
    ).firstOrNull { it > 0 } ?: fallbackWidth

    return NativePresentationLayoutMetrics(
        fontMetrics = metrics,
        lineHeight = editor.lineHeight,
        spaceWidth = spaceWidth,
        tabWidth = tabWidth,
        viewportWidth = viewportWidth,
    )
}
