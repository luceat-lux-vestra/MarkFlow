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
    fun launchesNativeFallbackRevealsFormatsPastesAndPersistsUserEditAcrossReopen() {
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
                markFlow.attachNativeProjection(editor)
                try {
                    check(markFlow.isNativeProjectionAttached(editor)) {
                        "native projection E2E controller did not attach"
                    }
                    check(markFlow.isNativeProjectionPlanReady(editor)) {
                        "native projection E2E plan is not READY"
                    }
                    check(markFlow.hasProjection(editor, "EMPHASIS", revealStart, revealEnd)) {
                        "attached MarkFlow plan does not contain the deterministic emphasis projection"
                    }
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
                    markFlow.detachNativeProjection(editor)
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
}
