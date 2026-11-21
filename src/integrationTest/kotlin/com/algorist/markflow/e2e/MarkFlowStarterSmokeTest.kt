package com.algorist.markflow.e2e

import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MarkFlowStarterSmokeTest {
    @Test
    fun launchesProductionNativeEditorRevealsFormatsPastesAndPersistsUserEditAcrossReopen() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-driver-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver fixture project"
        }
        val fixturePath = projectPath.resolve("README.md")
        val expectedSource = Files.readString(fixturePath)
        val revealSource = "*emphasis*"
        val revealStart = expectedSource.indexOf(revealSource)
        check(revealStart >= 0) { "deterministic reveal fixture is missing" }
        val revealEnd = revealStart + revealSource.length
        val openingDelimiterEnd = revealStart + 1
        val strikeSource = "~~obsolete~~"
        val strikeStart = expectedSource.indexOf(strikeSource)
        check(strikeStart >= 0) { "deterministic strikethrough fixture is missing" }
        val strikeEnd = strikeStart + strikeSource.length
        val strikeOpeningDelimiterEnd = strikeStart + 2
        val formattingTarget = "formatme"
        val formattingStart = expectedSource.indexOf(formattingTarget)
        check(formattingStart >= 0) { "deterministic formatting fixture is missing" }
        val formattingEnd = formattingStart + formattingTarget.length
        val expectedBoldSources = listOf("**", "__").map { marker ->
            expectedSource.replaceRange(
                formattingStart,
                formattingEnd,
                marker + formattingTarget + marker,
            )
        }

        val pasteMarkdown = "**e2e-markdown-paste**"
        val pastePlain = "e2e-plain-paste"
        val outsidePasteTarget = "outside-paste"
        val outsidePasteStart = expectedSource.indexOf(outsidePasteTarget)
        check(outsidePasteStart >= 0) { "deterministic outside-code paste fixture is missing" }
        val expectedOutsidePasteSource = expectedSource.replaceRange(
            outsidePasteStart,
            outsidePasteStart + outsidePasteTarget.length,
            pasteMarkdown,
        )
        val codePasteTarget = "inside-code-paste"
        val codePasteStart = expectedSource.indexOf(codePasteTarget)
        check(codePasteStart >= 0) { "deterministic code paste fixture is missing" }
        val expectedCodePasteSource = expectedSource.replaceRange(
            codePasteStart,
            codePasteStart + codePasteTarget.length,
            pastePlain,
        )

        val multicaretMarkdown = "**multicaret-markdown**"
        val multicaretPlain = "multicaret-plain"
        val multicaretTargetA = "multicaret-a"
        val multicaretTargetB = "multicaret-b"
        val multicaretStart = expectedSource.indexOf(multicaretTargetA)
        check(multicaretStart >= 0 && expectedSource.indexOf(multicaretTargetB) >= 0) {
            "deterministic multicaret paste fixture is missing"
        }
        val expectedMulticaretPasteSource = expectedSource
            .replace(multicaretTargetA, multicaretMarkdown + multicaretTargetA)
            .replace(multicaretTargetB, multicaretMarkdown + multicaretTargetB)

        val columnMarkdown = "# column-one\n# column-two"
        val columnPlain = "column-plain"
        val columnTargetA = "column-a"
        val columnTargetB = "column-b"
        val columnStart = expectedSource.indexOf(columnTargetA)
        check(columnStart >= 0 && expectedSource.indexOf(columnTargetB) >= 0) {
            "deterministic column paste fixture is missing"
        }
        val expectedColumnPasteSource = expectedSource
            .replace(columnTargetA, "# column-one" + columnTargetA)
            .replace(columnTargetB, "# column-two" + columnTargetB)

        val appendedText = "Persistence marker saved and reopened"
        val expectedPersistedSource = expectedSource + appendedText
        val platformVersion = System.getProperty("markflow.test.platformVersion")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("markflow.test.platformVersion must be supplied by the Gradle integrationTest task")
        // Starter resolves stable release versions and EAP build numbers through different metadata fields.
        val targetTestCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectPath)).let { testCase ->
            if (isStarterEapBuildNumber(platformVersion)) {
                testCase.useEAP(platformVersion)
            } else {
                testCase.useRelease(platformVersion)
            }
        }

        try {
            Starter.newContext(
                testName = "markflow-starter-driver-persistence",
                testCase = targetTestCase,
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", false)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val markFlow = MarkFlowIdeDriver(this)
                val editor = markFlow.openMarkdown("README.md")
                check(markFlow.source(editor) == expectedSource) {
                    "opened native editor Document differs from deterministic fixture source"
                }
                check(!markFlow.isDirty(editor)) {
                    "opening the fixture must not dirty the authoritative Document"
                }

                val revealSourceBefore = markFlow.source(editor)
                val revealStampBefore = markFlow.modificationStamp(editor)
                markFlow.assertProductionProjectionAttached(editor)
                try {
                    check(markFlow.isNativeProjectionAttached(editor)) {
                        "production native MarkFlow projection is not attached"
                    }
                    check(markFlow.isNativeProjectionPlanReady(editor)) {
                        "native projection E2E plan is not READY"
                    }
                    check(markFlow.hasProjection(editor, "EMPHASIS", revealStart, revealEnd)) {
                        "attached MarkFlow plan does not contain the deterministic emphasis projection"
                    }
                    check(markFlow.hasProjection(editor, "STRIKETHROUGH", strikeStart, strikeEnd)) {
                        "attached MarkFlow plan does not contain the deterministic strikethrough projection"
                    }
                    waitFor(
                        message = "inactive strikethrough opening delimiter is concealed",
                        timeout = 10.seconds,
                        getter = { markFlow.isFoldCollapsed(editor, strikeStart, strikeOpeningDelimiterEnd) },
                        checker = { collapsed -> collapsed },
                    )
                    markFlow.clickText(editor, "obsolete")
                    waitFor(
                        message = "mouse caret entering strikethrough reveals exact opening delimiter",
                        timeout = 10.seconds,
                        getter = { markFlow.isFoldCollapsed(editor, strikeStart, strikeOpeningDelimiterEnd) },
                        checker = { collapsed -> !collapsed },
                    )
                    check(markFlow.source(editor) == revealSourceBefore) {
                        "strikethrough reveal changed authoritative Markdown source"
                    }
                    check(markFlow.modificationStamp(editor) == revealStampBefore) {
                        "strikethrough reveal changed the authoritative Document modification stamp"
                    }
                    check(!markFlow.isDirty(editor)) {
                        "strikethrough reveal dirtied the authoritative Document"
                    }
                    markFlow.clickText(editor, "deterministic")
                    waitFor(
                        message = "moving caret away restores inactive strikethrough presentation",
                        timeout = 10.seconds,
                        getter = { markFlow.isFoldCollapsed(editor, strikeStart, strikeOpeningDelimiterEnd) },
                        checker = { collapsed -> collapsed },
                    )

                    waitFor(
                        message = "inactive emphasis opening delimiter is concealed",
                        timeout = 10.seconds,
                        getter = { markFlow.isFoldCollapsed(editor, revealStart, openingDelimiterEnd) },
                        checker = { collapsed -> collapsed },
                    )

                    markFlow.clickText(editor, "emphasis")
                    waitFor(
                        message = "mouse caret entering emphasis reveals exact opening delimiter",
                        timeout = 10.seconds,
                        getter = { markFlow.isFoldCollapsed(editor, revealStart, openingDelimiterEnd) },
                        checker = { collapsed -> !collapsed },
                    )
                    check(markFlow.source(editor) == revealSourceBefore) {
                        "caret-driven reveal changed authoritative Markdown source"
                    }
                    check(markFlow.modificationStamp(editor) == revealStampBefore) {
                        "caret-driven reveal changed the authoritative Document modification stamp"
                    }
                    check(!markFlow.isDirty(editor)) {
                        "caret-driven reveal dirtied the authoritative Document"
                    }

                    markFlow.clickText(editor, "deterministic")
                    waitFor(
                        message = "moving caret away restores inactive emphasis presentation",
                        timeout = 10.seconds,
                        getter = { markFlow.isFoldCollapsed(editor, revealStart, openingDelimiterEnd) },
                        checker = { collapsed -> collapsed },
                    )
                    check(markFlow.source(editor) == revealSourceBefore)
                    check(markFlow.modificationStamp(editor) == revealStampBefore)
                    check(!markFlow.isDirty(editor))
                } finally {
                    markFlow.assertProductionProjectionStillAttached(editor)
                }

                markFlow.selectRangeWithKeyboard(editor, formattingStart, formattingTarget.length)
                markFlow.invokeMarkdownBold(editor)
                waitFor(
                    message = "bundled Markdown bold action wraps only the keyboard-selected source range",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source in expectedBoldSources },
                )
                val boldSource = markFlow.source(editor)
                check(markFlow.isDirty(editor)) {
                    "source-local Markdown action must dirty the authoritative Document"
                }

                markFlow.undo(editor)
                waitFor(
                    message = "Undo restores the exact pre-format Markdown source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source == expectedSource },
                )

                markFlow.redo(editor)
                waitFor(
                    message = "Redo restores the exact source-local bold edit",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source == boldSource },
                )
                check(markFlow.isDirty(editor)) {
                    "Redo of the Markdown action must dirty the authoritative Document"
                }

                markFlow.undo(editor)
                waitFor(
                    message = "formatting proof cleanup restores original source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source == expectedSource },
                )
                markFlow.save(editor)
                check(Files.readAllBytes(fixturePath).contentEquals(expectedSource.toByteArray(StandardCharsets.UTF_8))) {
                    "formatting proof cleanup changed deterministic fixture bytes"
                }

                try {
                    markFlow.resetToSingleCaret(editor, outsidePasteStart)
                    markFlow.selectRangeWithKeyboard(editor, outsidePasteStart, outsidePasteTarget.length)
                    markFlow.seedMarkdownClipboard(pasteMarkdown, pastePlain)
                    markFlow.paste(editor)
                    waitFor(
                        message = "user paste prefers text/markdown outside parser-proven code",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedOutsidePasteSource },
                    )
                    check(markFlow.isDirty(editor)) {
                        "outside-code user paste must dirty the authoritative Document"
                    }

                    markFlow.undo(editor)
                    waitFor(
                        message = "Undo restores exact source after outside-code Markdown paste",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )
                    markFlow.redo(editor)
                    waitFor(
                        message = "Redo restores exact Markdown-preferred paste",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedOutsidePasteSource },
                    )
                    markFlow.undo(editor)
                    waitFor(
                        message = "outside-code paste cleanup restores original source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )

                    markFlow.resetToSingleCaret(editor, codePasteStart)
                    markFlow.selectRangeWithKeyboard(editor, codePasteStart, codePasteTarget.length)
                    markFlow.seedMarkdownClipboard(pasteMarkdown, pastePlain)
                    markFlow.paste(editor)
                    waitFor(
                        message = "user paste preserves platform plain text in parser-proven code",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedCodePasteSource },
                    )

                    markFlow.undo(editor)
                    waitFor(
                        message = "Undo restores exact source after code-context paste",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )
                    markFlow.redo(editor)
                    waitFor(
                        message = "Redo restores exact code-context plain paste",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedCodePasteSource },
                    )
                    markFlow.undo(editor)
                    waitFor(
                        message = "code-context paste cleanup restores original source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )

                    markFlow.resetToSingleCaret(editor, multicaretStart)
                    markFlow.cloneCaretBelow(editor)
                    check(markFlow.caretCount(editor) == 2) {
                        "representative multicaret paste setup did not create two user carets"
                    }
                    markFlow.seedMarkdownClipboard(
                        multicaretMarkdown,
                        multicaretPlain,
                        singleSourceCaret = true,
                    )
                    markFlow.paste(editor)
                    waitFor(
                        message = "multicaret user paste preserves single-source Markdown preference and platform duplication",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedMulticaretPasteSource },
                    )
                    check(markFlow.caretCount(editor) == 2) {
                        "multicaret user paste changed destination caret cardinality"
                    }

                    markFlow.undo(editor)
                    waitFor(
                        message = "Undo restores exact source after multicaret paste",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )
                    markFlow.redo(editor)
                    waitFor(
                        message = "Redo restores exact multicaret Markdown paste",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedMulticaretPasteSource },
                    )
                    markFlow.undo(editor)
                    waitFor(
                        message = "multicaret paste cleanup restores original source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )
                    markFlow.removeSecondaryCarets(editor)

                    markFlow.resetToSingleCaret(editor, columnStart)
                    markFlow.setColumnMode(editor, true)
                    markFlow.seedMarkdownClipboard(columnMarkdown, columnPlain)
                    markFlow.paste(editor)
                    waitFor(
                        message = "column-mode user paste distributes Markdown through the platform column path",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedColumnPasteSource },
                    )
                    check(markFlow.caretCount(editor) == 2) {
                        "column-mode paste did not create the expected two platform carets"
                    }

                    markFlow.undo(editor)
                    waitFor(
                        message = "Undo restores exact source after column-mode paste",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )
                    markFlow.redo(editor)
                    waitFor(
                        message = "Redo restores exact column-mode Markdown paste",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedColumnPasteSource },
                    )
                    markFlow.undo(editor)
                    waitFor(
                        message = "column-mode paste cleanup restores original source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )
                } finally {
                    markFlow.setColumnMode(editor, false)
                    markFlow.removeSecondaryCarets(editor)
                    markFlow.clearClipboard()
                }

                check(markFlow.source(editor) == expectedSource) {
                    "Markdown-aware paste proof did not restore the exact original source"
                }
                check(Files.readAllBytes(fixturePath).contentEquals(expectedSource.toByteArray(StandardCharsets.UTF_8))) {
                    "unsaved Markdown-aware paste proof changed fixture bytes"
                }
                markFlow.save(editor)
                check(!markFlow.isDirty(editor)) {
                    "explicit SaveAll after paste proof must clear platform dirty tracking"
                }
                check(Files.readAllBytes(fixturePath).contentEquals(expectedSource.toByteArray(StandardCharsets.UTF_8))) {
                    "Markdown-aware paste cleanup changed deterministic fixture bytes"
                }

                markFlow.appendAtEnd(editor, appendedText)
                check(markFlow.source(editor) == expectedPersistedSource) {
                    "real editor input did not update the authoritative Document exactly"
                }
                check(markFlow.isDirty(editor)) {
                    "real editor input must dirty the authoritative Document before save"
                }

                markFlow.save(editor)
                waitFor(
                    message = "saved Markdown bytes reach the fixture path",
                    timeout = 10.seconds,
                    getter = { Files.readAllBytes(fixturePath) },
                    checker = { bytes -> bytes.contentEquals(expectedPersistedSource.toByteArray(StandardCharsets.UTF_8)) },
                )
                markFlow.close(editor)

                val reopenedEditor = markFlow.openMarkdown("README.md")
                check(markFlow.source(reopenedEditor) == expectedPersistedSource) {
                    "reopened native editor Document differs from the exact saved source"
                }
                check(!markFlow.isDirty(reopenedEditor)) {
                    "reopened persisted source must start clean"
                }
                check(Files.readAllBytes(fixturePath).contentEquals(expectedPersistedSource.toByteArray(StandardCharsets.UTF_8))) {
                    "persisted file bytes differ from the authoritative reopened Document"
                }
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun referenceDefinitionLifecycleRevealsEditsUndoesRedoesSavesAndRestoresFoldAfterReopen() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-reference-definition-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver fixture project"
        }
        val fixturePath = projectPath.resolve("REFERENCE_DEFINITION.md")
        val originalSource = Files.readString(fixturePath)
        val definitionLine = """[docs]: https://example.com/original "Original title""""
        val definitionStart = originalSource.indexOf(definitionLine)
        check(definitionStart >= 0) { "deterministic reference-definition fixture is missing" }
        val definitionEnd = definitionStart + definitionLine.length
        val originalPayload = """https://example.com/original "Original title""""
        val payloadStart = originalSource.indexOf(originalPayload, definitionStart)
        check(payloadStart >= definitionStart) { "reference-definition editable payload is missing" }
        val payloadEnd = payloadStart + originalPayload.length
        val replacementPayload = """https://example.com/edited "Edited title""""
        val editedSource = originalSource.replaceRange(payloadStart, payloadEnd, replacementPayload)
        val editedDefinitionLine = """[docs]: https://example.com/edited "Edited title""""
        val editedDefinitionStart = editedSource.indexOf(editedDefinitionLine)
        check(editedDefinitionStart >= 0) { "edited definition range cannot be derived" }
        val editedDefinitionEnd = editedDefinitionStart + editedDefinitionLine.length
        val externalPayload = """https://example.com/external "External title""""
        val externalSource = editedSource.replaceRange(
            payloadStart,
            payloadStart + replacementPayload.length,
            externalPayload,
        )
        val externalDefinitionLine = """[docs]: https://example.com/external "External title""""
        val externalDefinitionStart = externalSource.indexOf(externalDefinitionLine)
        check(externalDefinitionStart >= 0) { "external definition range cannot be derived" }
        val externalDefinitionEnd = externalDefinitionStart + externalDefinitionLine.length
        val originalTailOffset = originalSource.indexOf("Tail anchor.") + 1
        val editedTailOffset = editedSource.indexOf("Tail anchor.") + 1
        val externalTailOffset = externalSource.indexOf("Tail anchor.") + 1
        check(originalTailOffset > 0 && editedTailOffset > 0 && externalTailOffset > 0) {
            "tail anchor fixture is missing"
        }

        val platformVersion = System.getProperty("markflow.test.platformVersion")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("markflow.test.platformVersion must be supplied by the Gradle integrationTest task")
        val targetTestCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectPath)).let { testCase ->
            if (isStarterEapBuildNumber(platformVersion)) {
                testCase.useEAP(platformVersion)
            } else {
                testCase.useRelease(platformVersion)
            }
        }

        try {
            Starter.newContext(
                testName = "markflow-starter-reference-definition-lifecycle",
                testCase = targetTestCase,
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", false)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val markFlow = MarkFlowIdeDriver(this)
                val editor = markFlow.openMarkdown("REFERENCE_DEFINITION.md")

                check(markFlow.source(editor) == originalSource) {
                    "opened reference-definition fixture differs from exact source bytes"
                }
                check(!markFlow.isDirty(editor)) {
                    "opening the reference-definition fixture must not dirty the Document"
                }
                markFlow.assertProductionProjectionAttached(editor)
                check(markFlow.isNativeProjectionPlanReady(editor)) {
                    "reference-definition projection plan is not READY"
                }
                check(
                    markFlow.hasProjection(
                        editor,
                        "REFERENCE_DEFINITION",
                        definitionStart,
                        definitionEnd,
                    )
                ) {
                    "production plan does not contain the exact reference-definition projection"
                }

                markFlow.resetToSingleCaret(editor, originalTailOffset)
                waitFor(
                    message = "inactive reference definition is one collapsed full-range fold",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(editor, definitionStart, definitionEnd) },
                    checker = { collapsed -> collapsed },
                )

                val revealSourceBefore = markFlow.source(editor)
                val revealStampBefore = markFlow.modificationStamp(editor)
                val destinationProbeOffset = payloadStart + "https://".length
                markFlow.resetToSingleCaret(editor, destinationProbeOffset)
                waitFor(
                    message = "caret entering reference definition reveals exact source",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(editor, definitionStart, definitionEnd) },
                    checker = { collapsed -> !collapsed },
                )
                check(markFlow.source(editor) == revealSourceBefore) {
                    "reference-definition reveal changed authoritative source"
                }
                check(markFlow.modificationStamp(editor) == revealStampBefore) {
                    "reference-definition reveal changed Document modification stamp"
                }
                check(!markFlow.isDirty(editor)) {
                    "reference-definition reveal dirtied the Document"
                }

                markFlow.selectRangeWithKeyboard(editor, payloadStart, originalPayload.length)
                markFlow.typeText(editor, replacementPayload)
                waitFor(
                    message = "reference-definition destination/title edit changes only the selected payload",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source == editedSource },
                )
                check(markFlow.isDirty(editor)) {
                    "reference-definition edit must dirty the authoritative Document"
                }
                check(markFlow.modificationStamp(editor) != revealStampBefore) {
                    "reference-definition edit must advance the Document modification stamp"
                }

                markFlow.undo(editor)
                waitFor(
                    message = "Undo restores exact original reference-definition bytes",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source == originalSource },
                )
                markFlow.redo(editor)
                waitFor(
                    message = "Redo restores exact edited reference-definition bytes",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source == editedSource },
                )
                check(markFlow.isDirty(editor)) {
                    "redo of the reference-definition edit must leave the Document dirty"
                }

                markFlow.resetToSingleCaret(editor, editedTailOffset)
                waitFor(
                    message = "edited reference definition projection is rebuilt at its exact range",
                    timeout = 10.seconds,
                    getter = {
                        markFlow.hasProjection(
                            editor,
                            "REFERENCE_DEFINITION",
                            editedDefinitionStart,
                            editedDefinitionEnd,
                        )
                    },
                    checker = { present -> present },
                )
                waitFor(
                    message = "edited inactive reference definition regains one collapsed full-range fold",
                    timeout = 10.seconds,
                    getter = {
                        markFlow.isFoldCollapsed(
                            editor,
                            editedDefinitionStart,
                            editedDefinitionEnd,
                        )
                    },
                    checker = { collapsed -> collapsed },
                )

                markFlow.save(editor)
                waitFor(
                    message = "saved edited reference-definition bytes reach disk",
                    timeout = 10.seconds,
                    getter = { Files.readAllBytes(fixturePath) },
                    checker = { bytes ->
                        bytes.contentEquals(editedSource.toByteArray(StandardCharsets.UTF_8))
                    },
                )
                check(!markFlow.isDirty(editor)) {
                    "saving reference-definition edit must clear platform dirty tracking"
                }
                markFlow.close(editor)

                val reopened = markFlow.openMarkdown("REFERENCE_DEFINITION.md")
                check(markFlow.source(reopened) == editedSource) {
                    "reopened reference-definition Document differs from persisted bytes"
                }
                check(!markFlow.isDirty(reopened)) {
                    "reopened reference-definition Document must start clean"
                }
                markFlow.assertProductionProjectionAttached(reopened)
                check(markFlow.isNativeProjectionPlanReady(reopened)) {
                    "reopened reference-definition projection plan is not READY"
                }
                markFlow.resetToSingleCaret(reopened, editedTailOffset)
                waitFor(
                    message = "reopened edited definition restores exact parser-owned projection",
                    timeout = 10.seconds,
                    getter = {
                        markFlow.hasProjection(
                            reopened,
                            "REFERENCE_DEFINITION",
                            editedDefinitionStart,
                            editedDefinitionEnd,
                        )
                    },
                    checker = { present -> present },
                )
                waitFor(
                    message = "reopened inactive reference definition restores collapsed metadata fold",
                    timeout = 10.seconds,
                    getter = {
                        markFlow.isFoldCollapsed(
                            reopened,
                            editedDefinitionStart,
                            editedDefinitionEnd,
                        )
                    },
                    checker = { collapsed -> collapsed },
                )
                check(
                    Files.readAllBytes(fixturePath)
                        .contentEquals(editedSource.toByteArray(StandardCharsets.UTF_8))
                ) {
                    "reopened fixture bytes differ from the authoritative edited source"
                }


                val themeSourceBefore = markFlow.source(reopened)
                val themeStampBefore = markFlow.modificationStamp(reopened)
                val initialDarkTheme = markFlow.isIdeThemeDark()
                try {
                    markFlow.switchIdeTheme(dark = !initialDarkTheme)
                    waitFor(
                        message = "reference-definition projection remains ready after IDE theme transition",
                        timeout = 20.seconds,
                        getter = { markFlow.isNativeProjectionPlanReady(reopened) },
                        checker = { ready -> ready },
                    )
                    markFlow.resetToSingleCaret(reopened, editedTailOffset)
                    waitFor(
                        message = "reference-definition fold survives opposite IDE theme",
                        timeout = 20.seconds,
                        getter = {
                            markFlow.isFoldCollapsed(
                                reopened,
                                editedDefinitionStart,
                                editedDefinitionEnd,
                            )
                        },
                        checker = { collapsed -> collapsed },
                    )
                    check(markFlow.source(reopened) == themeSourceBefore) {
                        "IDE theme transition changed authoritative reference-definition source"
                    }
                    check(markFlow.modificationStamp(reopened) == themeStampBefore) {
                        "IDE theme transition changed Document modification stamp"
                    }
                    check(!markFlow.isDirty(reopened)) {
                        "IDE theme transition dirtied the reference-definition Document"
                    }
                    markFlow.assertProductionProjectionStillAttached(reopened)

                    markFlow.switchIdeTheme(dark = initialDarkTheme)
                    waitFor(
                        message = "reference-definition fold survives restored IDE theme",
                        timeout = 20.seconds,
                        getter = {
                            markFlow.isFoldCollapsed(
                                reopened,
                                editedDefinitionStart,
                                editedDefinitionEnd,
                            )
                        },
                        checker = { collapsed -> collapsed },
                    )
                    check(markFlow.source(reopened) == themeSourceBefore) {
                        "restoring IDE theme changed authoritative reference-definition source"
                    }
                    check(markFlow.modificationStamp(reopened) == themeStampBefore) {
                        "restoring IDE theme changed Document modification stamp"
                    }
                    check(!markFlow.isDirty(reopened)) {
                        "restoring IDE theme dirtied the reference-definition Document"
                    }
                } finally {
                    if (markFlow.isIdeThemeDark() != initialDarkTheme) {
                        markFlow.switchIdeTheme(dark = initialDarkTheme)
                    }
                }

                val stampBeforeExternalChange = markFlow.modificationStamp(reopened)
                Files.writeString(fixturePath, externalSource, StandardCharsets.UTF_8)
                markFlow.synchronizeExternalChanges(reopened)
                waitFor(
                    message = "platform reloads externally changed reference-definition bytes",
                    timeout = 20.seconds,
                    getter = { markFlow.source(reopened) },
                    checker = { source -> source == externalSource },
                )
                check(markFlow.modificationStamp(reopened) != stampBeforeExternalChange) {
                    "external file reload did not advance the authoritative Document stamp"
                }
                check(!markFlow.isDirty(reopened)) {
                    "clean external file reload must remain platform-clean"
                }
                check(
                    Files.readAllBytes(fixturePath)
                        .contentEquals(externalSource.toByteArray(StandardCharsets.UTF_8))
                ) {
                    "external file bytes differ from the platform-reloaded Document"
                }
                markFlow.assertProductionProjectionStillAttached(reopened)
                waitFor(
                    message = "external reload rebuilds a ready reference-definition plan",
                    timeout = 20.seconds,
                    getter = { markFlow.isNativeProjectionPlanReady(reopened) },
                    checker = { ready -> ready },
                )
                waitFor(
                    message = "external reload rebuilds exact parser-owned reference definition",
                    timeout = 20.seconds,
                    getter = {
                        markFlow.hasProjection(
                            reopened,
                            "REFERENCE_DEFINITION",
                            externalDefinitionStart,
                            externalDefinitionEnd,
                        )
                    },
                    checker = { present -> present },
                )
                markFlow.resetToSingleCaret(reopened, externalTailOffset)
                waitFor(
                    message = "externally reloaded inactive reference definition regains metadata fold",
                    timeout = 20.seconds,
                    getter = {
                        markFlow.isFoldCollapsed(
                            reopened,
                            externalDefinitionStart,
                            externalDefinitionEnd,
                        )
                    },
                    checker = { collapsed -> collapsed },
                )
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun lineAndThematicBreakLifecyclePreservesExactSourceAcrossEditSaveReopenAndTheme() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-line-breaks-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver fixture project"
        }

        val originalSource = listOf(
            "# Line Break Lifecycle",
            "",
            "Heading-adjacent hard break\\",
            "heading continuation.",
            "",
            "- List hard break\\",
            "  list continuation.",
            "",
            "Paragraph hard break with spaces  ",
            "paragraph continuation.",
            "",
            "Soft line starts here",
            "soft continuation.",
            "",
            "---",
            "",
            "***",
            "",
            "___",
            "",
            "Tail anchor.",
            "",
        ).joinToString("\n")
        val fixturePath = projectPath.resolve("LINE_BREAKS.md")
        Files.writeString(fixturePath, originalSource, StandardCharsets.UTF_8)

        val headingHardLine = "Heading-adjacent hard break\\"
        val headingHardStart = originalSource.indexOf(headingHardLine) + headingHardLine.length - 1
        val headingHardEnd = headingHardStart + 1
        val listHardLine = "- List hard break\\"
        val listHardStart = originalSource.indexOf(listHardLine) + listHardLine.length - 1
        val listHardEnd = listHardStart + 1
        val spaceHardLine = "Paragraph hard break with spaces  "
        val spaceHardStart = originalSource.indexOf(spaceHardLine) + spaceHardLine.length - 2
        val spaceHardEnd = spaceHardStart + 2
        val softLine = "Soft line starts here"
        val softLineStart = originalSource.indexOf(softLine)
        val softProbeStart = softLineStart + softLine.length - 1
        val softProbeEnd = softLineStart + softLine.length
        val thematicRanges = listOf("---", "***", "___").map { marker ->
            val start = originalSource.indexOf("\n$marker\n") + 1
            check(start > 0) { "thematic-break fixture is missing $marker" }
            Triple(marker, start, start + marker.length)
        }
        val originalTailOffset = originalSource.indexOf("Tail anchor.") + 1
        check(
            headingHardStart > 0 &&
                listHardStart > 0 &&
                spaceHardStart > 0 &&
                softLineStart > 0 &&
                originalTailOffset > 0
        ) {
            "line-break lifecycle fixture offsets cannot be derived"
        }

        val editedSource = originalSource.replaceRange(headingHardStart, headingHardEnd, "  ")
        val editedHeadingHardEnd = headingHardStart + 2
        val editedTailOffset = editedSource.indexOf("Tail anchor.") + 1

        val platformVersion = System.getProperty("markflow.test.platformVersion")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("markflow.test.platformVersion must be supplied by the Gradle integrationTest task")
        val targetTestCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectPath)).let { testCase ->
            if (isStarterEapBuildNumber(platformVersion)) {
                testCase.useEAP(platformVersion)
            } else {
                testCase.useRelease(platformVersion)
            }
        }

        try {
            Starter.newContext(
                testName = "markflow-starter-line-break-lifecycle",
                testCase = targetTestCase,
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", false)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val markFlow = MarkFlowIdeDriver(this)
                val editor = markFlow.openMarkdown("LINE_BREAKS.md")

                check(markFlow.source(editor) == originalSource) {
                    "opened line-break fixture differs from exact source bytes"
                }
                check(!markFlow.isDirty(editor)) {
                    "opening the line-break fixture must not dirty the Document"
                }
                markFlow.assertProductionProjectionAttached(editor)
                check(markFlow.isNativeProjectionPlanReady(editor)) {
                    "line-break projection plan is not READY"
                }

                listOf(
                    headingHardStart to headingHardEnd,
                    listHardStart to listHardEnd,
                    spaceHardStart to spaceHardEnd,
                ).forEach { (start, end) ->
                    check(markFlow.hasProjection(editor, "HARD_LINE_BREAK", start, end)) {
                        "production plan is missing exact hard-break projection $start..$end"
                    }
                }
                check(!markFlow.hasProjection(editor, "HARD_LINE_BREAK", softProbeStart, softProbeEnd)) {
                    "soft line acquired an unexpected hard-break projection"
                }
                thematicRanges.forEach { (marker, start, end) ->
                    check(markFlow.hasProjection(editor, "THEMATIC_BREAK", start, end)) {
                        "production plan is missing exact thematic-break projection for $marker"
                    }
                }

                markFlow.resetToSingleCaret(editor, originalTailOffset)
                waitFor(
                    message = "inactive backslash hard-break marker is concealed",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(editor, headingHardStart, headingHardEnd) },
                    checker = { collapsed -> collapsed },
                )
                waitFor(
                    message = "inactive two-space hard-break marker is concealed",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(editor, spaceHardStart, spaceHardEnd) },
                    checker = { collapsed -> collapsed },
                )

                val revealSourceBefore = markFlow.source(editor)
                val revealStampBefore = markFlow.modificationStamp(editor)
                markFlow.resetToSingleCaret(editor, headingHardStart)
                waitFor(
                    message = "caret touching hard-break marker reveals exact source",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(editor, headingHardStart, headingHardEnd) },
                    checker = { collapsed -> !collapsed },
                )
                check(markFlow.source(editor) == revealSourceBefore) {
                    "hard-break reveal changed authoritative source"
                }
                check(markFlow.modificationStamp(editor) == revealStampBefore) {
                    "hard-break reveal changed Document modification stamp"
                }
                check(!markFlow.isDirty(editor)) {
                    "hard-break reveal dirtied the Document"
                }

                markFlow.selectRangeWithKeyboard(editor, headingHardStart, 1)
                markFlow.typeText(editor, "  ")
                waitFor(
                    message = "hard-break marker edit changes only the selected lexical marker",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source == editedSource },
                )
                check(markFlow.isDirty(editor)) {
                    "hard-break marker edit must dirty the authoritative Document"
                }
                check(markFlow.modificationStamp(editor) != revealStampBefore) {
                    "hard-break marker edit must advance the Document modification stamp"
                }

                markFlow.undo(editor)
                waitFor(
                    message = "Undo restores exact original backslash hard-break bytes",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source == originalSource },
                )
                markFlow.redo(editor)
                waitFor(
                    message = "Redo restores exact edited two-space hard-break bytes",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { source -> source == editedSource },
                )

                markFlow.resetToSingleCaret(editor, editedTailOffset)
                waitFor(
                    message = "edited hard-break projection is rebuilt at exact two-space marker range",
                    timeout = 10.seconds,
                    getter = {
                        markFlow.hasProjection(
                            editor,
                            "HARD_LINE_BREAK",
                            headingHardStart,
                            editedHeadingHardEnd,
                        )
                    },
                    checker = { present -> present },
                )
                waitFor(
                    message = "edited inactive hard-break marker regains its collapsed fold",
                    timeout = 10.seconds,
                    getter = {
                        markFlow.isFoldCollapsed(
                            editor,
                            headingHardStart,
                            editedHeadingHardEnd,
                        )
                    },
                    checker = { collapsed -> collapsed },
                )

                markFlow.save(editor)
                waitFor(
                    message = "saved edited line-break bytes reach disk",
                    timeout = 10.seconds,
                    getter = { Files.readAllBytes(fixturePath) },
                    checker = { bytes ->
                        bytes.contentEquals(editedSource.toByteArray(StandardCharsets.UTF_8))
                    },
                )
                check(!markFlow.isDirty(editor)) {
                    "saving hard-break edit must clear platform dirty tracking"
                }
                markFlow.close(editor)

                val reopened = markFlow.openMarkdown("LINE_BREAKS.md")
                check(markFlow.source(reopened) == editedSource) {
                    "reopened line-break Document differs from persisted bytes"
                }
                check(!markFlow.isDirty(reopened)) {
                    "reopened line-break Document must start clean"
                }
                markFlow.assertProductionProjectionAttached(reopened)
                check(markFlow.isNativeProjectionPlanReady(reopened)) {
                    "reopened line-break projection plan is not READY"
                }
                markFlow.resetToSingleCaret(reopened, editedTailOffset)
                waitFor(
                    message = "reopened hard-break projection preserves edited lexical marker",
                    timeout = 10.seconds,
                    getter = {
                        markFlow.hasProjection(
                            reopened,
                            "HARD_LINE_BREAK",
                            headingHardStart,
                            editedHeadingHardEnd,
                        )
                    },
                    checker = { present -> present },
                )
                waitFor(
                    message = "reopened inactive hard-break marker restores collapsed presentation",
                    timeout = 10.seconds,
                    getter = {
                        markFlow.isFoldCollapsed(
                            reopened,
                            headingHardStart,
                            editedHeadingHardEnd,
                        )
                    },
                    checker = { collapsed -> collapsed },
                )

                val themeSourceBefore = markFlow.source(reopened)
                val themeStampBefore = markFlow.modificationStamp(reopened)
                val initialDarkTheme = markFlow.isIdeThemeDark()
                try {
                    markFlow.switchIdeTheme(dark = !initialDarkTheme)
                    waitFor(
                        message = "line-break projection remains ready after IDE theme transition",
                        timeout = 20.seconds,
                        getter = { markFlow.isNativeProjectionPlanReady(reopened) },
                        checker = { ready -> ready },
                    )
                    markFlow.resetToSingleCaret(reopened, editedTailOffset)
                    waitFor(
                        message = "hard-break fold survives opposite IDE theme",
                        timeout = 20.seconds,
                        getter = {
                            markFlow.isFoldCollapsed(
                                reopened,
                                headingHardStart,
                                editedHeadingHardEnd,
                            )
                        },
                        checker = { collapsed -> collapsed },
                    )
                    check(markFlow.source(reopened) == themeSourceBefore) {
                        "IDE theme transition changed authoritative line-break source"
                    }
                    check(markFlow.modificationStamp(reopened) == themeStampBefore) {
                        "IDE theme transition changed line-break Document modification stamp"
                    }
                    check(!markFlow.isDirty(reopened)) {
                        "IDE theme transition dirtied the line-break Document"
                    }
                    markFlow.assertProductionProjectionStillAttached(reopened)

                    markFlow.switchIdeTheme(dark = initialDarkTheme)
                    waitFor(
                        message = "hard-break fold survives restored IDE theme",
                        timeout = 20.seconds,
                        getter = {
                            markFlow.isFoldCollapsed(
                                reopened,
                                headingHardStart,
                                editedHeadingHardEnd,
                            )
                        },
                        checker = { collapsed -> collapsed },
                    )
                    check(markFlow.source(reopened) == themeSourceBefore) {
                        "restoring IDE theme changed authoritative line-break source"
                    }
                    check(markFlow.modificationStamp(reopened) == themeStampBefore) {
                        "restoring IDE theme changed line-break Document modification stamp"
                    }
                    check(!markFlow.isDirty(reopened)) {
                        "restoring IDE theme dirtied the line-break Document"
                    }
                } finally {
                    if (markFlow.isIdeThemeDark() != initialDarkTheme) {
                        markFlow.switchIdeTheme(dark = initialDarkTheme)
                    }
                }
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }


}
