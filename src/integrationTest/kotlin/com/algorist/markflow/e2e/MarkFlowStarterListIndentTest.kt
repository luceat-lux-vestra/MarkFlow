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

class MarkFlowStarterListIndentTest {
    @Test
    fun indentsAndOutdentsThroughNativeMarkdownShortcutsWithUndoRedoAndPersistence() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-driver-list-indent-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver list-indent fixture project"
        }
        val fixturePath = projectPath.resolve("LIST-INDENT.md")
        val expectedSource = Files.readString(fixturePath)
        val childMarkerStart = expectedSource.indexOf("- move-me")
        check(childMarkerStart >= 0 && expectedSource.lastIndexOf("- move-me") == childMarkerStart) {
            "deterministic fixture must contain exactly one move-me list item"
        }
        val childTextOffset = expectedSource.indexOf("move-me", childMarkerStart) + 2
        val expectedIndentedSource = expectedSource.replaceRange(childMarkerStart, childMarkerStart, "  ")

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
                testName = "markflow-starter-driver-list-indent",
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
                val editor = markFlow.openMarkdown("LIST-INDENT.md")
                check(markFlow.source(editor) == expectedSource) {
                    "opened native editor Document differs from deterministic list-indent source"
                }
                check(!markFlow.isDirty(editor)) {
                    "opening list-indent fixture must not dirty the authoritative Document"
                }

                markFlow.assertProductionProjectionAttached(editor)
                check(markFlow.isNativeProjectionPlanReady(editor)) {
                    "native list-indent projection plan is not READY"
                }

                // Deterministic setup only: place the caret in the source item so the native list
                // projection reveals exact Markdown before the user-level Tab action is emitted.
                markFlow.resetToSingleCaret(editor, childTextOffset)
                waitFor(
                    message = "flat list source is active before native indent shortcut",
                    timeout = 10.seconds,
                    getter = { markFlow.listMaxDepth(editor) to markFlow.listOwnedInlays(editor) },
                    checker = { (depth, inlays) -> depth == 0 && inlays == 0 },
                )

                val stampBeforeIndent = markFlow.modificationStamp(editor)
                markFlow.indentMarkdownListItem(editor)
                waitFor(
                    message = "native Markdown Tab path indents exactly one list level",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) to markFlow.listMaxDepth(editor) },
                    checker = { (source, depth) -> source == expectedIndentedSource && depth == 1 },
                )
                check(markFlow.isDirty(editor)) {
                    "native list indent must dirty the authoritative Document"
                }
                check(markFlow.modificationStamp(editor) != stampBeforeIndent) {
                    "native list indent did not advance the Document modification stamp"
                }
                check(
                    markFlow.source(editor).removeRange(childMarkerStart, childMarkerStart + 2) == expectedSource
                ) {
                    "native list indent changed source outside the two-space child indentation"
                }

                // Prove the platform list action's command/Undo contract before any
                // presentation-only caret transition. This keeps source mutation semantics
                // independent from the later inactive-rich-presentation assertion.
                markFlow.undo(editor)
                waitFor(
                    message = "Undo restores the exact flat list and hierarchy",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) to markFlow.listMaxDepth(editor) },
                    checker = { (source, depth) -> source == expectedSource && depth == 0 },
                )
                markFlow.redo(editor)
                waitFor(
                    message = "Redo restores the exact nested list and hierarchy",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) to markFlow.listMaxDepth(editor) },
                    checker = { (source, depth) -> source == expectedIndentedSource && depth == 1 },
                )

                markFlow.clickText(editor, "Tail anchor")
                waitFor(
                    message = "indented source automatically refreshes to nested native list presentation",
                    timeout = 10.seconds,
                    getter = {
                        Triple(
                            markFlow.listMaxDepth(editor),
                            markFlow.listOwnedInlays(editor),
                            markFlow.listFullyConcealed(editor),
                        )
                    },
                    checker = { (depth, inlays, concealed) -> depth == 1 && inlays == 1 && concealed == 1 },
                )

                val indentedChildTextOffset = markFlow.source(editor).indexOf("move-me", childMarkerStart) + 2
                markFlow.resetToSingleCaret(editor, indentedChildTextOffset)
                waitFor(
                    message = "nested list reveals exact source before native outdent shortcut",
                    timeout = 10.seconds,
                    getter = { markFlow.listOwnedInlays(editor) },
                    checker = { inlays -> inlays == 0 },
                )

                markFlow.unindentMarkdownListItem(editor)
                waitFor(
                    message = "native Markdown Shift-Tab path outdents exactly one list level",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) to markFlow.listMaxDepth(editor) },
                    checker = { (source, depth) -> source == expectedSource && depth == 0 },
                )

                markFlow.undo(editor)
                waitFor(
                    message = "Undo restores the exact nested source after outdent",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) to markFlow.listMaxDepth(editor) },
                    checker = { (source, depth) -> source == expectedIndentedSource && depth == 1 },
                )
                markFlow.redo(editor)
                waitFor(
                    message = "Redo restores the exact flat source after outdent",
                    timeout = 10.seconds,
                    getter = { markFlow.source(editor) to markFlow.listMaxDepth(editor) },
                    checker = { (source, depth) -> source == expectedSource && depth == 0 },
                )

                markFlow.save(editor)
                check(!markFlow.isDirty(editor)) {
                    "saving the outdented list must clear platform dirty tracking"
                }
                check(
                    Files.readAllBytes(fixturePath).contentEquals(expectedSource.toByteArray(StandardCharsets.UTF_8))
                ) {
                    "list indent/outdent save did not persist exact final Markdown bytes"
                }

                markFlow.close(editor)
                val reopened = markFlow.openMarkdown("LIST-INDENT.md")
                check(markFlow.source(reopened) == expectedSource) {
                    "reopened list-indent source differs from exact persisted Markdown"
                }
                check(!markFlow.isDirty(reopened)) {
                    "reopened list-indent document must be clean"
                }
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}
