package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.border.Border

/**
 * Research-only #353 source-neutral page typography experiment.
 *
 * The production editor/controller never installs this owner. It cannot be
 * promoted solely because it resembles a screenshot: a whole-editor font is
 * necessarily also used by code spans, fenced code and editable source text,
 * whereas JCEF uses distinct per-element fonts. Use this as a feasibility
 * boundary for source-preserving layout, not a production Markdown renderer.
 *
 * Every mutation is local to one EditorEx, and dispose restores the exact
 * scheme and border objects. Never touch the shared global color scheme or
 * any platform-owned folding region.
 */
internal class NativePageGeometryPilot private constructor(
    private val editor: EditorEx,
    private val originalScheme: EditorColorsScheme,
    private val originalBorder: Border?,
    private val paragraphSpacing: List<Inlay<*>>,
) : Disposable {
    private var disposed = false

    data class Measurements(
        val fontFamily: String,
        val fontSizePx: Int,
        val lineHeightPx: Int,
        val leftInsetPx: Int,
        val topInsetPx: Int,
        val paragraphSpacerCount: Int,
    )

    fun measurements(): Measurements {
        check(!disposed)
        val insets = editor.insets
        return Measurements(
            editor.colorsScheme.editorFontName,
            editor.colorsScheme.editorFontSize,
            editor.lineHeight,
            insets.left,
            insets.top,
            paragraphSpacing.count { it.isValid },
        )
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        paragraphSpacing.asReversed().forEach { it.takeIf(Inlay<*>::isValid)?.dispose() }
        if (editor.isDisposed) return
        // Restore local formatting even when a test fails after installation.
        editor.setBorder(originalBorder)
        editor.setColorsScheme(originalScheme)
    }

    companion object {
        // Genuine 2026.2.3 bundled JCEF computed paragraph font-size=13px;
        // box-left=26px in the pinned A/B capture at DPR=1.
        private const val BUNDLED_PARAGRAPH_FONT_PX = 13
        private const val REFERENCE_LEFT_INSET_PX = 26
        // Top inset is an explicit experimental variable, NOT an accepted
        // global 26px parity rule. The screenshot's image origin includes IDE
        // editor/preview chrome and cannot be equated with CSS page padding.
        private const val EXPERIMENTAL_TOP_INSET_PX = 26
        private const val EXPERIMENTAL_PARAGRAPH_GAP_PX = 16

        fun install(editor: Editor): NativePageGeometryPilot? {
            ApplicationManager.getApplication().assertIsDispatchThread()
            if (editor !is EditorEx || editor.isDisposed || editor.isViewer) return null

            // Fail closed on mixed code: changing one editor-wide font to
            // proportional must not silently destroy monospaced code layout.
            val source = editor.document.immutableCharSequence.toString()
            if (source.lineSequence().any {
                val line = it.trimStart()
                line.startsWith("```") || line.startsWith("~~~") ||
                    it.startsWith("    ") || it.startsWith("\t")
            }) return null

            val stamp = editor.document.modificationStamp
            val originalCaret = editor.caretModel.offset
            val originalScheme = editor.colorsScheme
            val originalBorder = editor.contentComponent.border
            val foreignFolds = editor.foldingModel.allFoldRegions.toList()
            val foreignStates = foreignFolds.map { Triple(it.startOffset, it.endOffset, it.isExpanded) }
            val paragraphSpacing = mutableListOf<Inlay<*>>()
            try {
                // Clone first, then alter ONLY the clone for this editor.
                val clone = originalScheme.clone() as EditorColorsScheme
                clone.editorFontName = bundledSansFamily()
                clone.editorFontSize = BUNDLED_PARAGRAPH_FONT_PX
                editor.setColorsScheme(clone)
                editor.setBorder(BorderFactory.createEmptyBorder(
                    EXPERIMENTAL_TOP_INSET_PX, REFERENCE_LEFT_INSET_PX, 0, 0,
                ))

                // Narrow research-only block model: one non-empty plain
                // paragraph after a blank line, followed by a blank line and
                // an ATX heading. Inlay is non-painting and MarkFlow-owned.
                // Do not attempt arbitrary HTML/CSS margin collapsing here.
                val doc = editor.document
                for (line in 2 until doc.lineCount - 2) {
                    val text = doc.getText(com.intellij.openapi.util.TextRange(
                        doc.getLineStartOffset(line), doc.getLineEndOffset(line)
                    ))
                    val before = doc.getLineStartOffset(line - 1) == doc.getLineEndOffset(line - 1)
                    val after = doc.getLineStartOffset(line + 1) == doc.getLineEndOffset(line + 1)
                    val nextStart = doc.getLineStartOffset(line + 2)
                    val nextEnd = doc.getLineEndOffset(line + 2)
                    val nextText = doc.getText(com.intellij.openapi.util.TextRange(nextStart, nextEnd))
                    if (before && after && text.startsWith("Paragraph after H") &&
                        nextText.startsWith("#") && nextText.dropWhile { it == '#' }.startsWith(" ")) {
                        val extra = editor.inlayModel.addBlockElement(
                            doc.getLineEndOffset(line), true, false, 0, NativePageParagraphSpacing
                        ) ?: error("Paragraph spacing inlay rejected by IDEA")
                        paragraphSpacing += extra
                    }
                }

                check(editor.document.modificationStamp == stamp && editor.document.text == source)
                check(editor.caretModel.offset == originalCaret)
                check(foreignFolds == editor.foldingModel.allFoldRegions.toList())
                check(foreignFolds.map { Triple(it.startOffset, it.endOffset, it.isExpanded) } == foreignStates)
                return NativePageGeometryPilot(editor, originalScheme, originalBorder, paragraphSpacing)
            } catch (failure: Throwable) {
                paragraphSpacing.asReversed().forEach { it.takeIf(Inlay<*>::isValid)?.dispose() }
                if (!editor.isDisposed) {
                    editor.setBorder(originalBorder)
                    editor.setColorsScheme(originalScheme)
                }
                throw failure
            }
        }

        private fun bundledSansFamily(): String {
            val families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames.toSet()
            return listOf("Helvetica", "Arial", "FreeSans", "Liberation Sans", "Arimo", "SansSerif")
                .firstOrNull { it in families } ?: "SansSerif"
        }
    }
}

/** Adds only a measurable non-painting paragraph margin in the research pilot. */
internal object NativePageParagraphSpacing : com.intellij.openapi.editor.EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 1
    override fun calcHeightInPixels(inlay: Inlay<*>): Int = 16
    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) = Unit
}
