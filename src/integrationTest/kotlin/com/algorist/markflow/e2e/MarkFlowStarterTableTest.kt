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
import java.util.Properties
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MarkFlowStarterTableTest {
    @Test
    fun revealsNativeTableByMouseAndEditsExactSourceWithUndoRedo() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-driver-table-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver table fixture project"
        }
        val fixturePath = projectPath.resolve("README.md")
        val expectedSource = Files.readString(fixturePath)

        val tableSource = "| Table key | Table value |\n| --- | --- |\n| alpha | table-edit |"
        val tableStart = expectedSource.indexOf(tableSource)
        check(tableStart >= 0 && expectedSource.lastIndexOf(tableSource) == tableStart) {
            "deterministic table fixture must contain exactly one supported GFM table"
        }
        val firstCellText = "Table key"
        val firstCellOffset = expectedSource.indexOf(firstCellText, tableStart)
        check(firstCellOffset >= tableStart) { "deterministic table first cell is missing" }
        val editTarget = "table-edit"
        val editStart = expectedSource.indexOf(editTarget, tableStart)
        check(editStart >= tableStart) { "deterministic table edit target is missing" }
        val editedText = "table-edited"
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
                testName = "markflow-starter-driver-table",
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
                val editor = markFlow.openMarkdown("README.md")
                check(markFlow.source(editor) == expectedSource) {
                    "opened native editor Document differs from deterministic table fixture source"
                }
                check(!markFlow.isDirty(editor)) {
                    "opening the table fixture must not dirty the authoritative Document"
                }

                // Keep the primary caret outside the table before attaching the pre-cutover E2E
                // controller. The table must therefore start in its inactive rich presentation.
                markFlow.clickText(editor, "deterministic")
                val sourceBeforeReveal = markFlow.source(editor)
                val stampBeforeReveal = markFlow.modificationStamp(editor)

                markFlow.attachNativeProjection(editor)
                try {
                    check(markFlow.isNativeProjectionAttached(editor)) {
                        "native projection E2E controller did not attach for table acceptance"
                    }
                    check(markFlow.isNativeProjectionPlanReady(editor)) {
                        "native table projection plan is not READY"
                    }
                    waitFor(
                        message = "inactive supported GFM table owns the platform-safe fold partition and block inlay",
                        timeout = 10.seconds,
                        getter = {
                            Triple(
                                markFlow.tableModels(editor),
                                markFlow.tableOwnedInlays(editor),
                                markFlow.tableOwnedFolds(editor),
                            )
                        },
                        checker = { (models, inlays, folds) -> models == 1 && inlays == 1 && folds == 2 },
                    )
                    check(markFlow.source(editor) == sourceBeforeReveal) {
                        "inactive table presentation changed authoritative Markdown source"
                    }
                    check(markFlow.modificationStamp(editor) == stampBeforeReveal) {
                        "inactive table presentation changed the authoritative Document modification stamp"
                    }
                    check(!markFlow.isDirty(editor)) {
                        "inactive table presentation dirtied the authoritative Document"
                    }

                    val mouseRevealsBefore = markFlow.tableMouseReveals(editor)
                    markFlow.clickNativeTableInlay(editor)
                    waitFor(
                        message = "normal mouse click reveals the exact table source",
                        timeout = 10.seconds,
                        getter = {
                            Triple(
                                markFlow.tableOwnedInlays(editor),
                                markFlow.tableOwnedFolds(editor),
                                markFlow.tableMouseReveals(editor),
                            )
                        },
                        checker = { (inlays, folds, reveals) ->
                            inlays == 0 && folds == 0 && reveals == mouseRevealsBefore + 1
                        },
                    )
                    check(markFlow.primaryCaretOffset(editor) == firstCellOffset) {
                        "native table mouse reveal did not place the caret at the first parser-proven cell"
                    }
                    check(markFlow.source(editor) == sourceBeforeReveal) {
                        "mouse table reveal changed authoritative Markdown source"
                    }
                    check(markFlow.modificationStamp(editor) == stampBeforeReveal) {
                        "mouse table reveal changed the authoritative Document modification stamp"
                    }
                    check(!markFlow.isDirty(editor)) {
                        "mouse table reveal dirtied the authoritative Document"
                    }

                    markFlow.selectRangeWithKeyboard(editor, editStart, editTarget.length)
                    markFlow.typeText(editor, editedText)
                    waitFor(
                        message = "real keyboard edit changes only the revealed table target span",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedEditedSource },
                    )
                    check(markFlow.isDirty(editor)) {
                        "real table source edit must dirty the authoritative Document"
                    }

                    markFlow.undo(editor)
                    waitFor(
                        message = "Undo restores exact original table source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )
                    markFlow.redo(editor)
                    waitFor(
                        message = "Redo restores exact source-local table edit",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedEditedSource },
                    )
                    markFlow.undo(editor)
                    waitFor(
                        message = "table edit cleanup restores exact original source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )

                    markFlow.clickText(editor, "deterministic")
                    waitFor(
                        message = "moving the caret away restores inactive table presentation",
                        timeout = 10.seconds,
                        getter = { markFlow.tableOwnedInlays(editor) to markFlow.tableOwnedFolds(editor) },
                        checker = { (inlays, folds) -> inlays == 1 && folds == 2 },
                    )
                } finally {
                    markFlow.detachNativeProjection(editor)
                }

                check(markFlow.source(editor) == expectedSource) {
                    "table acceptance proof did not restore exact original source"
                }
                check(Files.readAllBytes(fixturePath).contentEquals(expectedSource.toByteArray(StandardCharsets.UTF_8))) {
                    "unsaved table acceptance proof changed fixture bytes"
                }
                markFlow.save(editor)
                check(!markFlow.isDirty(editor)) {
                    "explicit SaveAll after table proof must clear platform dirty tracking"
                }
                check(Files.readAllBytes(fixturePath).contentEquals(expectedSource.toByteArray(StandardCharsets.UTF_8))) {
                    "table acceptance cleanup changed deterministic fixture bytes"
                }
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}
