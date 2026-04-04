package com.algorist.markflow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.concurrent.atomic.AtomicInteger

enum class MermaidSizeMode {
    FIT_TO_VIEWPORT,
    ACTUAL_SIZE_SCROLL
}

enum class ThemeSource {
    IDE_SYNC,
    LIGHT,
    DARK
}

enum class RenderTriggerMode {
    LIVE,
    DEBOUNCED,
    MANUAL_REFRESH
}

enum class BackgroundPreviewPolicy {
    ALWAYS_RENDER,
    PAUSE_WHEN_TAB_INACTIVE
}

enum class MermaidErrorDisplay {
    INLINE_ERROR_BOX,
    SILENT_LOG_ONLY
}

enum class KatexDisplayDensity {
    COMPACT,
    COMFORTABLE
}

enum class DiagramSecurityLevel {
    STRICT,
    LOOSE
}

data class MarkFlowSettingsState(
    var mermaidSizeMode: String = MermaidSizeMode.FIT_TO_VIEWPORT.name,
    var mermaidZoomPercent: Int = 100,
    var themeSource: String = ThemeSource.LIGHT.name,
    var renderTriggerMode: String = RenderTriggerMode.LIVE.name,
    var renderDebounceMs: Int = 500,
    var backgroundPreviewPolicy: String = BackgroundPreviewPolicy.PAUSE_WHEN_TAB_INACTIVE.name,
    var mermaidErrorDisplay: String = MermaidErrorDisplay.INLINE_ERROR_BOX.name,
    var katexDisplayDensity: String = KatexDisplayDensity.COMFORTABLE.name,
    var diagramSecurityLevel: String = DiagramSecurityLevel.STRICT.name
)

data class MarkFlowRuntimeSettings(
    val mermaidSizeMode: String,
    val mermaidZoomPercent: Int,
    val themeSource: String,
    val renderTriggerMode: String,
    val renderDebounceMs: Int,
    val backgroundPreviewPolicy: String,
    val mermaidErrorDisplay: String,
    val katexDisplayDensity: String,
    val diagramSecurityLevel: String,
    val manualRenderToolbarLabel: String,
    val manualRenderInlineLabel: String,
    val manualRenderShortcutHint: String,
    val previewPausedMessage: String,
    val mermaidSyntaxErrorMessage: String,
    val settingsRevision: Int
)

@State(name = "MarkFlowSettings", storages = [Storage("markflow.xml")])
class MarkFlowSettingsService : PersistentStateComponent<MarkFlowSettingsState> {

    private var state = MarkFlowSettingsState()

    override fun getState(): MarkFlowSettingsState = state

    override fun loadState(state: MarkFlowSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
        normalize()
        settingsRevision.incrementAndGet()
        LOG.warn(
            "MARKFLOW_SETTINGS loadState themeSource=${this.state.themeSource}, " +
                "renderTrigger=${this.state.renderTriggerMode}, debounceMs=${this.state.renderDebounceMs}"
        )
    }

    fun updateFromUi(newState: MarkFlowSettingsState) {
        val previousThemeSource = state.themeSource
        val previousSecurityLevel = state.diagramSecurityLevel
        XmlSerializerUtil.copyBean(newState, state)
        normalize()
        val nextRevision = settingsRevision.incrementAndGet()
        val requiresWebviewReload =
            previousThemeSource != state.themeSource || previousSecurityLevel != state.diagramSecurityLevel
        LOG.warn(
            "MARKFLOW_SETTINGS updateFromUi themeSource=$previousThemeSource -> ${state.themeSource}, " +
                "security=$previousSecurityLevel -> ${state.diagramSecurityLevel}, " +
                "renderTrigger=${state.renderTriggerMode}, debounceMs=${state.renderDebounceMs}, " +
                "revision=$nextRevision, requiresReload=$requiresWebviewReload"
        )
        MarkFlowEditor.notifyRuntimeSettingsChanged(requiresWebviewReload)
    }

