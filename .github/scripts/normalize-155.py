from pathlib import Path


def load(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def save(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old form, found {count}")
    return text.replace(old, new, 1)


# Settings service: retain presentation identity/revision, remove browser-era knobs.
p = "src/main/kotlin/com/algorist/markflow/settings/MarkFlowSettingsService.kt"
t = load(p)
t = one(t, "import com.algorist.markflow.settings.state.DiagramSecurityLevel\n", "", "service security import")
t = one(
    t,
    '''            LOG.warn(\n                "MARKFLOW_SETTINGS loadState themeSource=${this.state.themeSource}, " +\n                    "diagramSecurityLevel=${this.state.diagramSecurityLevel}"\n            )''',
    '''            LOG.warn("MARKFLOW_SETTINGS loadState themeSource=${this.state.themeSource}")''',
    "service load log",
)
t = one(
    t,
    '''                "MARKFLOW_SETTINGS updateFromUi changed=$changed, " +\n                    "themeSource=${previousState.themeSource} -> ${state.themeSource}, " +\n                    "security=${previousState.diagramSecurityLevel} -> ${state.diagramSecurityLevel}, " +\n                    "revision=$nextRevision"''',
    '''                "MARKFLOW_SETTINGS updateFromUi changed=$changed, " +\n                    "themeSource=${previousState.themeSource} -> ${state.themeSource}, revision=$nextRevision"''',
    "service update log",
)
t = one(t, "MarkFlowRuntimeSettingsNotifier.notifyChanged(forceReload = false)", "MarkFlowPresentationSettingsNotifier.notifyChanged()", "service notifier")
t = one(
    t,
    '''                "MARKFLOW_SETTINGS runtimeSettings themeSource=${state.themeSource}, " +\n                    "resolvedThemeSource=$resolvedThemeSource, security=${state.diagramSecurityLevel}, revision=$revision"''',
    '''                "MARKFLOW_SETTINGS runtimeSettings themeSource=${state.themeSource}, " +\n                    "resolvedThemeSource=$resolvedThemeSource, revision=$revision"''',
    "service runtime log",
)
t = one(t, "            diagramSecurityLevel = state.diagramSecurityLevel,\n            previewOnlyByDefault = state.previewOnlyByDefault,\n", "", "service legacy payload")
t = one(t, "        target.diagramSecurityLevel = normalizeEnum(target.diagramSecurityLevel, DiagramSecurityLevel.STRICT)\n", "", "service security normalize")
t = one(t, "        target.idleEvictAfterMs = target.idleEvictAfterMs.coerceIn(MIN_IDLE_EVICT_AFTER_MS, MAX_IDLE_EVICT_AFTER_MS)\n", "", "service idle normalize")
t = one(
    t,
    "        const val DEFAULT_IDLE_EVICT_AFTER_MS = 120_000\n\n        private const val MIN_IDLE_EVICT_AFTER_MS = 10_000\n        private const val MAX_IDLE_EVICT_AFTER_MS = 3_600_000\n\n",
    "",
    "service idle constants",
)
t = one(
    t,
    "         * Bumps the runtime-settings revision counter so open webviews re-fetch runtime settings.\n         * Used by the IDE theme service when the live IDE palette changes (IDE_SYNC).",
    "         * Bumps the presentation-settings revision so stale derived work is rejected.\n         * Used by the IDE theme service when the live IDE palette changes (IDE_SYNC).",
    "service revision comment",
)
save(p, t)

# Settings UI: only settings with proven native/renderer consumers remain.
p = "src/main/kotlin/com/algorist/markflow/settings/MarkFlowSettingsConfigurable.kt"
t = load(p)
t = one(t, "import javax.swing.JCheckBox\n", "", "ui checkbox import")
t = one(t, "import com.algorist.markflow.settings.state.DiagramSecurityLevel\n", "", "ui security import")
t = one(
    t,
    "    private lateinit var diagramSecurityCombo: ComboBox<DiagramSecurityLevel>\n    private lateinit var previewOnlyByDefaultCheckBox: JCheckBox\n    private lateinit var idleEvictAfterMsSpinner: JSpinner\n",
    "",
    "ui legacy fields",
)
t = one(
    t,
    "        diagramSecurityCombo = enumCombo(DiagramSecurityLevel.entries.toTypedArray())\n        previewOnlyByDefaultCheckBox = JCheckBox()\n        idleEvictAfterMsSpinner = JSpinner(\n            SpinnerNumberModel(DEFAULT_IDLE_EVICT_AFTER_MS, IDLE_EVICT_MIN, IDLE_EVICT_MAX, IDLE_EVICT_STEP)\n        )\n",
    "",
    "ui legacy initialization",
)
t = one(
    t,
    '''        row = addSection(root, row, MyBundle.message("settings.markflow.section.general"))\n        row = addRow(\n            root,\n            row,\n            MyBundle.message("settings.markflow.previewOnlyByDefault"),\n            previewOnlyByDefaultCheckBox\n        )\n\n''',
    "",
    "ui preview section",
)
t = one(
    t,
    '''        row = addSection(root, row, MyBundle.message("settings.markflow.section.advanced"))\n        row = addRow(root, row, MyBundle.message("settings.markflow.diagram.securityLevel"), diagramSecurityCombo)\n        row = addRow(\n            root,\n            row,\n            MyBundle.message("settings.markflow.idleBrowserEvictAfterMs"),\n            idleEvictAfterMsSpinner,\n            MyBundle.message("settings.markflow.idleBrowserEvictAfterMs.tooltip")\n        )\n\n''',
    "",
    "ui advanced section",
)
t = one(
    t,
    "            || state.katexDisplayDensity != selectedName(katexDensityCombo)\n            || state.diagramSecurityLevel != selectedName(diagramSecurityCombo)\n            || state.previewOnlyByDefault != previewOnlyByDefaultCheckBox.isSelected\n            || state.idleEvictAfterMs != spinnerInt(idleEvictAfterMsSpinner)",
    "            || state.katexDisplayDensity != selectedName(katexDensityCombo)",
    "ui modified legacy fields",
)
t = one(
    t,
    "            mermaidErrorDisplay = selectedName(mermaidErrorDisplayCombo),\n            katexDisplayDensity = selectedName(katexDensityCombo),\n            diagramSecurityLevel = selectedName(diagramSecurityCombo),\n            previewOnlyByDefault = previewOnlyByDefaultCheckBox.isSelected,\n            idleEvictAfterMs = spinnerInt(idleEvictAfterMsSpinner)\n",
    "            mermaidErrorDisplay = selectedName(mermaidErrorDisplayCombo),\n            katexDisplayDensity = selectedName(katexDensityCombo)\n",
    "ui apply legacy fields",
)
t = one(
    t,
    '''                "MARKFLOW_SETTINGS_UI apply themeSource=${updated.themeSource}, " +\n                    "security=${updated.diagramSecurityLevel}, " +\n                    "fontFamily=${updated.fontFamily}, baseFontSizePx=${updated.baseFontSizePx}, " +\n                    "idleEvictAfterMs=${updated.idleEvictAfterMs}"''',
    '''                "MARKFLOW_SETTINGS_UI apply themeSource=${updated.themeSource}, " +\n                    "fontFamily=${updated.fontFamily}, baseFontSizePx=${updated.baseFontSizePx}"''',
    "ui apply log",
)
t = one(
    t,
    "        setSelectedByName(katexDensityCombo, state.katexDisplayDensity, KatexDisplayDensity.COMFORTABLE)\n        setSelectedByName(diagramSecurityCombo, state.diagramSecurityLevel, DiagramSecurityLevel.STRICT)\n        previewOnlyByDefaultCheckBox.isSelected = state.previewOnlyByDefault\n        idleEvictAfterMsSpinner.value = state.idleEvictAfterMs.coerceIn(IDLE_EVICT_MIN, IDLE_EVICT_MAX)\n",
    "        setSelectedByName(katexDensityCombo, state.katexDisplayDensity, KatexDisplayDensity.COMFORTABLE)\n",
    "ui reset legacy fields",
)
t = one(
    t,
    "        private const val DEFAULT_IDLE_EVICT_AFTER_MS = MarkFlowSettingsService.DEFAULT_IDLE_EVICT_AFTER_MS\n        private const val IDLE_EVICT_MIN = 10_000\n        private const val IDLE_EVICT_MAX = 3_600_000\n        private const val IDLE_EVICT_STEP = 10_000\n",
    "",
    "ui idle constants",
)
save(p, t)

p = "src/main/resources/messages/MyBundle.properties"
t = load(p)
for old, label in [
    ("settings.markflow.section.general=General\n", "bundle general"),
    ("settings.markflow.section.advanced=Advanced\n", "bundle advanced"),
    ("settings.markflow.previewOnlyByDefault=Preview only by default (Mermaid/LaTeX)\n", "bundle preview"),
    ("settings.markflow.diagram.securityLevel=Diagram security level\n", "bundle security"),
    ("settings.markflow.idleBrowserEvictAfterMs=Idle browser eviction delay (ms)\n", "bundle idle"),
    ("settings.markflow.idleBrowserEvictAfterMs.tooltip=How long an unused pooled browser stays warm before MarkFlow disposes it.\n", "bundle idle tooltip"),
]:
    t = one(t, old, "", label)
save(p, t)

# Renderer payload: legacy keys are ignored, Mermaid security is always strict.
for p in ["webview/src/app/types.ts", "webview/src/vite-env.d.ts"]:
    t = load(p)
    t = one(t, '    diagramSecurityLevel?: "STRICT" | "LOOSE";\n    previewOnlyByDefault?: boolean;\n', "", f"{p} legacy keys")
    save(p, t)

p = "webview/src/app/runtime-settings.ts"
t = load(p)
t = one(t, '    diagramSecurityLevel: "STRICT",\n    previewOnlyByDefault: true,\n', "", "runtime defaults legacy keys")
t = one(
    t,
    "export const resolveRuntimeSettings = (raw: MarkFlowRuntimeSettings | undefined): Required<MarkFlowRuntimeSettings> => {\n    const merged: Required<MarkFlowRuntimeSettings> = {...DEFAULT_RUNTIME_SETTINGS, ...(raw ?? {})};\n    return {",
    "export const resolveRuntimeSettings = (raw: MarkFlowRuntimeSettings | undefined): Required<MarkFlowRuntimeSettings> => {\n    const current: MarkFlowRuntimeSettings & {diagramSecurityLevel?: unknown; previewOnlyByDefault?: unknown} = {...(raw ?? {})};\n    delete current.diagramSecurityLevel;\n    delete current.previewOnlyByDefault;\n    const merged: Required<MarkFlowRuntimeSettings> = {...DEFAULT_RUNTIME_SETTINGS, ...current};\n    return {",
    "runtime legacy strip",
)
t = one(
    t,
    'export const resolveDiagramSecurityLevel = (settings: Required<MarkFlowRuntimeSettings>): "strict" | "loose" =>\n    settings.diagramSecurityLevel === "LOOSE" ? "loose" : "strict";\n\n',
    "",
    "runtime security resolver",
)
t = one(t, "        securityLevel: resolveDiagramSecurityLevel(settings),", '        securityLevel: "strict" as const,', "runtime strict policy")
save(p, t)

p = "webview/tests/runtime-settings.test.mjs"
t = load(p)
t = one(
    t,
    '    assert.equal(resolved.diagramSecurityLevel, "STRICT");\n    assert.equal(resolved.themeSource, "LIGHT");',
    '    assert.equal("diagramSecurityLevel" in resolved, false);\n    assert.equal("previewOnlyByDefault" in resolved, false);\n    assert.equal(resolved.themeSource, "LIGHT");',
    "runtime default assertion",
)
anchor = '''test("renderer defaults are fail-closed and deterministic", () => {\n    const resolved = settings.resolveRuntimeSettings(undefined);\n    assert.equal("diagramSecurityLevel" in resolved, false);\n    assert.equal("previewOnlyByDefault" in resolved, false);\n    assert.equal(resolved.themeSource, "LIGHT");\n    assert.equal(resolved.mermaidZoomPercent, 100);\n    const config = settings.createMermaidPreviewConfig(resolved);\n    assert.equal(config.securityLevel, "strict");\n    assert.equal(config.theme, "default");\n    assert.equal(config.htmlLabels, false);\n    assert.equal(config.flowchart.htmlLabels, false);\n});\n'''
addition = '''\ntest("legacy preview and Mermaid security keys are ignored and cannot weaken renderer policy", () => {\n    const resolved = settings.resolveRuntimeSettings({\n        themeSource: "LIGHT",\n        diagramSecurityLevel: "LOOSE",\n        previewOnlyByDefault: false\n    });\n    assert.equal("diagramSecurityLevel" in resolved, false);\n    assert.equal("previewOnlyByDefault" in resolved, false);\n    assert.equal(settings.createMermaidPreviewConfig(resolved).securityLevel, "strict");\n});\n'''
if t.count(anchor) != 1:
    raise SystemExit("runtime test insertion anchor mismatch")
t = t.replace(anchor, anchor + addition, 1)
save(p, t)

# Host migration regression: old XML keys deserialize inertly and stop serializing.
p = "src/test/kotlin/com/algorist/markflow/settings/MarkFlowSettingsTest.kt"
t = load(p)
t = one(t, "import com.intellij.testFramework.fixtures.BasePlatformTestCase\n", "import com.intellij.testFramework.fixtures.BasePlatformTestCase\nimport com.intellij.util.xmlb.XmlSerializer\nimport org.jdom.Element\n", "settings test imports")
t = one(
    t,
    '''            mermaidErrorDisplay = "GARBAGE",\n            katexDisplayDensity = "TOO_DENSE",\n            diagramSecurityLevel = "CLASSIFIED"\n''',
    '''            mermaidErrorDisplay = "GARBAGE",\n            katexDisplayDensity = "TOO_DENSE"\n''',
    "settings invalid enum input",
)
t = one(t, '        assertEquals("COMFORTABLE", state.katexDisplayDensity)\n        assertEquals("STRICT", state.diagramSecurityLevel)\n', '        assertEquals("COMFORTABLE", state.katexDisplayDensity)\n', "settings security assertion")
idle_test = '''\n    fun testNormalizeCoercesIdleEvictRange() {\n        val service = newService()\n        val below = MarkFlowSettingsState(idleEvictAfterMs = 1)\n        service.normalizeState(below)\n        assertEquals(10_000, below.idleEvictAfterMs)\n\n        val above = MarkFlowSettingsState(idleEvictAfterMs = 100_000_000)\n        service.normalizeState(above)\n        assertEquals(3_600_000, above.idleEvictAfterMs)\n    }\n'''
t = one(t, idle_test, "\n", "settings idle test")
anchor = "    fun testTypographyPreservedWhenApplyingAnotherSetting() {\n"
migration = '''    fun testLegacyBrowserSettingsAreIgnoredAndNoLongerSerialized() {\n        val legacy = Element("state").apply {\n            addContent(Element("option").setAttribute("name", "themeSource").setAttribute("value", "DARK"))\n            addContent(Element("option").setAttribute("name", "diagramSecurityLevel").setAttribute("value", "LOOSE"))\n            addContent(Element("option").setAttribute("name", "previewOnlyByDefault").setAttribute("value", "false"))\n            addContent(Element("option").setAttribute("name", "idleEvictAfterMs").setAttribute("value", "10000"))\n        }\n        val restored = XmlSerializer.deserialize(legacy, MarkFlowSettingsState::class.java)\n        assertEquals("DARK", restored.themeSource)\n\n        val serialized = XmlSerializer.serialize(restored)\n        val names = serialized.getChildren("option").mapNotNull { it.getAttributeValue("name") }.toSet()\n        assertFalse(names.contains("diagramSecurityLevel"))\n        assertFalse(names.contains("previewOnlyByDefault"))\n        assertFalse(names.contains("idleEvictAfterMs"))\n    }\n\n'''
if t.count(anchor) != 1:
    raise SystemExit("settings migration test anchor mismatch")
t = t.replace(anchor, migration + anchor, 1)
save(p, t)

p = "src/test/kotlin/com/algorist/markflow/editor/native/NativeDerivedPresentationControllerTest.kt"
t = load(p)
t = one(t, '        diagramSecurityLevel = "STRICT",\n        previewOnlyByDefault = true,\n', "", "native derived test legacy args")
save(p, t)

p = "src/main/kotlin/com/algorist/markflow/renderer/jcef/DerivedRendererProbe.kt"
t = load(p)
t = one(t, '"diagramSecurityLevel":"STRICT",', "", "renderer probe legacy key")
save(p, t)

# Rename the generic invalidation API around its actual native/presentation responsibility.
old = Path("src/main/kotlin/com/algorist/markflow/settings/MarkFlowRuntimeSettingsSink.kt")
new = Path("src/main/kotlin/com/algorist/markflow/settings/MarkFlowPresentationSettingsSink.kt")
if not old.is_file() or new.exists():
    raise SystemExit("settings sink rename precondition failed")
t = old.read_text(encoding="utf-8")
t = t.replace("MarkFlowRuntimeSettingsSink", "MarkFlowPresentationSettingsSink")
t = t.replace("MarkFlowRuntimeSettingsNotifier", "MarkFlowPresentationSettingsNotifier")
t = one(t, "    fun runtimeSettingsChanged(forceReload: Boolean)\n", "    fun presentationSettingsChanged()\n", "sink callback")
t = one(t, '            ExtensionPointName.create("com.algorist.markflow.runtimeSettingsSink")', '            ExtensionPointName.create("com.algorist.markflow.presentationSettingsSink")', "sink EP id")
t = one(t, "    fun notifyChanged(forceReload: Boolean = false) {", "    fun notifyChanged() {", "notifier signature")
t = one(t, "            runCatching { sink.runtimeSettingsChanged(forceReload) }", "            runCatching { sink.presentationSettingsChanged() }", "notifier call")
t = one(t, '                    log.warn("MARKFLOW_SETTINGS optional renderer sink failed", failure)', '                    log.warn("MARKFLOW_SETTINGS presentation sink failed", failure)', "notifier log")
new.write_text(t, encoding="utf-8")
old.unlink()

# Update source/tests/descriptor references. Historical absent-class string remains unchanged.
for p in [
    "src/main/kotlin/com/algorist/markflow/settings/MarkFlowSettingsService.kt",
    "src/main/kotlin/com/algorist/markflow/settings/MarkFlowIdeThemeService.kt",
    "src/main/kotlin/com/algorist/markflow/editor/native/NativeMarkFlowProductionLifecycle.kt",
    "src/main/kotlin/com/algorist/markflow/editor/native/NoJcefNativeEditingProbe.kt",
    "src/test/kotlin/com/algorist/markflow/editor/native/NativeProductionCutoverTest.kt",
    "src/main/resources/META-INF/plugin.xml",
]:
    t = load(p)
    t = t.replace("MarkFlowRuntimeSettingsNotifier", "MarkFlowPresentationSettingsNotifier")
    t = t.replace("MarkFlowRuntimeSettingsSink", "MarkFlowPresentationSettingsSink")
    t = t.replace("NativeMarkFlowRuntimeSettingsSink", "NativeMarkFlowPresentationSettingsSink")
    t = t.replace("notifyChanged(forceReload = false)", "notifyChanged()")
    t = t.replace("runtimeSettingsChanged(forceReload: Boolean)", "presentationSettingsChanged()")
    t = t.replace("runtimeSettingsSink", "presentationSettingsSink")
    save(p, t)

# The no-JCEF negative assertion must continue to name the deleted browser class exactly.
p = "src/main/kotlin/com/algorist/markflow/editor/native/NoJcefNativeEditingProbe.kt"
t = load(p)
if '"com.algorist.markflow.browser.MarkFlowBrowserRuntimeSettingsSink"' not in t:
    raise SystemExit("no-JCEF historical browser sink assertion was lost")
save(p, t)

# Fail closed on production/source residue. Tests may mention legacy keys only to prove migration.
for root, needles in {
    "src/main/kotlin": ["idleEvictAfterMs", "previewOnlyByDefault", "DiagramSecurityLevel"],
    "webview/src": ["previewOnlyByDefault", "diagramSecurityLevel", '"LOOSE"'],
    "src/main/resources/messages/MyBundle.properties": ["idleBrowserEvictAfterMs", "previewOnlyByDefault", "diagram.securityLevel"],
}.items():
    path = Path(root)
    files = [path] if path.is_file() else [candidate for candidate in path.rglob("*") if candidate.is_file()]
    corpus = "\n".join(candidate.read_text(encoding="utf-8", errors="ignore") for candidate in files)
    for needle in needles:
        if needle in corpus:
            raise SystemExit(f"forbidden target residue under {root}: {needle}")

current = "\n".join(
    candidate.read_text(encoding="utf-8", errors="ignore")
    for root in [Path("src/main/kotlin"), Path("src/main/resources/META-INF")]
    for candidate in root.rglob("*") if candidate.is_file()
)
for needle in ["MarkFlowRuntimeSettingsSink", "MarkFlowRuntimeSettingsNotifier", "NativeMarkFlowRuntimeSettingsSink", "runtimeSettingsSink"]:
    if needle in current:
        raise SystemExit(f"old settings invalidation name remains: {needle}")

print("#155 source/settings convergence normalized")
