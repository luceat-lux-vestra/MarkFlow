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
import kotlin.math.max
import kotlin.math.min

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
 * Supported inactive headings conceal their exact source through MarkFlow-owned folds plus any
 * already-collapsed parser-syntax fold owned by the platform, then render a native block inlay with
 * H1-H6 typography. Active headings remove only MarkFlow-owned rich presentation so the original
 * Markdown is visible and directly editable.
 */
// JetBrains CustomFoldRegion and addCustomLinesFolding are @ApiStatus.Experimental.
// Deliberately constrained to the explicit opt-in #350 pilot on pinned 2026.2.x;
// suppress locally instead of disabling Qodana High severity for the repository.
@Suppress("UnstableApiUsage")
internal class NativeHeadingPresentationController(
    private val editor: Editor,
    // Explicit integration pilot; no production call site enables it yet.
    private val sourceLineReplacement: Boolean = false,
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
            // Keep additive block-inlay clicks on the existing click phase.
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
            return true
        }
        val renderer = event.inlay?.renderer ?: return false
        val sourceRange: ProjectionRange
        val contentOffset: Int
        when (renderer) {
            is NativeHeadingInlayRenderer -> {
                sourceRange = renderer.sourceRange
                contentOffset = renderer.contentOffset
            }
            is NativeHeadingInlineFoldRenderer -> {
                sourceRange = renderer.sourceRange
                contentOffset = renderer.contentOffset
            }
            else -> return false
        }
        val key = HeadingKey(sourceRange.startOffset, sourceRange.endOffset)
        val presentation = owned[key] ?: return false
        if (!presentation.inlay.isValid || presentation.inlay.renderer !== renderer) return false
        if (currentPlanIdentity?.let(::matchesCurrentIdentity) != true) return false
        removeOwned(key)
        editor.selectionModel.removeSelection()
        editor.caretModel.primaryCaret.moveToOffset(contentOffset)
        mouseReveals += 1
        event.consume()
        return true
    }

    private fun installIfCurrent(identity: ProjectionSourceIdentity, model: NativeHeadingModel) {
        if (!isCurrent(identity) || isActive(model)) return
        val key = HeadingKey.of(model)
        if (owned[key]?.let(::isFullyConcealed) == true ||
            customOwned[key]?.let { it.isValid && !it.isExpanded } == true) return
        removeOwned(key)

        if (sourceLineReplacement) {
            // A custom fold can replace only a parser-proven whole source line.
            // Unsafe Setext/multiline cases must remain exact, editable Markdown;
            // never fall through to the legacy additive block inlay in this mode.
            val span = NativeHeadingCustomFoldLinePlanner.plan(editor, model) ?: return
            installCustomIfCurrent(identity, key, model, span)
            return
        }

        val sourceBefore = editor.document.immutableCharSequence.toString()
        val stampBefore = editor.document.modificationStamp
        val foldRanges = headingFoldRanges(model, sourceBefore) ?: return
        val installedFolds = mutableListOf<FoldRegion>()
        val coverageFolds = mutableListOf<FoldRegion>()
        var foldsInstalled = true
        editor.foldingModel.runBatchFoldingOperationDoNotCollapseCaret {
            for ((startOffset, endOffset) in foldRanges) {
                val coveredByForeignSyntaxFold = editor.foldingModel.allFoldRegions.firstOrNull { fold ->
                    fold.isValid &&
                        !fold.isExpanded &&
                        fold.startOffset <= startOffset &&
                        fold.endOffset >= endOffset &&
                        model.syntaxRanges.any { syntax ->
                            startOffset >= syntax.startOffset && endOffset <= syntax.endOffset
                        }
                }
                if (coveredByForeignSyntaxFold != null) {
                    coverageFolds += coveredByForeignSyntaxFold
                    continue
                }

                val fold = editor.foldingModel.addFoldRegion(
                    startOffset,
                    endOffset,
                    ZERO_WIDTH_PLACEHOLDER,
                )
                if (fold == null) {
                    foldsInstalled = false
                    break
                }
                installedFolds += fold
                coverageFolds += fold
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
                coverageFolds.clear()
            }
        }
        if (!foldsInstalled || coverageFolds.size != foldRanges.size) return

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
        owned[key] = OwnedHeadingPresentation(
            folds = installedFolds.toList(),
            coverageFolds = coverageFolds.toList(),
            inlay = inlay,
        )

        check(editor.document.modificationStamp == stampBefore) {
            "native heading presentation changed the authoritative Document modification stamp"
        }
        check(editor.document.immutableCharSequence.toString() == sourceBefore) {
            "native heading presentation changed the authoritative Document source"
        }
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
            // In the explicit #353 source-line-replacement pilot ONLY:
            // the Markdown provider may own an expanded section fold with the
            // same start as document H1. IDEA rejects a CustomFoldRegion there.
            // Try a small ordinary MarkFlow-owned source fold plus a SAME-LINE
            // inline heading. Never remove/change the platform-owned region,
            // and never fall back to the legacy additive block inlay.
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
     * Isolated #353 H1 candidate, enabled only for sourceLineReplacement tests.
     * The added InlineElement uses the existing heading source line rather
     * than introducing a second block height. This is NOT release acceptance:
     * the pinned full-IDE mouse/selection and baseline contracts remain gates.
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
        val inline = editor.inlayModel.addInlineElement(foldEnd, true, renderer)
        if (inline == null || !inline.isValid) {
            inline?.takeIf { it.isValid }?.dispose()
            removeFolds(listOf(fold))
            return
        }
        // #353 measured 2026.2.3 JCEF paragraph text-range gap:
        // P2 - P1 = 102.297 CSS px, source-line candidate = 85 device px.
        // The missing space lies *after the first paragraph* (before the
        // next section heading), NOT between H1 and that paragraph.
        // Earlier H1-anchored spacer attempts moved H1 or P1 but left the
        // relative P1-to-P2 interval unchanged. Only apply to a parser-proven
        // H1 followed by one verified blank line and nonblank paragraph;
        // avoid altering unrelated source ranges or platform-owned folds.
        val paragraphLine = lineSpan.last + 1
        if (paragraphLine >= doc.lineCount ||
            doc.getLineStartOffset(paragraphLine) >= doc.getLineEndOffset(paragraphLine)) {
            inline.dispose()
            removeFolds(listOf(fold))
            return
        }
        val paragraphEnd = doc.getLineEndOffset(paragraphLine)
        // The source-neutral page pilot may already own this exact block
        // boundary. A second 16px gap would double the measured P1 -> P2
        // spacing. Do not dispose or re-own the pilot's inlay.
        val pageOwnsGap = editor.inlayModel.getBlockElementsInRange(paragraphEnd, paragraphEnd)
            .any { it.isValid && it.offset == paragraphEnd &&
                it.renderer === NativePageParagraphSpacing }
        val paragraphGap = if (pageOwnsGap) null else editor.inlayModel.addBlockElement(
            paragraphEnd, true, false, 0, NativeHeadingParagraphGapRenderer
        )
        if (!pageOwnsGap && (paragraphGap == null || !paragraphGap.isValid)) {
            paragraphGap?.takeIf { it.isValid }?.dispose()
            inline.dispose()
            removeFolds(listOf(fold))
            return
        }
        owned[key] = OwnedHeadingPresentation(
            folds = listOf(fold), coverageFolds = listOf(fold),
            inlay = inline, spacingInlay = paragraphGap,
        )
        check(doc.modificationStamp == originalStamp &&
            doc.immutableCharSequence.toString() == originalText) {
            "co-start H1 inline fallback mutated authoritative source"
        }
    }

    private fun headingFoldRanges(model: NativeHeadingModel, source: String): List<ProjectionRange>? {
        val range = model.sourceRange
        if (!range.isInside(source)) return null
        val codePoints = source.codePointCount(range.startOffset, range.endOffset)
        if (codePoints < 2) return null
        val tailStart = Character.offsetByCodePoints(source, range.endOffset, -1)
        if (tailStart <= range.startOffset || tailStart >= range.endOffset) return null

        val boundaries = linkedSetOf(range.startOffset, tailStart, range.endOffset)
        model.syntaxRanges.forEach { syntax ->
            if (syntax.startOffset > range.startOffset && syntax.startOffset < range.endOffset) {
                boundaries += syntax.startOffset
            }
            if (syntax.endOffset > range.startOffset && syntax.endOffset < range.endOffset) {
                boundaries += syntax.endOffset
            }
        }
        editor.foldingModel.allFoldRegions
            .asSequence()
            .filter(FoldRegion::isValid)
            .filter { fold -> fold.startOffset < range.endOffset && fold.endOffset > range.startOffset }
            .forEach { fold ->
                if (fold.startOffset > range.startOffset && fold.startOffset < range.endOffset) {
                    boundaries += fold.startOffset
                }
                if (fold.endOffset > range.startOffset && fold.endOffset < range.endOffset) {
                    boundaries += fold.endOffset
                }
            }

        return boundaries
            .sorted()
            .zipWithNext()
            .mapNotNull { (startOffset, endOffset) ->
                if (endOffset > startOffset) ProjectionRange(startOffset, endOffset) else null
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
            if (presentation.spacingInlay?.isValid == true) presentation.spacingInlay.dispose()
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
        val spacingInlay: Inlay<*>? = null,
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

    val displayText: String
        get() = model.text

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = headingFont()
        val metrics = editor.contentComponent.getFontMetrics(font)
        val naturalWidth = max(1, metrics.stringWidth(model.text) + HORIZONTAL_PADDING * 2)
        // IDEA caches geometry when each renderer is installed. A detached
        // test editor (or a not-yet-displayed production editor) can report
        // different transient, positive viewport sizes between installations.
        // Only a visible, meaningfully laid-out viewport may clip the text.
        // Otherwise both inlay and custom fold retain their intrinsic width.
        val viewportWidth = editor.scrollingModel.visibleArea.width
        return if (editor.contentComponent.isShowing &&
            viewportWidth > HORIZONTAL_PADDING * 2 + 1) {
            min(naturalWidth, viewportWidth - HORIZONTAL_PADDING * 2)
        } else {
            naturalWidth
        }
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

/**
 * Pilot-only whitespace component, not a rich heading or baseline-approved
 * typography. Preview text Range P2-P1 measured 102.297 CSS px versus native
 * 85 px at DPR 1. The 16px CSS block-margin contribution is placed after the
 * first paragraph rather than after H1: only then can the source-aligned P2-P1
 * interval change. This candidate is not approved for 89-case acceptance.
 */
internal object NativeHeadingParagraphGapRenderer : EditorCustomElementRenderer {
    private const val BUNDLED_PREVIEW_HEADING_BOTTOM_GAP_PX = 16

    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 1

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = BUNDLED_PREVIEW_HEADING_BOTTOM_GAP_PX

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) { /* Deliberately empty. Source heading text is rendered only inline. */ }
}

/**
 * Exact 2026.2.3 bundled JCEF Preview CSS typography, opt-in #353 pilot ONLY.
 *
 * Measured on an actual IDE/JCEF run, not invented font tiers:
 * H1..H6 28.6, 23.4, 16.9, 13, 13, 13 CSS px; font-weight 700;
 * computed-family Helvetica, Arial, freesans, sans-serif;
 * H6 color rgb(115,118,124) in Islands Light.
 *
 * The shipping additive heading renderer deliberately retains its existing
 * user-editor styling. This is a test-only controlled experiment until whole
 * editor content geometry, fallback font and mouse/source lifecycle match.
 */
internal object NativeHeadingPreviewTypographyPilot {
    private val pinnedScale = floatArrayOf(2.2f, 1.8f, 1.3f, 1.0f, 1.0f, 1.0f)
    private val preferredCssFamilies = listOf(
        "Helvetica", "Arial", "FreeSans", "Liberation Sans", "Arimo", "SansSerif"
    )
    private val resolvedFamily: String by lazy {
        val installed = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames.toSet()
        preferredCssFamilies.firstOrNull { it in installed } ?: "SansSerif"
    }
    internal val mutedH6LightColor = java.awt.Color(115, 118, 124)

    fun scale(level: Int): Float {
        require(level in 1..6)
        return pinnedScale[level - 1]
    }

    fun font(editor: Editor, level: Int): Font {
        val size = editor.contentComponent.font.size2D * scale(level)
        return Font(resolvedFamily, Font.BOLD, 1).deriveFont(size)
    }

    fun foreground(editor: Editor, level: Int, fallback: java.awt.Color): java.awt.Color {
        val background = editor.colorsScheme.defaultBackground
        return if (level == 6 && (background.red * 299 +
                background.green * 587 + background.blue * 114) / 1000 >= 128) {
            mutedH6LightColor
        } else {
            fallback
        }
    }
}

/**
 * Bounded source-line H1 pilot for IDEA's co-start platform section folding.
 * Inline geometry participates in the SAME source line's vertical rhythm;
 * there is no extra block element and no platform-fold ownership transfer.
 */
internal class NativeHeadingInlineFoldRenderer(
    private val editor: Editor,
    private val model: NativeHeadingModel,
) : EditorCustomElementRenderer {
    val sourceRange: ProjectionRange get() = model.sourceRange
    val contentOffset: Int get() = model.contentRange.startOffset

    private fun font(): Font = NativeHeadingPreviewTypographyPilot.font(editor, model.level)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val width = max(1, editor.contentComponent.getFontMetrics(font()).stringWidth(model.text) + 8)
        val viewport = editor.scrollingModel.visibleArea.width
        return if (editor.contentComponent.isShowing && viewport > 9) min(width, viewport - 8) else width
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
        val maxWidth = max(1, targetRegion.width - 8)
        val ellipsis = "…"
        var text = model.text
        if (metrics.stringWidth(text) > maxWidth) {
            val budget = max(0, maxWidth - metrics.stringWidth(ellipsis))
            while (text.isNotEmpty() && metrics.stringWidth(text) > budget) text = text.dropLast(1)
            text += ellipsis
        }
        val previousFont = g2.font
        val previousColor = g2.color
        val previousHint = g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING)
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            g2.color = NativeHeadingPreviewTypographyPilot.foreground(
                editor, model.level, textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
            )
            val baseline = targetRegion.y +
                max(metrics.ascent, (targetRegion.height + metrics.ascent - metrics.descent) / 2)
            g2.drawString(text, targetRegion.x + 4, baseline)
        } finally {
            g2.font = previousFont
            g2.color = previousColor
            if (previousHint != null) g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, previousHint)
        }
    }
}

