package com.algorist.markflow.settings

import com.intellij.application.options.EditorFontsConstants
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Font
import java.text.ParseException
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.JList
import javax.swing.DefaultListCellRenderer
import com.algorist.markflow.MyBundle
import com.algorist.markflow.MarkFlowDiagnostics
import com.algorist.markflow.settings.state.DiagramSecurityLevel
import com.algorist.markflow.settings.state.KatexDisplayDensity
import com.algorist.markflow.settings.state.MermaidErrorDisplay
import com.algorist.markflow.settings.state.MermaidSizeMode
import com.algorist.markflow.settings.state.ThemeSource

class MarkFlowSettingsConfigurable : Configurable {

    private var panel: JPanel? = null

    private lateinit var mermaidSizeModeCombo: ComboBox<MermaidSizeMode>
    private lateinit var mermaidZoomSpinner: JSpinner
    private lateinit var themeSourceCombo: ComboBox<ThemeSource>
    private lateinit var mermaidErrorDisplayCombo: ComboBox<MermaidErrorDisplay>
    private lateinit var katexDensityCombo: ComboBox<KatexDisplayDensity>
    private lateinit var diagramSecurityCombo: ComboBox<DiagramSecurityLevel>
    private lateinit var previewOnlyByDefaultCheckBox: JCheckBox
    private lateinit var idleEvictAfterMsSpinner: JSpinner
    private lateinit var fontFamilyCombo: ComboBox<FontFamilyOption>
    private lateinit var baseFontSizeSpinner: JSpinner

    override fun getDisplayName(): String = MyBundle.message("settings.markflow.displayName")

    override fun createComponent(): JComponent {
        panel?.let { return it }

        mermaidSizeModeCombo = enumCombo(MermaidSizeMode.entries.toTypedArray())
        mermaidZoomSpinner = JSpinner(
            SpinnerNumberModel(DEFAULT_ZOOM_PERCENT, ZOOM_MIN, ZOOM_MAX, ZOOM_STEP)
        )
        themeSourceCombo = enumCombo(ThemeSource.entries.toTypedArray())
        mermaidErrorDisplayCombo = enumCombo(MermaidErrorDisplay.entries.toTypedArray())
        katexDensityCombo = enumCombo(KatexDisplayDensity.entries.toTypedArray())
        diagramSecurityCombo = enumCombo(DiagramSecurityLevel.entries.toTypedArray())
        previewOnlyByDefaultCheckBox = JCheckBox()
        idleEvictAfterMsSpinner = JSpinner(
            SpinnerNumberModel(DEFAULT_IDLE_EVICT_AFTER_MS, IDLE_EVICT_MIN, IDLE_EVICT_MAX, IDLE_EVICT_STEP)
        )
        fontFamilyCombo = fontCombo(
            FontFamilyOptions.build(
                GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toList(),
                MarkFlowIdeThemeService.getInstance().getSnapshot().fonts["codeFont"].orEmpty()
            )
        )
        baseFontSizeSpinner = JSpinner(
            SpinnerNumberModel(DEFAULT_BASE_FONT_SIZE_PX, BASE_FONT_SIZE_MIN, BASE_FONT_SIZE_MAX, 1)
        )

        val root = JPanel(GridBagLayout())
        root.border = JBUI.Borders.empty(PANEL_PADDING)
        var row = 0

        row = addSection(root, row, MyBundle.message("settings.markflow.section.general"))
        row = addRow(
            root,
            row,
            MyBundle.message("settings.markflow.previewOnlyByDefault"),
            previewOnlyByDefaultCheckBox
        )

        row = addSection(root, row, MyBundle.message("settings.markflow.section.appearance"))
        row = addRow(root, row, MyBundle.message("settings.markflow.themeSource"), themeSourceCombo)
        row = addRow(root, row, MyBundle.message("settings.markflow.fontFamily"), fontFamilyCombo)
        row = addRow(
            root,
            row,
            MyBundle.message("settings.markflow.baseFontSize"),
            baseFontSizeSpinner,
            baseFontSizeTooltip()
        )

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
            || state.fontFamily != selectedValue(fontFamilyCombo)
            || state.baseFontSizePx != spinnerInt(baseFontSizeSpinner)
            || state.mermaidErrorDisplay != selectedName(mermaidErrorDisplayCombo)
            || state.katexDisplayDensity != selectedName(katexDensityCombo)
            || state.diagramSecurityLevel != selectedName(diagramSecurityCombo)
            || state.previewOnlyByDefault != previewOnlyByDefaultCheckBox.isSelected
            || state.idleEvictAfterMs != spinnerInt(idleEvictAfterMsSpinner)
    }

    override fun apply() {
        val service = MarkFlowSettingsService.getInstance()
        // Copy the existing state and override only the fields shown here. Starting from the live
        // state guarantees no field (including typography) is silently reset to a constructor default.
        val updated = service.state.copy(
            mermaidSizeMode = selectedName(mermaidSizeModeCombo),
            mermaidZoomPercent = spinnerInt(mermaidZoomSpinner),
            themeSource = selectedName(themeSourceCombo),
            fontFamily = selectedValue(fontFamilyCombo),
            baseFontSizePx = spinnerInt(baseFontSizeSpinner),
            mermaidErrorDisplay = selectedName(mermaidErrorDisplayCombo),
            katexDisplayDensity = selectedName(katexDensityCombo),
            diagramSecurityLevel = selectedName(diagramSecurityCombo),
            previewOnlyByDefault = previewOnlyByDefaultCheckBox.isSelected,
            idleEvictAfterMs = spinnerInt(idleEvictAfterMsSpinner)
        )
        if (MarkFlowDiagnostics.enabled) {
            LOG.warn(
                "MARKFLOW_SETTINGS_UI apply themeSource=${updated.themeSource}, " +
                    "security=${updated.diagramSecurityLevel}, " +
                    "fontFamily=${updated.fontFamily}, baseFontSizePx=${updated.baseFontSizePx}, " +
                    "idleEvictAfterMs=${updated.idleEvictAfterMs}"
            )
        }
        service.updateFromUi(updated)
    }

