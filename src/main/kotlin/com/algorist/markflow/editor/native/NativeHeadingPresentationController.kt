package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

internal data class NativeHeadingModel(
    val sourceRange: ProjectionRange,
    val contentRange: ProjectionRange,
    val level: Int,
    val text: String,
)

internal data class NativeHeadingPresentationEvidence(
    val models: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val levels: List<Int>,
    val accessibilityFallbacks: Long,
    val mouseReveals: Long,
)

/**
 * Converts only parser-proven simple headings into native rich presentation.
 *
 * Headings containing nested inline Markdown remain exact source in this slice. That fail-closed
 * boundary avoids rendering raw emphasis/link/code delimiters as heading text before a retained
 * inline-rich heading renderer exists.
 */
internal object NativeHeadingProjectionPlanner {
    private val nestedInlineKinds = setOf(
        NativeProjectionKind.EMPHASIS,
        NativeProjectionKind.STRONG,
        NativeProjectionKind.LINK,
        NativeProjectionKind.INLINE_CODE,
    )

    fun plan(plan: NativeProjectionPlan): List<NativeHeadingModel> {
        if (plan.status != ProjectionPlanStatus.READY) return emptyList()
        val source = plan.identity.source
        val projections = plan.projections
        return projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.HEADING }
            .mapNotNull { heading ->
                val level = heading.headingLevel?.takeIf { it in 1..6 } ?: return@mapNotNull null
                val contentRange = heading.contentRanges.singleOrNull() ?: return@mapNotNull null
                val hasNestedInline = projections.any { candidate ->
                    candidate.kind in nestedInlineKinds &&
                        candidate.sourceRange.startOffset >= heading.sourceRange.startOffset &&
                        candidate.sourceRange.endOffset <= heading.sourceRange.endOffset
                }
                if (hasNestedInline) return@mapNotNull null
                if (!contentRange.isInside(source)) return@mapNotNull null
                val text = source.substring(contentRange.startOffset, contentRange.endOffset)
                if (
                    text.isBlank() ||
                    text.contains('\n') ||
                    text.contains('\r') ||
                    text.any(::isPresentationSensitiveInlineChar)
                ) {
                    return@mapNotNull null
                }
                NativeHeadingModel(
                    sourceRange = heading.sourceRange,
                    contentRange = contentRange,
                    level = level,
                    text = text,
                )
            }
            .toList()
    }

    private fun isPresentationSensitiveInlineChar(char: Char): Boolean =
        char == '\\' ||
            char == '*' ||
            char == '_' ||
            char == '[' ||
            char == ']' ||
            char == '`' ||
            char == '<' ||
            char == '>' ||
            char == '&' ||
            char == '~'

    fun unsupportedSourceRanges(plan: NativeProjectionPlan): List<ProjectionRange> {
        if (plan.status != ProjectionPlanStatus.READY) return emptyList()
        val supported = plan(plan)
            .map { model -> model.sourceRange.startOffset to model.sourceRange.endOffset }
            .toSet()
        return plan.projections
            .asSequence()
            .filter { it.kind == NativeProjectionKind.HEADING }
            .filter { heading -> (heading.sourceRange.startOffset to heading.sourceRange.endOffset) !in supported }
            .map { it.sourceRange }
            .toList()
    }
}

/**
 * Source-neutral native heading presentation owned by one editor.
 *
 * Supported inactive headings conceal their exact source through owned folds and render a native
 * block inlay with H1-H6 typography. Active headings remove the rich presentation so the original
 * Markdown is visible and directly editable.
 */