/**
 * Fail-closed physical-line eligibility for a future custom heading fold.
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
        val line = document.getLineNumber(start)
        if (document.getLineNumber(end - 1) != line) return null
        // Setext, nested/indented text and parser sub-line ranges are not
        // eligible for whole-line replacement until separately reviewed.
        if (start != document.getLineStartOffset(line) ||
            end != document.getLineEndOffset(line)) return null

        val nextLine = line + 1
        val afterBlankLine = line + 2
        if (afterBlankLine < document.lineCount &&
            document.getLineStartOffset(nextLine) == document.getLineEndOffset(nextLine) &&
            document.getLineStartOffset(afterBlankLine) < document.getLineEndOffset(afterBlankLine)) {
            return line..nextLine
        }
        return line..line
    }
}

/**
 * Actual #350 replacement-presentation renderer. Unlike an Editor block inlay,
 * CustomFoldRegion occupies source visual lines, so its declared height does
 * not get added *after* the original heading + blank-line height.
 *
 * Kept separate from the active NativeHeadingPresentationController until
 * platform-owned syntax folds, real mouse hit testing, and source-reveal
 * lifecycle are verified together on the exact production path.
 */
// JetBrains CustomFoldRegion and addCustomLinesFolding are @ApiStatus.Experimental.
// Deliberately constrained to the explicit opt-in #350 pilot on pinned 2026.2.x;
// suppress locally instead of disabling Qodana High severity for the repository.
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
        val naturalWidth = max(1, metrics.stringWidth(model.text) + HORIZONTAL_PADDING * 2)
        // IDEA caches geometry when each renderer is installed. A detached
        // test editor (or a not-yet-displayed production editor) can report
        // different transient, positive viewport sizes between installations.
        // Only a visible, meaningfully laid-out viewport may clip the text.
        // Otherwise both inlay and custom fold retain their intrinsic width.
        val viewportWidth = editor.scrollingModel.visibleArea.width
        return if (editor.contentComponent.isShowing &&
            viewportWidth > HORIZONTAL_PADDING * 2 + 1) {
            min(naturalWidth, viewportWidth - HORIZONTAL_PADDING * 2)
        } else {
            naturalWidth
        }
    }

    override fun calcHeightInPixels(region: CustomFoldRegion): Int {
        val metrics = editor.contentComponent.getFontMetrics(headingFont())
        // The editor itself clamps custom fold height to at least lineHeight.
        return max(editor.lineHeight, metrics.height + verticalPadding(model.level) * 2)
    }

    override fun paint(
        region: CustomFoldRegion,
        g: Graphics2D,
        targetRegion: Rectangle2D,
        textAttributes: TextAttributes,
    ) {
        val font = headingFont()
        val metrics = g.getFontMetrics(font)
        val regionHeight = max(1, targetRegion.height.toInt())
        val available = max(1, targetRegion.width.toInt() - HORIZONTAL_PADDING * 2)
        val rendered = clip(model.text, metrics, available)
        val baseline = targetRegion.y.toInt() +
            max(metrics.ascent + verticalPadding(model.level), (regionHeight + metrics.ascent - metrics.descent) / 2)

        val oldFont = g.font
        val oldColor = g.color
        val oldHint = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING)
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = font
            g.color = NativeHeadingPreviewTypographyPilot.foreground(
                editor, model.level, textAttributes.foregroundColor ?: editor.colorsScheme.defaultForeground
            )
            g.drawString(rendered, targetRegion.x.toInt() + HORIZONTAL_PADDING, baseline)
        } finally {
            g.font = oldFont
            g.color = oldColor
            if (oldHint != null) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, oldHint)
        }
    }

    private fun headingFont(): Font =
        NativeHeadingPreviewTypographyPilot.font(editor, model.level)

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