    override fun reset() {
        val state = MarkFlowSettingsService.getInstance().state
        setSelectedByName(mermaidSizeModeCombo, state.mermaidSizeMode, MermaidSizeMode.FIT_TO_VIEWPORT)
        mermaidZoomSpinner.value = state.mermaidZoomPercent.coerceIn(ZOOM_MIN, ZOOM_MAX)
        setSelectedByName(themeSourceCombo, state.themeSource, ThemeSource.LIGHT)
        fontFamilyCombo.selectedItem = resolveFontFamily(state.fontFamily)
        baseFontSizeSpinner.value = state.baseFontSizePx.coerceIn(BASE_FONT_SIZE_MIN, BASE_FONT_SIZE_MAX)
        setSelectedByName(mermaidErrorDisplayCombo, state.mermaidErrorDisplay, MermaidErrorDisplay.INLINE_ERROR_BOX)
        setSelectedByName(katexDensityCombo, state.katexDisplayDensity, KatexDisplayDensity.COMFORTABLE)
        setSelectedByName(diagramSecurityCombo, state.diagramSecurityLevel, DiagramSecurityLevel.STRICT)
        previewOnlyByDefaultCheckBox.isSelected = state.previewOnlyByDefault
        idleEvictAfterMsSpinner.value = state.idleEvictAfterMs.coerceIn(IDLE_EVICT_MIN, IDLE_EVICT_MAX)
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun fontCombo(options: List<FontFamilyOption>): ComboBox<FontFamilyOption> {
        return ComboBox(options.toTypedArray()).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<out Any>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    hasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, hasFocus)
                    if (value is FontFamilyOption) setText(value.displayName)
                    return this
                }
            }
        }
    }

    /** Resolves a persisted family name against the dropdown's current options, defaulting when absent. */
    private fun resolveFontFamily(persisted: String): FontFamilyOption {
        val items = (0 until fontFamilyCombo.itemCount).map { fontFamilyCombo.getItemAt(it) }
        return FontFamilyOptions.resolve(items, persisted)
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

    private fun spinnerInt(spinner: JSpinner): Int {
        // JSpinner keeps direct text edits in its editor until the editor is committed. Without
        // this, isModified() sees the old model value and the Settings dialog leaves Apply disabled.
        try {
            spinner.commitEdit()
        } catch (_: ParseException) {
            // Keep the last valid model value for incomplete input; numeric text is clamped below.
            clampTypedSpinnerValue(spinner)
        } catch (_: IllegalArgumentException) {
            // SpinnerNumberModel rejects values outside its configured range. Clamp the typed
            // value back into the model so Apply still persists the platform boundary.
            clampTypedSpinnerValue(spinner)
        }
        return (spinner.value as? Number)?.toInt() ?: 0
    }

    private fun clampTypedSpinnerValue(spinner: JSpinner) {
        val textField = (spinner.editor as? JSpinner.DefaultEditor)?.textField ?: return
        val model = spinner.model as? SpinnerNumberModel ?: return
        val typed = textField.text.trim().toIntOrNull() ?: return
        val minimum = (model.minimum as? Number)?.toInt() ?: return
        val maximum = (model.maximum as? Number)?.toInt() ?: return
        spinner.value = typed.coerceIn(minimum, maximum)
    }

    private fun <T : Enum<T>> selectedName(comboBox: ComboBox<T>): String {
        return (comboBox.selectedItem as? Enum<*>)?.name.orEmpty()
    }

    private fun selectedValue(comboBox: ComboBox<FontFamilyOption>): String {
        return (comboBox.selectedItem as? FontFamilyOption)?.value.orEmpty()
    }

    private fun baseFontSizeTooltip(): String {
        return MyBundle.message("settings.markflow.baseFontSize.tooltip") +
            " (${EditorFontsConstants.getMinEditorFontSize()}–${EditorFontsConstants.getMaxEditorFontSize()})"
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

        private const val DEFAULT_BASE_FONT_SIZE_PX = MarkFlowSettingsService.DEFAULT_BASE_FONT_SIZE_PX
        private val BASE_FONT_SIZE_MIN: Int
            get() = EditorFontsConstants.getMinEditorFontSize()
        private val BASE_FONT_SIZE_MAX: Int
            get() = EditorFontsConstants.getMaxEditorFontSize()

        private const val DEFAULT_IDLE_EVICT_AFTER_MS = MarkFlowSettingsService.DEFAULT_IDLE_EVICT_AFTER_MS
        private const val IDLE_EVICT_MIN = 10_000
        private const val IDLE_EVICT_MAX = 3_600_000
        private const val IDLE_EVICT_STEP = 10_000
    }
}