internal class NativeHeadingPresentationController(
    private val editor: Editor,
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentModels = emptyList<NativeHeadingModel>()
    private val owned = LinkedHashMap<HeadingKey, OwnedHeadingPresentation>()
    private var accessibilityFallbacks = 0L
    private var mouseReveals = 0L
    private var disposed = false

    private val mouseListener = object : EditorMouseListener {
        override fun mouseClicked(event: EditorMouseEvent) {
            handleMouseReveal(event)
        }
    }

    init {
        ApplicationManager.getApplication().assertIsDispatchThread()
        editor.addEditorMouseListener(mouseListener)
    }

    fun applyPlan(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        requireAlive()
        ApplicationManager.getApplication().assertIsDispatchThread()
        clearOwned()
        currentPlanIdentity = plan.identity
        currentModels = NativeHeadingProjectionPlanner.plan(plan)
        if (plan.status != ProjectionPlanStatus.READY || currentModels.isEmpty()) return
        if (!richPresentationEnabled) {
            accessibilityFallbacks += currentModels.size
            return
        }
        currentModels.filterNot(::isActive).forEach { model -> installIfCurrent(plan.identity, model) }
    }

    fun refreshActivity(plan: NativeProjectionPlan, richPresentationEnabled: Boolean) {
        if (disposed || editor.isDisposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (plan.identity != currentPlanIdentity || !matchesCurrentIdentity(plan.identity)) return
        if (!richPresentationEnabled) {
            clearOwned()
            return
        }
        currentModels.forEach { model ->
            val key = HeadingKey.of(model)
            if (isActive(model)) removeOwned(key) else installIfCurrent(plan.identity, model)
        }
    }

    fun clearPresentation() {
        if (disposed) return
        ApplicationManager.getApplication().assertIsDispatchThread()
        clearOwned()
        currentModels = emptyList()
        currentPlanIdentity = null
    }

    fun evidenceSnapshot(): NativeHeadingPresentationEvidence = NativeHeadingPresentationEvidence(
        models = currentModels.size,
        ownedInlays = owned.values.count { it.inlay.isValid },
        ownedFolds = owned.values.sumOf { presentation -> presentation.folds.count(FoldRegion::isValid) },
        levels = currentModels.map(NativeHeadingModel::level),
        accessibilityFallbacks = accessibilityFallbacks,
        mouseReveals = mouseReveals,
    )

    internal fun handleMouseReveal(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val renderer = event.inlay?.renderer as? NativeHeadingInlayRenderer ?: return false
        val key = HeadingKey(renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false
        removeOwned(key)
        editor.selectionModel.removeSelection()
        editor.caretModel.primaryCaret.moveToOffset(renderer.contentOffset)
        mouseReveals += 1
        event.consume()
        return true
    }

    private fun installIfCurrent(identity: ProjectionSourceIdentity, model: NativeHeadingModel) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = HeadingKey.of(model)
        if (owned[key]?.let { it.inlay.isValid && it.folds.all(FoldRegion::isValid) } == true) return
        removeOwned(key)

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        val foldRanges = headingFoldRanges(model, sourceBefore) ?: return
        val installedFolds = mutableListOf<FoldRegion>()
        var foldsInstalled = true
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            for (range in foldRanges) {
                val fold = editor.foldingModel.addFoldRegion(
                    range.startOffset,
                    range.endOffset,
                    ZERO_WIDTH_PLACEHOLDER,
                )
                if (fold == null) {
                    foldsInstalled = false
                    break
                }
                installedFolds += fold
                fold.isExpanded = false
                if (fold.isExpanded) {
                    foldsInstalled = false
                    break
                }
            }
            if (!foldsInstalled) {
                installedFolds.asReversed().forEach { fold ->
                    if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
                }
                installedFolds.clear()
            }
        }
        if (!foldsInstalled || installedFolds.size != foldRanges.size) return

        val renderer = NativeHeadingInlayRenderer(editor, model)
        val inlay = editor.inlayModel.addBlockElement(
            model.sourceRange.endOffset,
            true,
            true,
            0,
            renderer,
        )
        if (inlay == null) {
            removeFolds(installedFolds)
            return
        }
        owned[key] = OwnedHeadingPresentation(installedFolds.toList(), inlay)

        check(editor.document.modificationStamp == stampBefore) {
            "native heading presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native heading presentation changed the authoritative Document source"
        }
    }

    private fun headingFoldRanges(model: NativeHeadingModel, source: String): List<ProjectionRange>? {
        val range = model.sourceRange
        if (!range.isInside(source)) return null
        val codePoints = source.codePointCount(range.startOffset, range.endOffset)
        if (codePoints < 2) return null
        val tailStart = Character.offsetByCodePoints(source, range.endOffset, -1)
        if (tailStart <= range.startOffset || tailStart >= range.endOffset) return null
        return listOf(
            ProjectionRange(range.startOffset, tailStart),
            ProjectionRange(tailStart, range.endOffset),
        )
    }

    private fun isCurrent(identity: ProjectionSourceIdentity): Boolean =
        currentPlanIdentity == identity && matchesCurrentIdentity(identity)

    private fun matchesCurrentIdentity(identity: ProjectionSourceIdentity): Boolean =
        ProjectionSnapshot.capture(editor.document, identity.configGeneration).identity == identity

    private fun isActive(model: NativeHeadingModel): Boolean {
        val range = model.sourceRange
        return editor.caretModel.allCarets.any { caret ->
            range.touches(caret.offset) ||
                (caret.hasSelection() && range.intersects(caret.selectionStart, caret.selectionEnd))
        }
    }

    private fun clearOwned() {
        owned.keys.toList().forEach(::removeOwned)
    }

    private fun removeOwned(key: HeadingKey) {
        val presentation = owned.remove(key) ?: return
        if (presentation.inlay.isValid) presentation.inlay.dispose()
        removeFolds(presentation.folds)
    }

    private fun removeFolds(folds: List<FoldRegion>) {
        if (editor.isDisposed || folds.none(FoldRegion::isValid)) return
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            folds.asReversed().forEach { fold ->
                if (fold.isValid) editor.foldingModel.removeFoldRegion(fold)
            }
        }
    }

    private fun requireAlive() {
        check(!disposed) { "native heading presentation controller is disposed" }
        check(!editor.isDisposed) { "native editor is disposed" }
    }

    override fun dispose() {
        if (disposed) return
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeAndWait { dispose() }
            return
        }
        editor.removeEditorMouseListener(mouseListener)
        clearOwned()
        currentModels = emptyList()
        currentPlanIdentity = null
        disposed = true
    }

    private data class HeadingKey(
        val startOffset: Int,
        val endOffset: Int,
    ) {
        companion object {
            fun of(model: NativeHeadingModel) = HeadingKey(model.sourceRange.startOffset, model.sourceRange.endOffset)
        }
    }

    private data class OwnedHeadingPresentation(
        val folds: List<FoldRegion>,
        val inlay: Inlay<*>,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
    }
}

