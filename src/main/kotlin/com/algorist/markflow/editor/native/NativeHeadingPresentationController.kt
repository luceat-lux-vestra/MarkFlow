package com.algorist.markflow.editor.native

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
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
import java.awt.geom.Rectangle2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.util.LinkedHashMap
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class NativeHeadingModel(
    val sourceRange: ProjectionRange,
    val contentRange: ProjectionRange,
    val syntaxRanges: List<ProjectionRange>,
    val level: Int,
    val text: String,
)

internal data class NativeHeadingPresentationEvidence(
    val models: Int,
    val ownedInlays: Int,
    val ownedFolds: Int,
    val fullyConcealed: Int,
    val levels: List<Int>,
    val accessibilityFallbacks: Long,
    val mouseReveals: Long,
    val ownedCustomFolds: Int = 0,
)

/**
 * Converts only parser-proven simple headings into native rich presentation.
 *
 * Headings containing nested inline Markdown remain exact source in this slice. That fail-closed
 * boundary avoids rendering raw emphasis/strikethrough/link/code delimiters as heading text before a retained
 * inline-rich heading renderer exists.
 */
internal object NativeHeadingProjectionPlanner {
    private val nestedInlineKinds = setOf(
        NativeProjectionKind.EMPHASIS,
        NativeProjectionKind.STRONG,
        NativeProjectionKind.STRIKETHROUGH,
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
                    syntaxRanges = heading.syntaxRanges,
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
 * Supported inactive headings replace parser-proven source rows with a MarkFlow-owned
 * CustomFoldRegion. When an expanded foreign section fold blocks the document-start H1 custom
 * fold, the bounded fallback owns only that exact H1 source fold plus one same-line inline
 * presentation. Active headings remove only MarkFlow-owned presentation so the original Markdown
 * is visible and directly editable.
 */
// JetBrains CustomFoldRegion and addCustomLinesFolding are @ApiStatus.Experimental.
// The dependency is isolated here and exercised against the maintained IntelliJ compatibility
// envelope; production has one source-line-replacement heading policy.
@Suppress("UnstableApiUsage")
internal class NativeHeadingPresentationController(
    private val editor: Editor,
    private val postMouseActivityReconciliation: () -> Unit = {},
) : Disposable {
    private var currentPlanIdentity: ProjectionSourceIdentity? = null
    private var currentModels = emptyList<NativeHeadingModel>()
    private val owned = LinkedHashMap<HeadingKey, OwnedHeadingPresentation>()
    private val customOwned = LinkedHashMap<HeadingKey, CustomFoldRegion>()
    private var accessibilityFallbacks = 0L
    private var mouseReveals = 0L
    private var disposed = false

    private val mouseListener = object : EditorMouseListener {
        override fun mousePressed(event: EditorMouseEvent) {
            // A custom Heading fold must reveal before IDEA's default mouse
            // press moves the caret into source and invalidates the fold.
            // The pinned platform's Markdown Heading renderer also handles
            // custom-fold hit testing on mousePressed, not mouseClicked.
            if (event.collapsedFoldRegion is CustomFoldRegion) handleMouseReveal(event)
        }

        override fun mouseClicked(event: EditorMouseEvent) {
            if (event.inlay != null) handleMouseReveal(event)
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
        ownedFolds = owned.values.sumOf { presentation -> presentation.folds.count(FoldRegion::isValid) } +
            customOwned.values.count(CustomFoldRegion::isValid),
        fullyConcealed = owned.values.count(::isFullyConcealed) +
            customOwned.values.count { fold -> fold.isValid && !fold.isExpanded },
        levels = currentModels.map(NativeHeadingModel::level),
        accessibilityFallbacks = accessibilityFallbacks,
        mouseReveals = mouseReveals,
        ownedCustomFolds = customOwned.values.count(CustomFoldRegion::isValid),
    )

    internal fun handleMouseReveal(event: EditorMouseEvent): Boolean {
        if (disposed || editor.isDisposed || event.isConsumed) return false
        if (event.editor !== editor || event.area != EditorMouseEventArea.EDITING_AREA) return false
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return false
        val clickedFold = event.collapsedFoldRegion
        if (clickedFold is CustomFoldRegion) {
            val renderer = clickedFold.renderer as? NativeHeadingCustomFoldRenderer ?: return false
            val key = HeadingKey(renderer.sourceRange.startOffset, renderer.sourceRange.endOffset)
            if (customOwned[key] !== clickedFold || !clickedFold.isValid) return false
            if (currentPlanIdentity?.let(::matchesCurrentIdentity) != true) return false
            removeOwned(key)
            editor.selectionModel.removeSelection()
            editor.caretModel.primaryCaret.moveToOffset(renderer.contentOffset)
            mouseReveals += 1
            event.consume()
            postMouseActivityReconciliation()
            return true
        }
        val renderer = event.inlay?.renderer as? NativeHeadingInlineFoldRenderer ?: return false
        val sourceRange = renderer.sourceRange
        val contentOffset = renderer.contentOffset
        val key = HeadingKey(sourceRange.startOffset, sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false
        if (currentPlanIdentity?.let(::matchesCurrentIdentity) != true) return false
        removeOwned(key)
        editor.selectionModel.removeSelection()
        editor.caretModel.primaryCaret.moveToOffset(contentOffset)
        mouseReveals += 1
        event.consume()
        postMouseActivityReconciliation()
        return true
    }

    private fun installIfCurrent(identity: ProjectionSourceIdentity, model: NativeHeadingModel) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = HeadingKey.of(model)
        if (owned[key]?.let(::isFullyConcealed) == true ||
            customOwned[key]?.let { it.isValid && !it.isExpanded } == true) return
        removeOwned(key)

        val span = NativeHeadingCustomFoldLinePlanner.plan(editor, model) ?: return
        installCustomIfCurrent(identity, key, model, span)
    }

    private fun installCustomIfCurrent(
        identity: ProjectionSourceIdentity,
        key: HeadingKey,
        model: NativeHeadingModel,
        lineSpan: IntRange,
    ) {
        if (!isCurrent(identity) || isActive(model)) return
        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        var installed: CustomFoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            installed = editor.foldingModel.addCustomLinesFolding(
                lineSpan.first, lineSpan.last,
                NativeHeadingCustomFoldRenderer(editor, model),
            )
        }
        if (installed == null) {
            // A foreign expanded section fold can share the H1 start and make IDEA reject
            // CustomFoldRegion creation. Keep that foreign owner untouched and use the bounded
            // same-line fallback; if its source/blank-row proof fails, leave exact source visible.
            installSameStartInlineFallback(identity, key, model, lineSpan)
            return
        }
        val region = installed ?: return
        if (!region.isValid || region.isExpanded) {
            editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                if (region.isValid) editor.foldingModel.removeFoldRegion(region)
            }
            return
        }
        customOwned[key] = region
        check(editor.document.modificationStamp == stampBefore) {
            "source-line replacement changed authoritative heading document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "source-line replacement changed authoritative heading document bytes"
        }
    }

    /**
     * Bounded same-start H1 fallback for a foreign expanded section fold.
     *
     * The InlineElement consumes only the parser-proven heading line plus a verified blank-row
     * capacity. Failure to prove those bounds leaves exact source visible instead of inventing
     * alternate layout ownership.
     */
    private fun installSameStartInlineFallback(
        identity: ProjectionSourceIdentity,
        key: HeadingKey,
        model: NativeHeadingModel,
        lineSpan: IntRange,
    ) {
        if (!isCurrent(identity) || isActive(model) ||
            model.level != 1 || model.sourceRange.startOffset != 0 ||
            lineSpan.first != 0) return
        val foreign = editor.foldingModel.allFoldRegions.any { fold ->
            fold.isValid && fold.isExpanded &&
                fold !is CustomFoldRegion &&
                fold.startOffset == model.sourceRange.startOffset &&
                fold.endOffset > model.sourceRange.endOffset
        }
        if (!foreign) return
        val doc = editor.document
        val originalText = doc.immutableCharSequence.toString()
        val originalStamp = doc.modificationStamp
        val foldStart = model.sourceRange.startOffset
        val foldEnd = model.sourceRange.endOffset
        // Only a parser-proven physical H1 line can be concealed; the following
        // blank line/paragraph and other platform folds remain untouched.
        if (foldEnd != doc.getLineEndOffset(0)) return
        var ownedFold: FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            ownedFold = editor.foldingModel.addFoldRegion(
                foldStart, foldEnd, ZERO_WIDTH_PLACEHOLDER
            )
            ownedFold?.isExpanded = false
        }
        val fold = ownedFold ?: return
        if (!fold.isValid || fold.isExpanded) {
            removeFolds(listOf(fold))
            return
        }
        val renderer = NativeHeadingInlineFoldRenderer(editor, model)
        val glyphHeight = editor.contentComponent.getFontMetrics(renderer.presentationFont()).height
        // The custom fold was rejected by a foreign 0..section owner.
        // Borrow ONLY the verified physical blank row; never hide source
        // paragraphs or modify the foreign section fold.
        if (lineSpan.last <= lineSpan.first ||
            !NativeHeadingLayoutMetrics.fitsVerifiedBlankRow(glyphHeight, editor.lineHeight)) {
            removeFolds(listOf(fold))
            return
        }
        val inline = editor.inlayModel.addInlineElement(foldEnd, true, renderer)
        if (inline == null || !inline.isValid) {
            inline?.takeIf { it.isValid }?.dispose()
            removeFolds(listOf(fold))
            return
        }
        owned[key] = OwnedHeadingPresentation(
            folds = listOf(fold), coverageFolds = listOf(fold), inlay = inline
        )
        check(doc.modificationStamp == originalStamp &&
            doc.immutableCharSequence.toString() == originalText) {
            "co-start H1 inline fallback mutated authoritative source"
        }
    }

