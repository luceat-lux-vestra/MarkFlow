package com.algorist.markflow.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import java.awt.Color
import java.util.concurrent.atomic.AtomicReference

/**
 * App-scoped source of truth for the active IDE editor palette + font, surfaced to presentation as a
 * stable `name -> "#RRGGBB"` map (`ideColorScheme`) plus font hints. The optional renderer sink owns
 * browser delivery; palette capture itself remains available without JCEF.
 *
 * The editor colors are read from stable `EditorColors` color keys (the plan's `SchemeColor` /
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

    private val log = Logger.getInstance(MarkFlowIdeThemeService::class.java)
    private val current = AtomicReference(Snapshot.EMPTY)

    init {
        val listener = EditorColorsListener { refresh() }
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(EditorColorsManager.TOPIC, listener)
        // Initial capture establishes the source of truth. There are no open MarkFlow editors to
        // notify yet, so initialization must not trigger a runtime-settings push.
        val snapshot = captureFromCurrentScheme()
        current.set(snapshot)
        log.info(
            "MARKFLOW_THEME initial capture dark=${snapshot.dark} colors=${snapshot.colors.keys.sorted()} " +
                "fonts=${snapshot.fonts.keys.sorted()}"
        )
    }

    override fun dispose() = Unit

    fun getSnapshot(): Snapshot = current.get()

    /** Re-reads the active scheme, stores the snapshot, and notifies available presentation sinks. */
    fun refresh(): Snapshot {
        val snapshot = captureFromCurrentScheme()
        current.set(snapshot)
        log.info(
            "MARKFLOW_THEME captured dark=${snapshot.dark} colors=${snapshot.colors.keys.sorted()} " +
                "fonts=${snapshot.fonts.keys.sorted()}"
        )
        MarkFlowSettingsService.bumpRuntimeSettingsRevision()
        MarkFlowRuntimeSettingsNotifier.notifyChanged(forceReload = false)
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
        put("background", scheme.defaultBackground)
        put("foreground", scheme.defaultForeground)
        put("selectionBackground", scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR))
        put("selectionForeground", scheme.getColor(EditorColors.SELECTION_FOREGROUND_COLOR))
        put("border", scheme.getColor(EditorColors.BORDER_LINES_COLOR))
        // EditorFontType.PLAIN is the regular editor font the user configures in Settings >
        // Editor > Font. Surface it as the webview's default body font.
        val fonts = LinkedHashMap<String, String>()
        fonts["codeFont"] = scheme.getFont(EditorFontType.PLAIN).family

        return Snapshot(dark = dark, colors = colors, fonts = fonts)
    }

    private fun toHex(color: Color?): String? {
        if (color == null) return null
        return String.format("#%06x", color.rgb and 0x00FFFFFF)
    }

    companion object {
        internal fun getInstance(): MarkFlowIdeThemeService =
            ApplicationManager.getApplication().getService(MarkFlowIdeThemeService::class.java)
    }
}
