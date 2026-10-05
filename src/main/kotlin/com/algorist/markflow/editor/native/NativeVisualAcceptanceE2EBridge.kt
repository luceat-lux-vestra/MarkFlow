package com.algorist.markflow.editor.native

import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.algorist.markflow.settings.MarkFlowSettingsService
import com.algorist.markflow.settings.state.KatexDisplayDensity
import com.algorist.markflow.settings.state.MermaidErrorDisplay
import com.algorist.markflow.settings.state.MermaidSizeMode
import com.algorist.markflow.settings.state.ThemeSource
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
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
    private const val INITIAL_WINDOW_WIDTH = 1900
    private const val INITIAL_WINDOW_HEIGHT = 1000
    private const val TARGET_VIEWPORT_WIDTH = 1200
    private const val TARGET_VIEWPORT_HEIGHT = 760

    fun prepare(editor: Editor): String {
        ApplicationManager.getApplication().assertIsDispatchThread()

        val lafManager = LafManager.getInstance()
        lafManager.autodetect = false
        val lightTheme = requireNotNull(lafManager.defaultLightLaf) {
            "visual acceptance requires IntelliJ's bundled default light theme"
        }
        if (lafManager.currentUIThemeLookAndFeel.id != lightTheme.id) {
            lafManager.currentUIThemeLookAndFeel = lightTheme
            lafManager.updateUI()
        }

        val colors = EditorColorsManager.getInstance()
        val defaultScheme = requireNotNull(
            colors.getScheme(EditorColorsManager.getDefaultSchemeName())
        ) {
            "visual acceptance requires IntelliJ's bundled default editor scheme"
        }
        // getGlobalScheme() is non-null while setGlobalScheme(...) accepts nullable, so Kotlin
        // cannot expose this Java API as a mutable synthetic property despite the style inspection.
        @Suppress("UsePropertyAccessSyntax")
        colors.setGlobalScheme(defaultScheme)

        val project = requireNotNull(editor.project) {
            "visual acceptance editor must belong to a project"
        }
        val selectedFileEditor = requireNotNull(FileEditorManager.getInstance(project).selectedEditor) {
            "visual acceptance requires the selected Markdown FileEditor"
        }
        check(selectedFileEditor is TextEditorWithPreview) {
            "visual acceptance requires a TextEditorWithPreview, got ${selectedFileEditor.javaClass.name}"
        }
        selectedFileEditor.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR)
        EditorSettingsExternalizable.getInstance().isShowInspectionWidget = false

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
        window.setBounds(0, 0, INITIAL_WINDOW_WIDTH, INITIAL_WINDOW_HEIGHT)
        window.validate()
        val screen = Toolkit.getDefaultToolkit().screenSize
        repeat(3) {
            val viewport = editor.scrollingModel.visibleArea
            if (viewport.width != TARGET_VIEWPORT_WIDTH || viewport.height != TARGET_VIEWPORT_HEIGHT) {
                val adjustedWidth = window.width + (TARGET_VIEWPORT_WIDTH - viewport.width)
                val adjustedHeight = window.height + (TARGET_VIEWPORT_HEIGHT - viewport.height)
                check(adjustedWidth in 1..screen.width && adjustedHeight in 1..screen.height) {
                    "visual acceptance cannot normalize the IDE window inside the pinned screen: " +
                        "requested=${adjustedWidth}x${adjustedHeight}, screen=${screen.width}x${screen.height}"
                }
                window.setBounds(0, 0, adjustedWidth, adjustedHeight)
                window.validate()
            }
        }
        val viewport = editor.scrollingModel.visibleArea
        check(viewport.width == TARGET_VIEWPORT_WIDTH && viewport.height == TARGET_VIEWPORT_HEIGHT) {
            "visual acceptance viewport normalization failed: " +
                "expected=${TARGET_VIEWPORT_WIDTH}x${TARGET_VIEWPORT_HEIGHT}, " +
                "actual=${viewport.width}x${viewport.height}"
        }
        NativeMarkFlowProductionLifecycle.refreshAll()
        return environmentIdentity(editor)
    }

    /**
     * Removes IntelliJ-owned chrome from the screen-capture surface.
     *
     * Notification balloons and inspection/traffic-light status are IDE state, not part of the
     * native Markdown presentation contract. Normalize them immediately before every capture so
     * unrelated plugin lifecycle and code-analysis timing cannot become golden pixels.
     */
    fun normalizeEditorChromeForCapture(editor: Editor): Int {
        ApplicationManager.getApplication().assertIsDispatchThread()

        val project = requireNotNull(editor.project) {
            "visual acceptance editor must belong to a project"
        }
        EditorSettingsExternalizable.getInstance().isShowInspectionWidget = false

        val manager = NotificationsManager.getNotificationsManager()
        val notifications = linkedSetOf<Notification>()
        notifications.addAll(manager.getNotificationsOfType(Notification::class.java, project))
        notifications.addAll(manager.getNotificationsOfType(Notification::class.java, null))
        notifications.forEach { it.expire() }

        val unexpired = notifications.count { !it.isExpired }
        check(unexpired == 0) {
            "visual acceptance failed to expire $unexpired IDE notifications before capture"
        }
        check(!EditorSettingsExternalizable.getInstance().isShowInspectionWidget) {
            "visual acceptance inspection widget normalization did not stick"
        }
        return notifications.size
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
        val uiTheme = LafManager.getInstance().currentUIThemeLookAndFeel
        val scheme = EditorColorsManager.getInstance().globalScheme
        val runtime = MarkFlowSettingsService.getInstance().runtimeSettings()
        val window = requireNotNull(SwingUtilities.getWindowAncestor(component))

        return listOf(
            "visual_contract=v1",
            "window=${window.width}x${window.height}",
            "viewport=${editor.scrollingModel.visibleArea.width}x${editor.scrollingModel.visibleArea.height}",
            "screen=${screen.width}x${screen.height}",
            "scale_x=${format(transform.scaleX)}",
            "scale_y=${format(transform.scaleY)}",
            "laf_name=${lookAndFeel.name}",
            "laf_class=${lookAndFeel.javaClass.name}",
            "laf_theme_id=${uiTheme.id}",
            "laf_theme_name=${uiTheme.name}",
            "laf_theme_dark=${uiTheme.isDark}",
            "editor_scheme=${scheme.name}",
            "editor_font_family=${component.font.family}",
            "editor_font_name=${component.font.name}",
            "editor_font_size=${component.font.size}",
            "inspection_widget=${EditorSettingsExternalizable.getInstance().isShowInspectionWidget}",
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
