package com.algorist.markflow.settings

import com.intellij.application.options.EditorFontsConstants
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.XmlSerializerUtil
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicInteger
import com.algorist.markflow.MarkFlowDiagnostics
import com.algorist.markflow.MyBundle
import com.algorist.markflow.browser.MarkFlowSharedBrowserService
import com.algorist.markflow.settings.state.DiagramSecurityLevel
import com.algorist.markflow.settings.state.KatexDisplayDensity
import com.algorist.markflow.settings.state.MarkFlowRuntimeSettings
import com.algorist.markflow.settings.state.MarkFlowSettingsState
import com.algorist.markflow.settings.state.MermaidErrorDisplay
import com.algorist.markflow.settings.state.MermaidSizeMode
import com.algorist.markflow.settings.state.ThemeSource

@State(name = "MarkFlowSettings", storages = [Storage("markflow.xml")])
class MarkFlowSettingsService : PersistentStateComponent<MarkFlowSettingsState> {

    private var state = MarkFlowSettingsState()

    override fun getState(): MarkFlowSettingsState = state

    override fun loadState(state: MarkFlowSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
        normalize()
        settingsRevision.incrementAndGet()
        if (MarkFlowDiagnostics.enabled) {
            LOG.warn(
                "MARKFLOW_SETTINGS loadState themeSource=${this.state.themeSource}, " +
                    "diagramSecurityLevel=${this.state.diagramSecurityLevel}"
            )
        }
    }

    fun updateFromUi(newState: MarkFlowSettingsState) {
        val previousState = state.copy().also { normalize(it) }
        XmlSerializerUtil.copyBean(newState, state)
        normalize()
        val changed = previousState != state
        val nextRevision = if (changed) settingsRevision.incrementAndGet() else settingsRevision.get()
        if (MarkFlowDiagnostics.enabled) {
            LOG.warn(
                "MARKFLOW_SETTINGS updateFromUi changed=$changed, " +
                    "themeSource=${previousState.themeSource} -> ${state.themeSource}, " +
                    "security=${previousState.diagramSecurityLevel} -> ${state.diagramSecurityLevel}, " +
                    "revision=$nextRevision"
            )
        }
        if (changed) {
            MarkFlowSharedBrowserService.notifyRuntimeSettingsChanged(forceReload = false)
        }
    }

    fun runtimeSettings(): MarkFlowRuntimeSettings {
        normalize()
        val resolvedThemeSource = resolveThemeSourceForRuntime()
        val revision = settingsRevision.get()
        val snapshot = MarkFlowIdeThemeService.getInstance().getSnapshot()
        if (MarkFlowDiagnostics.enabled) {
            LOG.warn(
                "MARKFLOW_SETTINGS runtimeSettings themeSource=${state.themeSource}, " +
                    "resolvedThemeSource=$resolvedThemeSource, security=${state.diagramSecurityLevel}, revision=$revision"
            )
        }
        return runtimeSettingsFromSnapshot(snapshot, resolvedThemeSource, revision)
    }

    /** Builds the host payload from a captured snapshot; kept separate for contract regression tests. */
    internal fun runtimeSettingsFromSnapshot(
        snapshot: MarkFlowIdeThemeService.Snapshot,
        resolvedThemeSource: String = resolveThemeSourceForRuntime(),
        revision: Int = settingsRevision.get()
    ): MarkFlowRuntimeSettings {
        normalize()
        return MarkFlowRuntimeSettings(
            mermaidSizeMode = state.mermaidSizeMode,
            mermaidZoomPercent = state.mermaidZoomPercent,
            themeSource = resolvedThemeSource,
            mermaidErrorDisplay = state.mermaidErrorDisplay,
            katexDisplayDensity = state.katexDisplayDensity,
            diagramSecurityLevel = state.diagramSecurityLevel,
            previewOnlyByDefault = state.previewOnlyByDefault,
            mermaidSyntaxErrorMessage = MyBundle.message("preview.mermaid.syntaxError"),
            fontFamily = resolveRuntimeFontFamily(state.fontFamily, snapshot.fonts["codeFont"].orEmpty()),
            baseFontSizePx = state.baseFontSizePx,
            ideColorScheme = snapshot.colors,
            ideFontFamily = snapshot.fonts["codeFont"],
            ideDark = snapshot.dark,
            settingsRevision = revision
        )
    }

