package com.algorist.markflow.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.algorist.markflow.browser.MarkFlowSharedBrowserService
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.util.messages.MessageBusConnection
import java.awt.Color
import java.util.concurrent.atomic.AtomicReference

/**
 * App-scoped source of truth for the active IDE editor palette + font, surfaced to the webview as a
 * stable `name -> "#RRGGBB"` map (`ideColorScheme`) plus font hints. This is Approach C (IDE palette
 * sync): the IDE is an opaque color/font provider and the webview owns the design system (mapping,
 * contrast guards, typography).
 *
 * The editor colors are read from stable `EditorColors` [ColorKey]s (the plan's `SchemeColor` /
 * `schemeColors` API does not exist on platform 2026.2), so the map keys are ours and version-stable.
 */
@Service(Service.Level.APP)
class MarkFlowIdeThemeService : Disposable {

    /** Immutable snapshot of the current IDE palette + fonts. */
    data class Snapshot(
        val dark: Boolean,
        val colors: Map<String, String>,
        val fonts: Map<String, String>
    ) {
        companion object {
            val EMPTY = Snapshot(dark = false, colors = emptyMap(), fonts = emptyMap())
        }
    }

    private val LOG = Logger.getInstance(MarkFlowIdeThemeService::class.java)
    private val current = AtomicReference<Snapshot>(Snapshot.EMPTY)


    private var editorColorsListener: EditorColorsListener? = null

    private var messageBusConnection: MessageBusConnection? = null

    init {
        val listener = object : EditorColorsListener {
            override fun globalSchemeChange(scheme: EditorColorsScheme?) {
                refresh()
            }
        }
        editorColorsListener = listener
        val connection = ApplicationManager.getApplication().messageBus.connect()
        messageBusConnection = connection
        connection.subscribe(EditorColorsManager.TOPIC, listener)
        refresh()
    }

    override fun dispose() {
        messageBusConnection?.disconnect()
        messageBusConnection = null
        editorColorsListener = null
    }

    fun getSnapshot(): Snapshot = current.get()

    /** Re-reads the active scheme, stores the snapshot, and re-pushes runtime settings. */
    fun refresh(): Snapshot {
        val snapshot = captureFromCurrentScheme()
        current.set(snapshot)
        LOG.info(
            "MARKFLOW_THEME captured dark=${snapshot.dark} colors=${snapshot.colors.keys.sorted()} " +
                "fonts=${snapshot.fonts.keys.sorted()}"
        )
        MarkFlowSettingsService.bumpRuntimeSettingsRevision()
        MarkFlowSharedBrowserService.notifyRuntimeSettingsChanged(forceReload = false)
        return snapshot
    }

    private fun captureFromCurrentScheme(): Snapshot {
        val manager = EditorColorsManager.getInstance()
        return capture(manager.isDarkEditor, manager.globalScheme)
    }

    /**
     * Pure capture of the palette + fonts from a scheme. Kept pure so it is unit-testable with a
     * fixture scheme (no platform wiring required beyond [EditorColorsScheme]).
     */
    internal fun capture(dark: Boolean, scheme: EditorColorsScheme): Snapshot {
        val colors = LinkedHashMap<String, String>()
        fun put(name: String, color: Color?) {
            val hex = toHex(color) ?: return
            colors[name] = hex
        }
        put("background", scheme.getDefaultBackground())
        put("foreground", scheme.getDefaultForeground())
        put("selectionBackground", scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR))
        put("selectionForeground", scheme.getColor(EditorColors.SELECTION_FOREGROUND_COLOR))
        put("currentLineHighlight", scheme.getColor(EditorColors.CARET_ROW_COLOR))
        put("border", scheme.getColor(EditorColors.BORDER_LINES_COLOR))
        // The editor font is monospace in practice; map it to the webview's code font. Body/title
        // fonts are left to the webview's bundled defaults.
        val fonts = LinkedHashMap<String, String>()
        fonts["codeFont"] = scheme.getFont(EditorFontType.PLAIN).family
        fonts["baseFontSizePx"] = scheme.getEditorFontSize().toString()

        return Snapshot(dark = dark, colors = colors, fonts = fonts)
    }

    private fun toHex(color: Color?): String? {
        if (color == null) return null
        return String.format("#%06x", color.rgb and 0x00FFFFFF)
    }

    companion object {
        fun getInstance(): MarkFlowIdeThemeService =
            ApplicationManager.getApplication().getService(MarkFlowIdeThemeService::class.java)
    }
}