    private fun isFullyConcealed(presentation: OwnedHeadingPresentation): Boolean =
        presentation.inlay.isValid &&
            presentation.coverageFolds.isNotEmpty() &&
            presentation.coverageFolds.all { fold -> fold.isValid && !fold.isExpanded }

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
        (owned.keys + customOwned.keys).toSet().forEach(::removeOwned)
    }

    private fun removeOwned(key: HeadingKey) {
        owned.remove(key)?.let { presentation ->
            if (presentation.inlay.isValid) presentation.inlay.dispose()
            removeFolds(presentation.folds)
        }
        customOwned.remove(key)?.let { region ->
            if (region.isValid && !editor.isDisposed) {
                editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
                    if (region.isValid) editor.foldingModel.removeFoldRegion(region)
                }
            }
        }
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
        val coverageFolds: List<FoldRegion>,
        val inlay: Inlay<*>,
    )

    companion object {
        private const val ZERO_WIDTH_PLACEHOLDER = "\u200B"
    }
}

internal object NativeHeadingLayoutMetrics {
    private val verticalPaddingEm = floatArrayOf(
        10f / 13f, 8f / 13f, 7f / 13f, 6f / 13f, 5f / 13f, 4f / 13f,
    )

    fun horizontalPadding(editor: Editor): Int {
        val metrics = editor.contentComponent.getFontMetrics(nativeHeadingBaseFont(editor))
        return max(1, metrics.charWidth(' '))
    }