    private fun resolveThemeSourceForRuntime(): String {
        if (state.themeSource != ThemeSource.IDE_SYNC.name) {
            if (MarkFlowDiagnostics.enabled) {
                LOG.warn("MARKFLOW_SETTINGS resolveTheme explicit=${state.themeSource}")
            }
            return state.themeSource
        }
        // IDE_SYNC = live IDE palette sync (Approach C): the IDE is the color source of
        // truth and the palette is always resolved on the backend side.
        if (MarkFlowDiagnostics.enabled) {
            LOG.warn("MARKFLOW_SETTINGS resolveTheme IDE_SYNC(palette)")
        }
        return ThemeSource.IDE_SYNC.name
    }

    private fun normalize(target: MarkFlowSettingsState = state) {
        normalizeState(target)
    }

    /**
     * Coerce every enum field to a known value and every numeric field to its valid range,
     * mutating [target] in place. Pure logic (no platform access) so it can be unit-tested
        * against a hand-built [MarkFlowSettingsState].
     */
    fun normalizeState(target: MarkFlowSettingsState): MarkFlowSettingsState {
        target.mermaidSizeMode = normalizeEnum(target.mermaidSizeMode, MermaidSizeMode.FIT_TO_VIEWPORT)
        target.themeSource = normalizeEnum(target.themeSource, ThemeSource.LIGHT)
        target.mermaidErrorDisplay = normalizeEnum(target.mermaidErrorDisplay, MermaidErrorDisplay.INLINE_ERROR_BOX)
        target.katexDisplayDensity = normalizeEnum(target.katexDisplayDensity, KatexDisplayDensity.COMFORTABLE)
        target.diagramSecurityLevel = normalizeEnum(target.diagramSecurityLevel, DiagramSecurityLevel.STRICT)
        target.mermaidZoomPercent = target.mermaidZoomPercent.coerceIn(50, 200)
        target.idleEvictAfterMs = target.idleEvictAfterMs.coerceIn(MIN_IDLE_EVICT_AFTER_MS, MAX_IDLE_EVICT_AFTER_MS)
        target.baseFontSizePx = target.baseFontSizePx.coerceIn(BASE_FONT_SIZE_MIN, BASE_FONT_SIZE_MAX)
        target.fontFamily = normalizeFontFamily(target.fontFamily)
        return target
    }

    /**
     * Normalize the user's MarkFlow body-font preference. The UI is a dropdown of installed fonts,
     * so the persisted value is a single family name (or empty for the IDE default). Trim only;
     * no CSS parsing is needed because the UI never emits a font stack.
     */
    private fun normalizeFontFamily(raw: String): String {
        return raw.trim()
    }

    /**
     * Do not send an unavailable persisted family to CSS. A setting synced from another machine
     * must immediately fall back to the active IDE editor family, without requiring the user to
     * open and re-apply this configurable.
     */
    private fun resolveRuntimeFontFamily(persisted: String, ideFontFamily: String): String {
        if (persisted.isBlank()) return ""
        return FontFamilyOptions.resolvePersistedValue(
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList(),
            ideFontFamily,
            persisted
        )
    }

    private inline fun <reified T : Enum<T>> normalizeEnum(raw: String, fallback: T): String {
        return enumValues<T>().firstOrNull { it.name == raw }?.name ?: fallback.name
    }

    companion object {
        private val LOG = Logger.getInstance(MarkFlowSettingsService::class.java)
        private val settingsRevision = AtomicInteger(1)

        const val DEFAULT_IDLE_EVICT_AFTER_MS = 120_000

        private const val MIN_IDLE_EVICT_AFTER_MS = 10_000
        private const val MAX_IDLE_EVICT_AFTER_MS = 3_600_000

        val BASE_FONT_SIZE_MIN: Int
            get() = EditorFontsConstants.getMinEditorFontSize()

        val BASE_FONT_SIZE_MAX: Int
            get() = EditorFontsConstants.getMaxEditorFontSize()
        const val DEFAULT_BASE_FONT_SIZE_PX = MarkFlowSettingsState.DEFAULT_BASE_FONT_SIZE_PX

        fun getInstance(): MarkFlowSettingsService {
            return ApplicationManager.getApplication().getService(MarkFlowSettingsService::class.java)
        }

        /**
         * Bumps the runtime-settings revision counter so open webviews re-fetch runtime settings.
         * Used by the IDE theme service when the live IDE palette changes (IDE_SYNC).
         */
        fun bumpRuntimeSettingsRevision(): Int {
            return settingsRevision.incrementAndGet()
        }
    }
}
