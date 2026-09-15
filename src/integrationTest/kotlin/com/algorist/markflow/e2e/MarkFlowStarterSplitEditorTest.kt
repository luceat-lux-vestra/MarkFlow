package com.algorist.markflow.e2e

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Editor
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
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
import java.util.Properties
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MarkFlowStarterSplitEditorTest {
    @Test
    fun splitEditorsShareOneDocumentButKeepIndependentCaretAndPresentationState() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-driver-split-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver split-editor fixture project"
        }
        val fixturePath = projectPath.resolve("README.md")
        val expectedSource = Files.readString(fixturePath)

        val revealSource = "*emphasis*"
        val revealStart = expectedSource.indexOf(revealSource)
        check(revealStart >= 0 && expectedSource.lastIndexOf(revealSource) == revealStart) {
            "deterministic split-editor reveal fixture must contain exactly one emphasis target"
        }
        val revealEnd = revealStart + revealSource.length
        val openingDelimiterEnd = revealStart + 1

        val editTarget = "split-sync"
        val editStart = expectedSource.indexOf(editTarget)
        check(editStart >= 0 && expectedSource.lastIndexOf(editTarget) == editStart) {
            "deterministic split-editor edit target must be unique"
        }
        val editedText = "split-synced"
        val expectedEditedSource = expectedSource.replaceRange(
            editStart,
            editStart + editTarget.length,
            editedText,
        )

        val platformVersion = Properties().apply {
            Files.newInputStream(Path.of("gradle.properties")).use(::load)
        }.getProperty("platformVersion")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("platformVersion is required in gradle.properties")
        val targetIde = IdeInfo.IdeaUltimate.copy(version = platformVersion)

        try {
            Starter.newContext(
                testName = "markflow-starter-driver-split-editor",
                testCase = TestCase(targetIde, LocalProjectInfo(projectPath)),
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
                val split = SplitEditorDriver(this)
                val original = markFlow.openMarkdown("README.md")
                check(markFlow.source(original) == expectedSource) {
                    "opened split-editor fixture differs from authoritative source"
                }
                check(!markFlow.isDirty(original)) {
                    "opening split-editor fixture must not dirty the authoritative Document"
                }
                check(split.visibleEditorsForFile("README.md").size == 1) {
                    "split-editor acceptance must start with exactly one visible Markdown editor"
                }

                markFlow.clickText(original, "deterministic")
                val sourceBeforeSplit = markFlow.source(original)
                val stampBeforeSplit = markFlow.modificationStamp(original)

                split.splitVertically(original)
                val editors = waitFor(
                    message = "normal Split Vertically action creates two visible native editors for README.md",
                    timeout = 10.seconds,
                    getter = { split.visibleEditorsForFile("README.md") },
                    checker = { visible -> visible.size == 2 },
                )
                val left = editors[0]
                val right = editors[1]

                check(split.sameDocument(left, right)) {
                    "split Markdown editors do not share one authoritative IntelliJ Document"
                }
                check(markFlow.source(left) == expectedSource && markFlow.source(right) == expectedSource) {
                    "split creation changed authoritative Markdown source"
                }
                check(markFlow.modificationStamp(left) == stampBeforeSplit) {
                    "split creation changed authoritative Document modification stamp"
                }
                check(markFlow.modificationStamp(right) == stampBeforeSplit) {
                    "split peer does not observe the authoritative Document modification stamp"
                }
                check(!markFlow.isDirty(left) && !markFlow.isDirty(right)) {
                    "split creation dirtied the authoritative Document"
                }

                // Normalize both independent caret models to the same source location before proving
                // that movement and native projection activity in one split do not leak to the other.
                markFlow.clickText(left, "deterministic")
                val leftCaretOutside = markFlow.primaryCaretOffset(left)
                markFlow.clickText(right, "deterministic")
                check(markFlow.primaryCaretOffset(left) == leftCaretOutside) {
                    "moving the right split caret changed the left split caret"
                }

                var leftAttached = false
                var rightAttached = false
                try {
                    markFlow.attachNativeProjection(left)
                    leftAttached = true
                    markFlow.attachNativeProjection(right)
                    rightAttached = true

                    check(markFlow.isNativeProjectionPlanReady(left) && markFlow.isNativeProjectionPlanReady(right)) {
                        "split-editor projection plan is not READY in both editor surfaces"
                    }
                    check(markFlow.hasProjection(left, "EMPHASIS", revealStart, revealEnd)) {
                        "left split plan does not contain the deterministic emphasis projection"
                    }
                    check(markFlow.hasProjection(right, "EMPHASIS", revealStart, revealEnd)) {
                        "right split plan does not contain the deterministic emphasis projection"
                    }
                    waitFor(
                        message = "both inactive split editors conceal the emphasis opening delimiter",
                        timeout = 10.seconds,
                        getter = {
                            markFlow.isFoldCollapsed(left, revealStart, openingDelimiterEnd) to
                                markFlow.isFoldCollapsed(right, revealStart, openingDelimiterEnd)
                        },
                        checker = { (leftCollapsed, rightCollapsed) -> leftCollapsed && rightCollapsed },
                    )

                    val sourceBeforeReveal = markFlow.source(left)
                    val stampBeforeReveal = markFlow.modificationStamp(left)
                    markFlow.clickText(right, "emphasis")
                    waitFor(
                        message = "right split caret reveals only the right split presentation",
                        timeout = 10.seconds,
                        getter = {
                            markFlow.isFoldCollapsed(left, revealStart, openingDelimiterEnd) to
                                markFlow.isFoldCollapsed(right, revealStart, openingDelimiterEnd)
                        },
                        checker = { (leftCollapsed, rightCollapsed) -> leftCollapsed && !rightCollapsed },
                    )
                    check(markFlow.primaryCaretOffset(left) == leftCaretOutside) {
                        "right split reveal changed the independent left split caret"
                    }
                    val rightRevealOffset = markFlow.primaryCaretOffset(right)
                    check(rightRevealOffset in (revealStart + 1) until (revealEnd - 1)) {
                        "right split reveal caret is not inside parser-proven emphasis content"
                    }
                    check(markFlow.source(left) == sourceBeforeReveal && markFlow.source(right) == sourceBeforeReveal) {
                        "per-editor presentation reveal changed shared authoritative source"
                    }
                    check(markFlow.modificationStamp(left) == stampBeforeReveal) {
                        "per-editor presentation reveal changed shared Document modification stamp"
                    }
                    check(!markFlow.isDirty(left) && !markFlow.isDirty(right)) {
                        "per-editor presentation reveal dirtied the shared Document"
                    }
                } finally {
                    if (rightAttached) markFlow.detachNativeProjection(right)
                    if (leftAttached) markFlow.detachNativeProjection(left)
                }

                check(markFlow.source(left) == sourceBeforeSplit && markFlow.source(right) == sourceBeforeSplit) {
                    "split presentation proof did not preserve exact authoritative source"
                }
                check(markFlow.modificationStamp(left) == stampBeforeSplit) {
                    "split presentation proof changed authoritative Document modification stamp"
                }

                val leftCaretBeforeEdit = markFlow.primaryCaretOffset(left)
                check(leftCaretBeforeEdit < editStart) {
                    "deterministic left caret must precede the split edit target for locality proof"
                }
                markFlow.selectRangeWithKeyboard(right, editStart, editTarget.length)
                markFlow.typeText(right, editedText)
                waitFor(
                    message = "keyboard edit in right split is immediately visible through the shared Document",
                    timeout = 10.seconds,
                    getter = { markFlow.source(left) to markFlow.source(right) },
                    checker = { (leftSource, rightSource) ->
                        leftSource == expectedEditedSource && rightSource == expectedEditedSource
                    },
                )
                check(split.sameDocument(left, right)) {
                    "split editors stopped sharing the authoritative Document after edit"
                }
                check(markFlow.primaryCaretOffset(left) == leftCaretBeforeEdit) {
                    "source-local edit after the left caret changed independent left caret state"
                }
                check(markFlow.isDirty(left) && markFlow.isDirty(right)) {
                    "shared split edit must be reported as one dirty authoritative Document"
                }

                markFlow.undo(right)
                waitFor(
                    message = "Undo from right split restores exact source in both editors",
                    timeout = 10.seconds,
                    getter = { markFlow.source(left) to markFlow.source(right) },
                    checker = { (leftSource, rightSource) ->
                        leftSource == expectedSource && rightSource == expectedSource
                    },
                )
                markFlow.redo(right)
                waitFor(
                    message = "Redo from right split restores exact edited source in both editors",
                    timeout = 10.seconds,
                    getter = { markFlow.source(left) to markFlow.source(right) },
                    checker = { (leftSource, rightSource) ->
                        leftSource == expectedEditedSource && rightSource == expectedEditedSource
                    },
                )
                markFlow.undo(right)
                waitFor(
                    message = "split-editor cleanup Undo restores exact original source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(left) to markFlow.source(right) },
                    checker = { (leftSource, rightSource) ->
                        leftSource == expectedSource && rightSource == expectedSource
                    },
                )

                check(Files.readAllBytes(fixturePath).contentEquals(expectedSource.toByteArray(StandardCharsets.UTF_8))) {
                    "unsaved split-editor proof changed fixture bytes"
                }

                split.unsplit(right)
                val remaining = waitFor(
                    message = "normal Unsplit action restores one visible native editor",
                    timeout = 10.seconds,
                    getter = { split.visibleEditorsForFile("README.md") },
                    checker = { visible -> visible.size == 1 },
                ).single()
                check(markFlow.source(remaining) == expectedSource) {
                    "Unsplit changed authoritative Markdown source"
                }
                markFlow.save(remaining)
                check(!markFlow.isDirty(remaining)) {
                    "SaveAll after split-editor proof must leave the authoritative Document clean"
                }
                check(Files.readAllBytes(fixturePath).contentEquals(expectedSource.toByteArray(StandardCharsets.UTF_8))) {
                    "split-editor cleanup changed deterministic fixture bytes"
                }
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}

private class SplitEditorDriver(private val driver: Driver) {
    fun visibleEditorsForFile(fileName: String): List<JEditorUiComponent> =
        driver.ideFrame()
            .xx("//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java)
            .list()
            .filter { editor ->
                editor.editor.getVirtualFile().getName() == fileName &&
                    editor.isEditable() &&
                    driver.withContext(OnDispatcher.EDT) { editor.component.isShowing() }
            }
            .sortedBy { editor ->
                driver.withContext(OnDispatcher.EDT) { editor.component.getLocationOnScreen().x }
            }

    fun splitVertically(editor: JEditorUiComponent) {
        editor.setFocus()
        driver.invokeAction("SplitVertically", component = editor.component)
    }

    fun unsplit(editor: JEditorUiComponent) {
        editor.setFocus()
        driver.invokeAction("Unsplit", component = editor.component)
    }

    fun sameDocument(left: JEditorUiComponent, right: JEditorUiComponent): Boolean {
        val bridge = driver.utility(SplitProjectionE2EBridgeRemote::class)
        return driver.withContext(OnDispatcher.EDT) {
            bridge.sameDocument(left.editor, right.editor)
        }
    }
}

@Remote(value = "com.algorist.markflow.editor.native.NativeProjectionE2EBridge", plugin = "com.algorist.markflow")
private interface SplitProjectionE2EBridgeRemote {
    fun sameDocument(first: Editor, second: Editor): Boolean
}