    fun verticalPadding(level: Int, bodyFontPx: Float): Int {
        require(level in 1..6 && bodyFontPx > 0f)
        return (bodyFontPx * verticalPaddingEm[level - 1]).roundToInt().coerceAtLeast(0)
    }

    fun fitsVerifiedBlankRow(glyphHeight: Int, lineHeight: Int): Boolean =
        glyphHeight > 0 && lineHeight > 0 && glyphHeight <= lineHeight * 2
}

/**
 * Bounded source-line H1 fallback for IDEA's co-start platform section folding.
 * Inline geometry participates in the SAME source line's vertical rhythm;
 * there is no extra block element and no platform-fold ownership transfer.
 */
internal class NativeHeadingInlineFoldRenderer(
    private val editor: Editor,
    private val model: NativeHeadingModel,
) : EditorCustomElementRenderer {
    val sourceRange: ProjectionRange get() = model.sourceRange
    val contentOffset: Int get() = model.contentRange.startOffset

    internal fun presentationFont(): Font = nativeHeadingFont(editor, model.level)

    private fun font(): Font = presentationFont()

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val padding = NativeHeadingLayoutMetrics.horizontalPadding(editor)
        val width = max(1, editor.contentComponent.getFontMetrics(font()).stringWidth(model.text) + padding * 2)
        val viewport = editor.scrollingModel.visibleArea.width
        return if (editor.contentComponent.isShowing && viewport > padding * 2 + 1) {
            min(width, viewport - padding * 2)
        } else {
            width
        }
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        max(editor.lineHeight, editor.contentComponent.getFontMetrics(font()).height)

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val g2 = g as? Graphics2D ?: return
        val font = font()
        val metrics = g2.getFontMetrics(font)
        val padding = NativeHeadingLayoutMetrics.horizontalPadding(editor)
        val maxWidth = max(1, targetRegion.width - padding * 2)
        val text = NativeHeadingEllipsis.rendered(model.text, metrics, maxWidth)
        val previousFont = g2.font
        val previousColor = g2.color
        val previousHint = g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING)
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            g2.color = textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
            val baseline = targetRegion.y +
                max(metrics.ascent, (targetRegion.height + metrics.ascent - metrics.descent) / 2)
            val previousClip = g2.clip
            try {
                // A parser-proven blank row follows this source line. Limit
                // the full-size H1 glyph to that native capacity, not to the
                // undersized inline targetRegion (which clipped the baseline).
                g2.clip(Rectangle(targetRegion.x, targetRegion.y,
                    targetRegion.width, editor.lineHeight * 2))
                g2.drawString(text, targetRegion.x + padding, baseline)
            } finally {
                g2.clip = previousClip
            }
        } finally {
            g2.font = previousFont
            g2.color = previousColor
            if (previousHint != null) g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, previousHint)
        }
    }
}

