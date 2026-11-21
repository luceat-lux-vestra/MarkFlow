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

class MarkFlowStarterHardLineBreakTest {
    @Test
    fun revealsEditsUndoesRedoesSavesReopensAndSurvivesThemeTransition() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-hard-line-break-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver hard-line-break fixture project"
        }

        val fixturePath = projectPath.resolve("HARD-LINE-BREAK.md")
        val originalSource = Files.readString(fixturePath)
        val backslashLine = "Backslash hard break starts here"
        val twoSpaceLine = "Two-space hard break starts here"
        val softLine = "Soft line break starts here"
        val backslashStart = originalSource.indexOf(backslashLine) + backslashLine.length
        val twoSpaceStart = originalSource.indexOf(twoSpaceLine) + twoSpaceLine.length
        val softBoundary = originalSource.indexOf(softLine) + softLine.length
        val tailOffset = originalSource.indexOf("Tail anchor.") + 2
        check(backslashStart >= backslashLine.length)
        check(twoSpaceStart >= twoSpaceLine.length)
        check(softBoundary >= softLine.length)
        check(tailOffset >= 2)
        check(originalSource.substring(backslashStart, backslashStart + 2) == "\\\n") {
            "backslash hard-break marker fixture is missing"
        }
        check(originalSource.substring(twoSpaceStart, twoSpaceStart + 3) == "  \n") {
            "two-space hard-break marker fixture is missing"
        }
        check(originalSource[softBoundary] == '\n') {
            "soft-break fixture must end directly at a physical newline"
        }

        val editedSource = originalSource.replaceRange(backslashStart, backslashStart + 1, "  ")
        val editedBackslashStart = editedSource.indexOf(backslashLine) + backslashLine.length
        val editedTwoSpaceStart = editedSource.indexOf(twoSpaceLine) + twoSpaceLine.length
        val editedTailOffset = editedSource.indexOf("Tail anchor.") + 2

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
                testName = "markflow-starter-hard-line-break-lifecycle",
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
                val editor = markFlow.openMarkdown("HARD-LINE-BREAK.md")

                check(markFlow.source(editor) == originalSource) {
                    "opened hard-line-break fixture differs from authoritative Markdown source"
                }
                check(!markFlow.isDirty(editor)) {
                    "opening hard-line-break fixture must not dirty the authoritative Document"
                }
                markFlow.assertProductionProjectionAttached(editor)
                check(markFlow.isNativeProjectionPlanReady(editor)) {
                    "hard-line-break native projection plan is not READY"
                }
                check(markFlow.hasProjection(editor, "HARD_LINE_BREAK", backslashStart, backslashStart + 1)) {
                    "backslash hard break is missing its exact marker projection"
                }
                check(markFlow.hasProjection(editor, "HARD_LINE_BREAK", twoSpaceStart, twoSpaceStart + 2)) {
                    "two-space hard break is missing its exact marker projection"
                }
                check(!markFlow.hasProjection(editor, "HARD_LINE_BREAK", softBoundary, softBoundary + 1)) {
                    "soft line break must not acquire a hard-break projection"
                }

                markFlow.resetToSingleCaret(editor, tailOffset)
                waitFor(
                    message = "inactive backslash hard-break marker is concealed",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(editor, backslashStart, backslashStart + 1) },
                    checker = { it },
                )
                waitFor(
                    message = "inactive two-space hard-break marker is concealed",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(editor, twoSpaceStart, twoSpaceStart + 2) },
                    checker = { it },
                )

                val revealSourceBefore = markFlow.source(editor)
                val revealStampBefore = markFlow.modificationStamp(editor)
                markFlow.resetToSingleCaret(editor, backslashStart)
                waitFor(
                    message = "caret entering hard-break marker reveals exact source",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(editor, backslashStart, backslashStart + 1) },
                    checker = { collapsed -> !collapsed },
                )
                check(markFlow.source(editor) == revealSourceBefore) {
                    "caret reveal changed authoritative hard-break source"
                }
                check(markFlow.modificationStamp(editor) == revealStampBefore) {
                    "caret reveal changed hard-break Document modification stamp"
                }
                check(!markFlow.isDirty(editor)) {
                    "caret reveal dirtied the hard-break Document"
                }

                markFlow.selectRangeWithKeyboard(editor, backslashStart, 1)
                markFlow.typeText(editor, "  ")
                waitFor(
                    message = "editing the revealed hard-break marker preserves exact user bytes",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { it == editedSource },
                )
                check(markFlow.isDirty(editor)) {
                    "hard-break marker edit must dirty the authoritative Document"
                }

                markFlow.undo(editor)
                waitFor(
                    message = "Undo restores exact backslash hard-break source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { it == originalSource },
                )
                markFlow.redo(editor)
                waitFor(
                    message = "Redo restores exact two-space replacement source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) },
                    checker = { it == editedSource },
                )

                markFlow.resetToSingleCaret(editor, editedTailOffset)
                waitFor(
                    message = "edited hard-break projection rebuilds at exact two-space range",
                    timeout = 10.seconds,
                    getter = { markFlow.hasProjection(editor, "HARD_LINE_BREAK", editedBackslashStart, editedBackslashStart + 2) },
                    checker = { it },
                )
                check(markFlow.hasProjection(editor, "HARD_LINE_BREAK", editedTwoSpaceStart, editedTwoSpaceStart + 2)) {
                    "untouched two-space hard break lost its exact marker projection"
                }

                markFlow.save(editor)
                check(
                    Files.readAllBytes(fixturePath)
                        .contentEquals(editedSource.toByteArray(StandardCharsets.UTF_8))
                ) {
                    "hard-break save did not persist exact edited Markdown bytes"
                }
                markFlow.close(editor)

                val reopened = markFlow.openMarkdown("HARD-LINE-BREAK.md")
                check(markFlow.source(reopened) == editedSource) {
                    "reopened hard-break Document differs from exact persisted bytes"
                }
                check(!markFlow.isDirty(reopened)) {
                    "reopened hard-break Document must start clean"
                }
                markFlow.assertProductionProjectionAttached(reopened)
                check(markFlow.isNativeProjectionPlanReady(reopened))
                markFlow.resetToSingleCaret(reopened, editedTailOffset)
                waitFor(
                    message = "reopened edited hard-break marker restores inactive concealment",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(reopened, editedBackslashStart, editedBackslashStart + 2) },
                    checker = { it },
                )

                val themeSourceBefore = markFlow.source(reopened)
                val themeStampBefore = markFlow.modificationStamp(reopened)
                val initialDarkTheme = markFlow.isIdeThemeDark()
                try {
                    markFlow.switchIdeTheme(dark = !initialDarkTheme)
                    waitFor(
                        message = "hard-break projection remains ready after IDE theme transition",
                        timeout = 20.seconds,
                        getter = { markFlow.isNativeProjectionPlanReady(reopened) },
                        checker = { it },
                    )
                    waitFor(
                        message = "hard-break marker remains concealed after IDE theme transition",
                        timeout = 20.seconds,
                        getter = { markFlow.isFoldCollapsed(reopened, editedBackslashStart, editedBackslashStart + 2) },
                        checker = { it },
                    )
                    check(markFlow.source(reopened) == themeSourceBefore)
                    check(markFlow.modificationStamp(reopened) == themeStampBefore)
                    check(!markFlow.isDirty(reopened))
                    markFlow.assertProductionProjectionStillAttached(reopened)
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