    fun runtimeSettings(): MarkFlowRuntimeSettings {
        normalize()
        val resolvedThemeSource = resolveThemeSourceForRuntime()
        val revision = settingsRevision.get()
        LOG.warn(
            "MARKFLOW_SETTINGS runtimeSettings themeSource=${state.themeSource}, " +
                "resolvedThemeSource=$resolvedThemeSource, security=${state.diagramSecurityLevel}, revision=$revision"
        )
        return MarkFlowRuntimeSettings(
            mermaidSizeMode = state.mermaidSizeMode,
            mermaidZoomPercent = state.mermaidZoomPercent,
            themeSource = resolvedThemeSource,
            renderTriggerMode = state.renderTriggerMode,
            renderDebounceMs = state.renderDebounceMs,
            backgroundPreviewPolicy = state.backgroundPreviewPolicy,
            mermaidErrorDisplay = state.mermaidErrorDisplay,
            katexDisplayDensity = state.katexDisplayDensity,
            diagramSecurityLevel = state.diagramSecurityLevel,
            manualRenderToolbarLabel = MyBundle.message("preview.manual.toolbarButton"),
            manualRenderInlineLabel = MyBundle.message("preview.manual.inlineButton"),
            manualRenderShortcutHint = MyBundle.message("preview.manual.shortcutHint"),
            previewPausedMessage = MyBundle.message("preview.paused"),
            mermaidSyntaxErrorMessage = MyBundle.message("preview.mermaid.syntaxError"),
            settingsRevision = revision
        )
    }

    private fun resolveThemeSourceForRuntime(): String {
        if (state.themeSource != ThemeSource.IDE_SYNC.name) {
            LOG.warn("MARKFLOW_SETTINGS resolveTheme explicit=${state.themeSource}")
            return state.themeSource
        }
        val ideResolved = if (EditorColorsManager.getInstance().isDarkEditor) {
            ThemeSource.DARK.name
        } else {
            ThemeSource.LIGHT.name
        }
        LOG.warn("MARKFLOW_SETTINGS resolveTheme IDE_SYNC->$ideResolved")
        return ideResolved
    }

    private fun normalize() {
        state.mermaidSizeMode = normalizeEnum(state.mermaidSizeMode, MermaidSizeMode.FIT_TO_VIEWPORT)
        state.themeSource = normalizeEnum(state.themeSource, ThemeSource.LIGHT)
        state.renderTriggerMode = normalizeEnum(state.renderTriggerMode, RenderTriggerMode.LIVE)
        state.backgroundPreviewPolicy = normalizeEnum(state.backgroundPreviewPolicy, BackgroundPreviewPolicy.PAUSE_WHEN_TAB_INACTIVE)
        state.mermaidErrorDisplay = normalizeEnum(state.mermaidErrorDisplay, MermaidErrorDisplay.INLINE_ERROR_BOX)
        state.katexDisplayDensity = normalizeEnum(state.katexDisplayDensity, KatexDisplayDensity.COMFORTABLE)
        state.diagramSecurityLevel = normalizeEnum(state.diagramSecurityLevel, DiagramSecurityLevel.STRICT)
        state.mermaidZoomPercent = state.mermaidZoomPercent.coerceIn(50, 200)
        state.renderDebounceMs = state.renderDebounceMs.coerceIn(300, 800)
    }

    private inline fun <reified T : Enum<T>> normalizeEnum(raw: String, fallback: T): String {
        return enumValues<T>().firstOrNull { it.name == raw }?.name ?: fallback.name
    }

    companion object {
        private val LOG = Logger.getInstance(MarkFlowSettingsService::class.java)
        private val settingsRevision = AtomicInteger(1)

        fun getInstance(): MarkFlowSettingsService {
            return ApplicationManager.getApplication().getService(MarkFlowSettingsService::class.java)
        }
    }
}