/**
 * Fail-closed physical-line eligibility for the production custom heading fold.
 * Never let a custom fold hide any unrelated source text: its region always
 * covers whole editor lines, unlike the old subrange syntax folds.
 */
internal object NativeHeadingCustomFoldLinePlanner {
    fun plan(editor: Editor, model: NativeHeadingModel): IntRange? {
        if (editor.isDisposed) return null
        val document = editor.document
        val start = model.sourceRange.startOffset
        val end = model.sourceRange.endOffset
        if (start < 0 || end <= start || end > document.textLength) return null
        val firstLine = document.getLineNumber(start)
        val lastLine = document.getLineNumber(end - 1)
        if (start != document.getLineStartOffset(firstLine) ||
            end != document.getLineEndOffset(lastLine)) return null

        val eligibleEndLine = when {
            lastLine == firstLine -> firstLine // ATX exactly one source line
            lastLine == firstLine + 1 && model.level in 1..2 -> {
                // Setext exactly two physical source lines: heading text plus
                // a parser-proven equals/dash underline. Treat the whole pair
                // as one rich native Heading box instead of revealing raw source.
                val firstText = document.getText(com.intellij.openapi.util.TextRange(
                    document.getLineStartOffset(firstLine), document.getLineEndOffset(firstLine)
                )).trim()
                val underline = document.getText(com.intellij.openapi.util.TextRange(
                    document.getLineStartOffset(lastLine), document.getLineEndOffset(lastLine)
                )).trim()
                val marker = if (model.level == 1) '=' else '-'
                if (firstText != model.text || underline.isEmpty() ||
                    !underline.all { it == marker }) return null
                lastLine
            }
            else -> return null
        }

        val nextLine = eligibleEndLine + 1
        val afterBlankLine = eligibleEndLine + 2
        if (afterBlankLine < document.lineCount &&
            document.getLineStartOffset(nextLine) == document.getLineEndOffset(nextLine) &&
            document.getLineStartOffset(afterBlankLine) < document.getLineEndOffset(afterBlankLine)) {
            return firstLine..nextLine
        }
        return firstLine..eligibleEndLine
    }
}

/**
 * Production source-line replacement renderer support. CustomFoldRegion occupies the
 * parser-proven heading source rows directly; no additive block-inlay implementation exists.
 * An inactive Heading is always displayed as ONE native line with ellipsis.
 * Truncation happens only in paint input, never in Markdown source bytes.
 * BreakIterator prevents slicing Unicode surrogate or grapheme boundaries.
 */