internal class NativeHeadingInlayRenderer(
    private val editor: Editor,
    private val model: NativeHeadingModel,
) : EditorCustomElementRenderer {
    val sourceRange: ProjectionRange
        get() = model.sourceRange

    val contentOffset: Int
        get() = model.contentRange.startOffset

    val level: Int
        get() = model.level

    val displayText: String
        get() = model.text

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = headingFont()
        val metrics = editor.contentComponent.getFontMetrics(font)
        val available = max(1, editor.scrollingModel.visibleArea.width - HORIZONTAL_PADDING * 2)
        return min(available, max(1, metrics.stringWidth(model.text) + HORIZONTAL_PADDING * 2))
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val metrics = editor.contentComponent.getFontMetrics(headingFont())
        return metrics.height + verticalPadding(model.level) * 2
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g as? Graphics2D ?: return
        val font = headingFont()
        val metrics = g2.getFontMetrics(font)
        val available = max(1, targetRegion.width - HORIZONTAL_PADDING * 2)
        val rendered = clip(model.text, metrics, available)
        val baseline = targetRegion.y +
            max(metrics.ascent + verticalPadding(model.level), (targetRegion.height + metrics.ascent - metrics.descent) / 2)

        val previousTextHint = g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = font
        g2.color = textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
        g2.drawString(rendered, targetRegion.x + HORIZONTAL_PADDING, baseline)
        if (previousTextHint != null) {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, previousTextHint)
        }
    }

    private fun headingFont(): Font {
        val base = editor.contentComponent.font
        return base.deriveFont(Font.BOLD, base.size2D * nativeHeadingFontScale(model.level))
    }

    private fun clip(text: String, metrics: java.awt.FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        val target = max(0, maxWidth - metrics.stringWidth(ellipsis))
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > target) end -= 1
        return text.substring(0, end) + ellipsis
    }

    private fun verticalPadding(level: Int): Int = when (level) {
        1 -> 10
        2 -> 8
        3 -> 7
        4 -> 6
        5 -> 5
        else -> 4
    }

    companion object {
        private const val HORIZONTAL_PADDING = 4
    }
}

internal fun nativeHeadingFontScale(level: Int): Float = when (level) {
    1 -> 1.75f
    2 -> 1.55f
    3 -> 1.35f
    4 -> 1.20f
    5 -> 1.10f
    6 -> 1.00f
    else -> error("unsupported heading level: $level")
}
