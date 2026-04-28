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
    ACTUAL_SIZE_SCROLL,
    SHRINK_TO_FIT
}

enum class ThemeSource {
    IDE_SYNC,
    LIGHT,
    DARK
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
    var mermaidErrorDisplay: String = MermaidErrorDisplay.INLINE_ERROR_BOX.name,
    var katexDisplayDensity: String = KatexDisplayDensity.COMFORTABLE.name,
    var diagramSecurityLevel: String = DiagramSecurityLevel.STRICT.name,
    var previewOnlyByDefault: Boolean = true,
    var maxPoolSize: Int = 4,
    var idleEvictAfterMs: Int = 120_000
)

data class MarkFlowRuntimeSettings(
    val mermaidSizeMode: String,
    val mermaidZoomPercent: Int,
    val themeSource: String,
    val mermaidErrorDisplay: String,
    val katexDisplayDensity: String,
    val diagramSecurityLevel: String,
    val previewOnlyByDefault: Boolean,
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
                "diagramSecurityLevel=${this.state.diagramSecurityLevel}"
        )
    }

    fun updateFromUi(newState: MarkFlowSettingsState) {
        val previousState = state.copy().also { normalize(it) }
        XmlSerializerUtil.copyBean(newState, state)
        normalize()
        val changed = previousState != state
        val nextRevision = if (changed) settingsRevision.incrementAndGet() else settingsRevision.get()
        LOG.warn(
            "MARKFLOW_SETTINGS updateFromUi changed=$changed, " +
                "themeSource=${previousState.themeSource} -> ${state.themeSource}, " +
                "security=${previousState.diagramSecurityLevel} -> ${state.diagramSecurityLevel}, " +
                "revision=$nextRevision"
        )
        if (changed) {
            MarkFlowSharedBrowserService.notifyRuntimeSettingsChanged(forceReload = false)
        }
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
            mermaidErrorDisplay = state.mermaidErrorDisplay,
            katexDisplayDensity = state.katexDisplayDensity,
            diagramSecurityLevel = state.diagramSecurityLevel,
            previewOnlyByDefault = state.previewOnlyByDefault,
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

    private fun normalize(target: MarkFlowSettingsState = state) {
        target.mermaidSizeMode = normalizeEnum(target.mermaidSizeMode, MermaidSizeMode.FIT_TO_VIEWPORT)
        target.themeSource = normalizeEnum(target.themeSource, ThemeSource.LIGHT)
        target.mermaidErrorDisplay = normalizeEnum(target.mermaidErrorDisplay, MermaidErrorDisplay.INLINE_ERROR_BOX)
        target.katexDisplayDensity = normalizeEnum(target.katexDisplayDensity, KatexDisplayDensity.COMFORTABLE)
        target.diagramSecurityLevel = normalizeEnum(target.diagramSecurityLevel, DiagramSecurityLevel.STRICT)
        target.mermaidZoomPercent = target.mermaidZoomPercent.coerceIn(50, 200)
        target.maxPoolSize = target.maxPoolSize.coerceIn(MIN_POOL_SIZE, MAX_POOL_SIZE_LIMIT)
        target.idleEvictAfterMs = target.idleEvictAfterMs.coerceIn(MIN_IDLE_EVICT_AFTER_MS, MAX_IDLE_EVICT_AFTER_MS)
    }

    private inline fun <reified T : Enum<T>> normalizeEnum(raw: String, fallback: T): String {
        return enumValues<T>().firstOrNull { it.name == raw }?.name ?: fallback.name
    }

    companion object {
        private val LOG = Logger.getInstance(MarkFlowSettingsService::class.java)
        private val settingsRevision = AtomicInteger(1)

        const val DEFAULT_MAX_POOL_SIZE = 4
        const val DEFAULT_IDLE_EVICT_AFTER_MS = 120_000

        private const val MIN_POOL_SIZE = 1
        private const val MAX_POOL_SIZE_LIMIT = 16
        private const val MIN_IDLE_EVICT_AFTER_MS = 10_000
        private const val MAX_IDLE_EVICT_AFTER_MS = 3_600_000

        fun getInstance(): MarkFlowSettingsService {
            return ApplicationManager.getApplication().getService(MarkFlowSettingsService::class.java)
        }
    }
}
