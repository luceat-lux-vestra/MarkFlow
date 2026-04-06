package com.algorist.markflow

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class MarkFlowSettingsConfigurable : Configurable {

    private var panel: JPanel? = null

    private lateinit var mermaidSizeModeCombo: ComboBox<MermaidSizeMode>
    private lateinit var mermaidZoomSpinner: JSpinner
    private lateinit var themeSourceCombo: ComboBox<ThemeSource>
    private lateinit var renderTriggerCombo: ComboBox<RenderTriggerMode>
    private lateinit var renderDebounceSpinner: JSpinner
    private lateinit var mermaidErrorDisplayCombo: ComboBox<MermaidErrorDisplay>
    private lateinit var katexDensityCombo: ComboBox<KatexDisplayDensity>
    private lateinit var diagramSecurityCombo: ComboBox<DiagramSecurityLevel>
    private lateinit var previewOnlyByDefaultCheckBox: JCheckBox
    private lateinit var forceRerenderShortcutEnabledCheckBox: JCheckBox
    private lateinit var maxPoolSizeSpinner: JSpinner
    private lateinit var idleEvictAfterMsSpinner: JSpinner

    override fun getDisplayName(): String = MyBundle.message("settings.markflow.displayName")

    override fun createComponent(): JComponent {
        panel?.let { return it }

        mermaidSizeModeCombo = enumCombo(MermaidSizeMode.entries.toTypedArray())
        mermaidZoomSpinner = JSpinner(
            SpinnerNumberModel(DEFAULT_ZOOM_PERCENT, ZOOM_MIN, ZOOM_MAX, ZOOM_STEP)
        )
        themeSourceCombo = enumCombo(ThemeSource.entries.toTypedArray())
        renderTriggerCombo = enumCombo(RenderTriggerMode.entries.toTypedArray())
        renderDebounceSpinner = JSpinner(
            SpinnerNumberModel(DEFAULT_DEBOUNCE_MS, DEBOUNCE_MIN, DEBOUNCE_MAX, DEBOUNCE_STEP)
        )
        mermaidErrorDisplayCombo = enumCombo(MermaidErrorDisplay.entries.toTypedArray())
        katexDensityCombo = enumCombo(KatexDisplayDensity.entries.toTypedArray())
        diagramSecurityCombo = enumCombo(DiagramSecurityLevel.entries.toTypedArray())
        previewOnlyByDefaultCheckBox = JCheckBox()
        forceRerenderShortcutEnabledCheckBox = JCheckBox()
        maxPoolSizeSpinner = JSpinner(
            SpinnerNumberModel(DEFAULT_MAX_POOL_SIZE, MAX_POOL_SIZE_MIN, MAX_POOL_SIZE_MAX, MAX_POOL_SIZE_STEP)
        )
        idleEvictAfterMsSpinner = JSpinner(
            SpinnerNumberModel(DEFAULT_IDLE_EVICT_AFTER_MS, IDLE_EVICT_MIN, IDLE_EVICT_MAX, IDLE_EVICT_STEP)
        )

        val root = JPanel(GridBagLayout())
        root.border = JBUI.Borders.empty(PANEL_PADDING)
        var row = 0

        row = addSection(root, row, MyBundle.message("settings.markflow.section.general"))
        row = addRow(root, row, MyBundle.message("settings.markflow.themeSource"), themeSourceCombo)
        row = addRow(root, row, MyBundle.message("settings.markflow.renderTrigger"), renderTriggerCombo)
        row = addRow(root, row, MyBundle.message("settings.markflow.renderDebounceMs"), renderDebounceSpinner)
        row = addRow(
            root,
            row,
            MyBundle.message("settings.markflow.previewOnlyByDefault"),
            previewOnlyByDefaultCheckBox
        )
        row = addRow(root, row, "Force Re-render Shortcut (Cmd/Ctrl+Alt+Shift+R)", forceRerenderShortcutEnabledCheckBox)

        row = addSection(root, row, MyBundle.message("settings.markflow.section.mermaid"))
        row = addRow(root, row, MyBundle.message("settings.markflow.mermaid.sizeMode"), mermaidSizeModeCombo)
        row = addRow(root, row, MyBundle.message("settings.markflow.mermaid.zoomPercent"), mermaidZoomSpinner)
        row = addRow(root, row, MyBundle.message("settings.markflow.mermaid.errorDisplay"), mermaidErrorDisplayCombo)

        row = addSection(root, row, MyBundle.message("settings.markflow.section.katex"))
        row = addRow(root, row, MyBundle.message("settings.markflow.katex.displayDensity"), katexDensityCombo)

        row = addSection(root, row, MyBundle.message("settings.markflow.section.advanced"))
        row = addRow(root, row, MyBundle.message("settings.markflow.diagram.securityLevel"), diagramSecurityCombo)
        row = addRow(
            root,
            row,
            MyBundle.message("settings.markflow.browserPoolSize"),
            maxPoolSizeSpinner,
            MyBundle.message("settings.markflow.browserPoolSize.tooltip")
        )
        row = addRow(
            root,
            row,
            MyBundle.message("settings.markflow.idleBrowserEvictAfterMs"),
            idleEvictAfterMsSpinner,
            MyBundle.message("settings.markflow.idleBrowserEvictAfterMs.tooltip")
        )

        val spacer = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            gridwidth = 2
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        }
        root.add(JPanel(), spacer)

        panel = root
        reset()
        return root
    }

    override fun isModified(): Boolean {
        val state = MarkFlowSettingsService.getInstance().state
        return state.mermaidSizeMode != selectedName(mermaidSizeModeCombo)
            || state.mermaidZoomPercent != spinnerInt(mermaidZoomSpinner)
            || state.themeSource != selectedName(themeSourceCombo)
            || state.renderTriggerMode != selectedName(renderTriggerCombo)
            || state.renderDebounceMs != spinnerInt(renderDebounceSpinner)
            || state.mermaidErrorDisplay != selectedName(mermaidErrorDisplayCombo)
            || state.katexDisplayDensity != selectedName(katexDensityCombo)
            || state.diagramSecurityLevel != selectedName(diagramSecurityCombo)
            || state.previewOnlyByDefault != previewOnlyByDefaultCheckBox.isSelected
            || state.forceRerenderShortcutEnabled != forceRerenderShortcutEnabledCheckBox.isSelected
            || state.maxPoolSize != spinnerInt(maxPoolSizeSpinner)
            || state.idleEvictAfterMs != spinnerInt(idleEvictAfterMsSpinner)
    }

    override fun apply() {
        val updated = MarkFlowSettingsState(
            mermaidSizeMode = selectedName(mermaidSizeModeCombo),
            mermaidZoomPercent = spinnerInt(mermaidZoomSpinner),
            themeSource = selectedName(themeSourceCombo),
            renderTriggerMode = selectedName(renderTriggerCombo),
            renderDebounceMs = spinnerInt(renderDebounceSpinner),
            mermaidErrorDisplay = selectedName(mermaidErrorDisplayCombo),
            katexDisplayDensity = selectedName(katexDensityCombo),
            diagramSecurityLevel = selectedName(diagramSecurityCombo),
            previewOnlyByDefault = previewOnlyByDefaultCheckBox.isSelected,
            forceRerenderShortcutEnabled = forceRerenderShortcutEnabledCheckBox.isSelected,
            maxPoolSize = spinnerInt(maxPoolSizeSpinner),
            idleEvictAfterMs = spinnerInt(idleEvictAfterMsSpinner)
        )
        LOG.warn(
            "MARKFLOW_SETTINGS_UI apply themeSource=${updated.themeSource}, " +
                "renderTrigger=${updated.renderTriggerMode}, debounceMs=${updated.renderDebounceMs}, " +
                "security=${updated.diagramSecurityLevel}, " +
                "forceRerenderShortcutEnabled=${updated.forceRerenderShortcutEnabled}, " +
                "maxPoolSize=${updated.maxPoolSize}, idleEvictAfterMs=${updated.idleEvictAfterMs}"
        )
        MarkFlowSettingsService.getInstance().updateFromUi(updated)
    }

    override fun reset() {
        val state = MarkFlowSettingsService.getInstance().state
        setSelectedByName(mermaidSizeModeCombo, state.mermaidSizeMode, MermaidSizeMode.FIT_TO_VIEWPORT)
        mermaidZoomSpinner.value = state.mermaidZoomPercent.coerceIn(ZOOM_MIN, ZOOM_MAX)
        setSelectedByName(themeSourceCombo, state.themeSource, ThemeSource.LIGHT)
        setSelectedByName(renderTriggerCombo, state.renderTriggerMode, RenderTriggerMode.LIVE)
        renderDebounceSpinner.value = state.renderDebounceMs.coerceIn(DEBOUNCE_MIN, DEBOUNCE_MAX)
        setSelectedByName(mermaidErrorDisplayCombo, state.mermaidErrorDisplay, MermaidErrorDisplay.INLINE_ERROR_BOX)
        setSelectedByName(katexDensityCombo, state.katexDisplayDensity, KatexDisplayDensity.COMFORTABLE)
        setSelectedByName(diagramSecurityCombo, state.diagramSecurityLevel, DiagramSecurityLevel.STRICT)
        previewOnlyByDefaultCheckBox.isSelected = state.previewOnlyByDefault
        forceRerenderShortcutEnabledCheckBox.isSelected = state.forceRerenderShortcutEnabled
        maxPoolSizeSpinner.value = state.maxPoolSize.coerceIn(MAX_POOL_SIZE_MIN, MAX_POOL_SIZE_MAX)
        idleEvictAfterMsSpinner.value = state.idleEvictAfterMs.coerceIn(IDLE_EVICT_MIN, IDLE_EVICT_MAX)
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun addSection(root: JPanel, row: Int, title: String): Int {
        val nextRow = if (row > 0) {
            addSectionSeparator(root, row)
        } else {
            row
        }
        val label = JLabel(title)
        label.font = label.font.deriveFont(Font.BOLD, label.font.size2D + SECTION_FONT_DELTA)
        label.border = JBUI.Borders.empty(TOP_SECTION_GAP, 0, SECTION_BOTTOM_GAP, 0)
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = nextRow
            gridwidth = 2
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(0)
        }
        root.add(label, constraints)
        return nextRow + 1
    }

    private fun addSectionSeparator(root: JPanel, row: Int): Int {
        val separator = JSeparator()
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            gridwidth = 2
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(SECTION_SEPARATOR_TOP_GAP, 0, 0, 0)
        }
        root.add(separator, constraints)
        return row + 1
    }

    private fun addRow(root: JPanel, row: Int, labelText: String, input: JComponent, tooltip: String? = null): Int {
        val label = JLabel(labelText)
        if (!tooltip.isNullOrBlank()) {
            label.toolTipText = tooltip
            input.toolTipText = tooltip
        }
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(ROW_VERTICAL_PADDING, ROW_LEFT_INDENT, ROW_VERTICAL_PADDING, LABEL_RIGHT_GAP)
        }
        root.add(label, labelConstraints)

        val inputConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(ROW_VERTICAL_PADDING, INPUT_LEFT_INDENT, ROW_VERTICAL_PADDING, 0)
        }
        root.add(input, inputConstraints)
        return row + 1
    }

    private fun spinnerInt(spinner: JSpinner): Int = (spinner.value as? Int) ?: 0

    private fun <T : Enum<T>> selectedName(comboBox: ComboBox<T>): String {
        return (comboBox.selectedItem as? Enum<*>)?.name.orEmpty()
    }

    private fun <T : Enum<T>> setSelectedByName(comboBox: ComboBox<T>, raw: String, fallback: T) {
        val match = (0 until comboBox.itemCount)
            .asSequence()
            .mapNotNull { comboBox.getItemAt(it) }
            .firstOrNull { it.name == raw }
        comboBox.selectedItem = match ?: fallback
    }

    private fun <T : Enum<T>> enumCombo(values: Array<T>): ComboBox<T> = ComboBox(values)

    private companion object {
        private val LOG = Logger.getInstance(MarkFlowSettingsConfigurable::class.java)

        private const val PANEL_PADDING = 8
        private const val TOP_SECTION_GAP = 10
        private const val SECTION_BOTTOM_GAP = 4
        private const val SECTION_FONT_DELTA = 1.0f
        private const val SECTION_SEPARATOR_TOP_GAP = 8
        private const val ROW_VERTICAL_PADDING = 4
        private const val ROW_LEFT_INDENT = 14
        private const val INPUT_LEFT_INDENT = 8
        private const val LABEL_RIGHT_GAP = 12

        private const val DEFAULT_ZOOM_PERCENT = 100
        private const val ZOOM_MIN = 50
        private const val ZOOM_MAX = 200
        private const val ZOOM_STEP = 10

        private const val DEFAULT_DEBOUNCE_MS = 500
        private const val DEBOUNCE_MIN = 300
        private const val DEBOUNCE_MAX = 800
        private const val DEBOUNCE_STEP = 50

        private const val DEFAULT_MAX_POOL_SIZE = MarkFlowSettingsService.DEFAULT_MAX_POOL_SIZE
        private const val MAX_POOL_SIZE_MIN = 1
        private const val MAX_POOL_SIZE_MAX = 16
        private const val MAX_POOL_SIZE_STEP = 1

        private const val DEFAULT_IDLE_EVICT_AFTER_MS = MarkFlowSettingsService.DEFAULT_IDLE_EVICT_AFTER_MS
        private const val IDLE_EVICT_MIN = 10_000
        private const val IDLE_EVICT_MAX = 3_600_000
        private const val IDLE_EVICT_STEP = 10_000
    }
}