internal object NativeHeadingEllipsis {
    fun rendered(text: String, metrics: java.awt.FontMetrics, width: Int): String {
        if (width <= 0) return ""
        if (metrics.stringWidth(text) <= width) return text
        val suffix = "…"
        if (metrics.stringWidth(suffix) > width) return ""
        val budget = width - metrics.stringWidth(suffix)
        val breaker = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        breaker.setText(text)
        var end = 0
        var pos = breaker.next()
        while (pos != java.text.BreakIterator.DONE &&
            metrics.stringWidth(text.substring(0, pos)) <= budget) {
            end = pos
            pos = breaker.next()
        }
        return text.substring(0, end) + suffix
    }
}

/** Native source-line replacement Heading renderer (pinned IDEA experimental API). */
@Suppress("UnstableApiUsage")
internal class NativeHeadingCustomFoldRenderer(
    private val editor: Editor,
    private val model: NativeHeadingModel,
) : CustomFoldRegionRenderer {
    val sourceRange: ProjectionRange get() = model.sourceRange
    val contentOffset: Int get() = model.contentRange.startOffset
    val displayText: String get() = model.text

    override fun calcWidthInPixels(region: CustomFoldRegion): Int {
        val metrics = editor.contentComponent.getFontMetrics(headingFont())
        val padding = NativeHeadingLayoutMetrics.horizontalPadding(editor)
        val naturalWidth = max(1, metrics.stringWidth(model.text) + padding * 2)
        val viewportWidth = editor.scrollingModel.visibleArea.width
        return if (editor.contentComponent.isShowing && viewportWidth > padding * 2 + 1) {
            min(naturalWidth, viewportWidth - padding * 2)
        } else {
            naturalWidth
        }
    }

    override fun calcHeightInPixels(region: CustomFoldRegion): Int {
        val metrics = editor.contentComponent.getFontMetrics(headingFont())
        val verticalPadding = NativeHeadingLayoutMetrics.verticalPadding(
            model.level,
            editor.colorsScheme.editorFontSize2D,
        )
        return max(editor.lineHeight, metrics.height + verticalPadding * 2)
    }

    override fun paint(
        region: CustomFoldRegion,
        g: Graphics2D,
        targetRegion: Rectangle2D,
        textAttributes: TextAttributes,
    ) {
        val font = headingFont()
        val metrics = g.getFontMetrics(font)
        val height = max(1, targetRegion.height.toInt())
        val padding = NativeHeadingLayoutMetrics.horizontalPadding(editor)
        val available = max(1, targetRegion.width.toInt() - padding * 2)
        val rendered = NativeHeadingEllipsis.rendered(model.text, metrics, available)
        val baseline = targetRegion.y.toInt() +
            max(metrics.ascent, (height + metrics.ascent - metrics.descent) / 2)
        val oldFont = g.font
        val oldColor = g.color
        val oldClip = g.clip
        val oldHint = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING)
        try {
            g.clip(targetRegion)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = font
            g.color = textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
            g.drawString(rendered, targetRegion.x.toInt() + padding, baseline)
        } finally {
            g.font = oldFont
            g.color = oldColor
            g.clip = oldClip
            if (oldHint != null) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, oldHint)
        }
    }

    private fun headingFont(): Font = nativeHeadingFont(editor, model.level)
}

internal fun nativeHeadingBaseFont(editor: Editor): Font =
    UIManager.getFont("Label.font") ?: editor.contentComponent.font

internal fun nativeHeadingFont(editor: Editor, level: Int): Font =
    nativeHeadingBaseFont(editor).deriveFont(
        Font.BOLD,
        editor.colorsScheme.editorFontSize2D * nativeHeadingFontScale(level),
    )

internal fun nativeHeadingFontScale(level: Int): Float = when (level) {
    1 -> 2.20f
    2 -> 1.80f
    3 -> 1.30f
    4 -> 1.00f
    5 -> 1.00f
    6 -> 1.00f
    else -> error("unsupported heading level: $level")
}
