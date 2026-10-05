package com.algorist.markflow.editor.native

import com.algorist.markflow.settings.MarkFlowSettingsService
import com.algorist.markflow.settings.state.KatexDisplayDensity
import com.algorist.markflow.settings.state.MermaidErrorDisplay
import com.algorist.markflow.settings.state.MermaidSizeMode
import com.algorist.markflow.settings.state.ThemeSource
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Toolkit
import java.util.Locale
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Narrow Starter/Driver-only seam for #339 deterministic visual acceptance.
 *
 * It changes only test-sandbox appearance/window state and exposes read-only presentation geometry.
 * It never mutates the authoritative Markdown [com.intellij.openapi.editor.Document], never creates
 * presentation owners, and has no production registration or startup hook.
 */
@Suppress("unused")
internal object NativeVisualAcceptanceE2EBridge {
    private const val WINDOW_WIDTH = 1400
    private const val WINDOW_HEIGHT = 1000

    fun prepare(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()

        val settings = MarkFlowSettingsService.getInstance()
        settings.updateFromUi(
            settings.state.copy(
                mermaidSizeMode = MermaidSizeMode.FIT_TO_VIEWPORT.name,
                mermaidZoomPercent = 100,
                themeSource = ThemeSource.LIGHT.name,
                fontFamily = "",
                baseFontSizePx = 16,
                mermaidErrorDisplay = MermaidErrorDisplay.INLINE_ERROR_BOX.name,
                katexDisplayDensity = KatexDisplayDensity.COMFORTABLE.name,
            )
        )

        val window = requireNotNull(SwingUtilities.getWindowAncestor(editor.component)) {
            "visual acceptance requires a showing IntelliJ editor window"
        }
        window.setBounds(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT)
        window.validate()
        NativeMarkFlowProductionLifecycle.refreshAll()
        return environmentIdentity(editor)
    }

    fun environmentIdentity(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val component = editor.contentComponent
        val graphics = requireNotNull(component.graphicsConfiguration) {
            "visual acceptance editor has no graphics configuration"
        }
        val transform = graphics.defaultTransform
        val screen = Toolkit.getDefaultToolkit().screenSize
        val lookAndFeel = requireNotNull(UIManager.getLookAndFeel())
        val scheme = EditorColorsManager.getInstance().globalScheme
        val runtime = MarkFlowSettingsService.getInstance().runtimeSettings()
        val window = requireNotNull(SwingUtilities.getWindowAncestor(component))

        return listOf(
            "visual_contract=v1",
            "window=${window.width}x${window.height}",
            "screen=${screen.width}x${screen.height}",
            "scale_x=${format(transform.scaleX)}",
            "scale_y=${format(transform.scaleY)}",
            "laf_name=${lookAndFeel.name}",
            "laf_class=${lookAndFeel.javaClass.name}",
            "editor_scheme=${scheme.name}",
            "editor_font_family=${component.font.family}",
            "editor_font_name=${component.font.name}",
            "editor_font_size=${component.font.size}",
            "markflow_theme=${runtime.themeSource}",
            "markflow_font=${runtime.fontFamily.ifBlank { "<IDE_DEFAULT>" }}",
            "markflow_base_font_size=${runtime.baseFontSizePx}",
            "mermaid_size=${runtime.mermaidSizeMode}",
            "mermaid_zoom=${runtime.mermaidZoomPercent}",
            "katex_density=${runtime.katexDisplayDensity}",
        ).joinToString(separator = "\n", postfix = "\n")
    }

    fun contentScreenX(editor: Editor): Int =
        editor.contentComponent.locationOnScreen.x + editor.scrollingModel.visibleArea.x

    fun contentScreenY(editor: Editor): Int =
        editor.contentComponent.locationOnScreen.y + editor.scrollingModel.visibleArea.y

    fun contentWidth(editor: Editor): Int = editor.scrollingModel.visibleArea.width

    fun contentHeight(editor: Editor): Int = editor.scrollingModel.visibleArea.height

    fun visualEvidence(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val controller = requireNotNull(NativeMarkFlowProductionLifecycle.controller(editor)) {
            "production native MarkFlow controller is not attached"
        }
        val ordinary = controller.evidenceSnapshot()
        val table = controller.tableEvidenceSnapshot()
        val host = controller.hostResourceEvidenceSnapshot()
        val derived = controller.derivedEvidenceSnapshot()
        val rawHtml = controller.rawHtmlEvidenceSnapshot()

        return listOf(
            "headingModels=${ordinary.headingModels}",
            "headingInlays=${ordinary.headingInlays}",
            "inlineHighlighters=${ordinary.inlineOwnedHighlighters}",
            "blockquoteInlays=${ordinary.blockQuoteInlays}",
            "fencedInlays=${ordinary.fencedCodeInlays}",
            "indentedInlays=${ordinary.indentedCodeInlays}",
            "listModels=${ordinary.listModels}",
            "listInlays=${ordinary.listInlays}",
            "taskRows=${ordinary.taskRows}",
            "tableInlays=${table.ownedInlays}",
            "localImages=${host?.localImages ?: 0}",
            "decodedImages=${host?.decodedImages ?: 0}",
            "imageInlays=${host?.ownedImageInlays ?: 0}",
            "imagePending=${host?.pendingImageLoads ?: 0}",
            "derivedFragments=${derived?.derivedFragments ?: 0}",
            "derivedPending=${derived?.pendingRequests ?: 0}",
            "derivedDecoded=${derived?.decodedArtifacts ?: 0}",
            "derivedInlays=${derived?.ownedInlays ?: 0}",
            "rawFragments=${rawHtml?.fragments ?: 0}",
            "rawPending=${rawHtml?.pendingRequests ?: 0}",
            "rawDecoded=${rawHtml?.decodedArtifacts ?: 0}",
            "rawInlays=${rawHtml?.ownedInlays ?: 0}",
            "rawBlocked=${rawHtml?.blockedPreviews ?: 0}",
        ).joinToString(separator = ";")
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
}
