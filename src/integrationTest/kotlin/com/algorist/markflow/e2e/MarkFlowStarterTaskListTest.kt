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

class MarkFlowStarterTaskListTest {
    @Test
    fun clicksNativeCheckboxUndoesRedoesSavesAndReopensExactTaskSource() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-driver-task-list-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver task-list fixture project"
        }
        val fixturePath = projectPath.resolve("TASKS.md")
        val expectedSource = Files.readString(fixturePath)
        val taskMarkerStart = expectedSource.indexOf("[ ]")
        check(taskMarkerStart >= 0 && expectedSource.lastIndexOf("[ ]") == taskMarkerStart) {
            "deterministic task-list fixture must contain exactly one unchecked task marker"
        }
        val stateOffset = taskMarkerStart + 1
        val expectedToggledSource = expectedSource.replaceRange(stateOffset, stateOffset + 1, "x")
        val tailOffset = expectedSource.indexOf("Plain tail") + 2
        check(tailOffset >= 2) { "deterministic task-list fixture lost the plain-tail caret anchor" }

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
                testName = "markflow-starter-driver-task-list",
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
                val editor = markFlow.openMarkdown("TASKS.md")

                check(markFlow.source(editor) == expectedSource) {
                    "opened task-list fixture differs from authoritative Markdown source"
                }
                check(!markFlow.isDirty(editor)) {
                    "opening task-list fixture must not dirty the authoritative Document"
                }

                markFlow.resetToSingleCaret(editor, tailOffset)
                waitFor(
                    message = "task-list acceptance caret is outside the inactive task block",
                    timeout = 10.seconds,
                    getter = { markFlow.primaryCaretOffset(editor) },
                    checker = { it == tailOffset },
                )

                markFlow.assertProductionProjectionAttached(editor)
                try {
                    check(markFlow.isNativeProjectionPlanReady(editor)) {
                        "task-list native projection plan is not READY"
                    }
                    waitFor(
                        message = "inactive task list owns native checkbox presentation",
                        timeout = 10.seconds,
                        getter = {
                            TaskPresentation(
                                inlays = markFlow.listOwnedInlays(editor),
                                folds = markFlow.listOwnedFolds(editor),
                                concealed = markFlow.listFullyConcealed(editor),
                                taskRows = markFlow.taskRows(editor),
                                checkedTasks = markFlow.checkedTasks(editor),
                            )
                        },
                        checker = { state ->
                            state.inlays == 1 &&
                                state.folds > 0 &&
                                state.concealed == 1 &&
                                state.taskRows == 2 &&
                                state.checkedTasks == 1
                        },
                    )

                    val sourceBeforeToggle = markFlow.source(editor)
                    val stampBeforeToggle = markFlow.modificationStamp(editor)
                    val togglesBefore = markFlow.taskToggles(editor)
                    markFlow.clickFirstTaskCheckbox(editor)

                    waitFor(
                        message = "real native checkbox click toggles exactly the first task state",
                        timeout = 10.seconds,
                        getter = {
                            TaskToggleState(
                                source = markFlow.source(editor),
                                inlays = markFlow.listOwnedInlays(editor),
                                concealed = markFlow.listFullyConcealed(editor),
                                checkedTasks = markFlow.checkedTasks(editor),
                                toggles = markFlow.taskToggles(editor),
                                caretOffset = markFlow.primaryCaretOffset(editor),
                            )
                        },
                        checker = { state ->
                            state.source == expectedToggledSource &&
                                state.inlays == 1 &&
                                state.concealed == 1 &&
                                state.checkedTasks == 2 &&
                                state.toggles == togglesBefore + 1 &&
                                state.caretOffset == tailOffset
                        },
                    )
                    check(markFlow.isDirty(editor)) {
                        "native task checkbox click did not dirty the authoritative Document"
                    }
                    check(markFlow.modificationStamp(editor) != stampBeforeToggle) {
                        "native task checkbox click did not advance the Document modification stamp"
                    }
                    val changedOffsets = sourceBeforeToggle.indices.filter { index ->
                        sourceBeforeToggle[index] != markFlow.source(editor)[index]
                    }
                    check(changedOffsets == listOf(stateOffset)) {
                        "task checkbox click changed source outside the single state character: $changedOffsets"
                    }

                    markFlow.undo(editor)
                    waitFor(
                        message = "Undo restores exact unchecked task source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) to markFlow.checkedTasks(editor) },
                        checker = { (source, checked) -> source == expectedSource && checked == 1 },
                    )

                    markFlow.redo(editor)
                    waitFor(
                        message = "Redo restores exact checked task source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) to markFlow.checkedTasks(editor) },
                        checker = { (source, checked) -> source == expectedToggledSource && checked == 2 },
                    )

                    markFlow.save(editor)
                    check(!markFlow.isDirty(editor)) {
                        "saving toggled task list must clear platform dirty tracking"
                    }
                    check(
                        Files.readAllBytes(fixturePath)
                            .contentEquals(expectedToggledSource.toByteArray(StandardCharsets.UTF_8))
                    ) {
                        "task-list save did not persist exact toggled Markdown bytes"
                    }
                } finally {
                    markFlow.assertProductionProjectionStillAttached(editor)
                }

                markFlow.close(editor)
                val reopened = markFlow.openMarkdown("TASKS.md")
                check(markFlow.source(reopened) == expectedToggledSource) {
                    "reopened task-list source differs from exact persisted toggled Markdown"
                }
                check(!markFlow.isDirty(reopened)) {
                    "reopened task-list document must be clean"
                }
                check(
                    Files.readAllBytes(fixturePath)
                        .contentEquals(expectedToggledSource.toByteArray(StandardCharsets.UTF_8))
                ) {
                    "reopened task-list fixture bytes differ from exact saved source"
                }
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private data class TaskPresentation(
        val inlays: Int,
        val folds: Int,
        val concealed: Int,
        val taskRows: Int,
        val checkedTasks: Int,
    )

    private data class TaskToggleState(
        val source: String,
        val inlays: Int,
        val concealed: Int,
        val checkedTasks: Int,
        val toggles: Long,
        val caretOffset: Int,
    )
}
