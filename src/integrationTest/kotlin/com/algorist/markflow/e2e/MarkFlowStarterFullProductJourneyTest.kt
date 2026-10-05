package com.algorist.markflow.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Editor
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

/**
 * Canonical #322 full-product journey.
 *
 * This is deliberately one real IDE process using the production plugin. Precise lexical and
 * hostile edge cases remain in lower-level/runtime evidence and the parallel Starter shards.
 */
class MarkFlowStarterFullProductJourneyTest {
    @Test
    fun userJourneyCoversProductionEditingOrdinaryRichDerivedAndPersistenceBoundaries() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val platformVersion = System.getProperty("markflow.test.platformVersion")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("markflow.test.platformVersion must be supplied by the Gradle integrationTest task")
        check(platformVersion == "2026.2.3") {
            "canonical full-product acceptance is authoritative only on IDEA 2026.2.3, got $platformVersion"
        }

        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-full-product-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver fixture project"
        }

        val readmePath = projectPath.resolve("README.md")
        val tasksPath = projectPath.resolve("TASKS.md")
        val productionPath = projectPath.resolve("PRODUCTION.md")
        val listIndentPath = projectPath.resolve("LIST-INDENT.md")
        val readmeSource = Files.readString(readmePath)
        val tasksSource = Files.readString(tasksPath)
        val productionSource = Files.readString(productionPath)
        val listIndentSource = Files.readString(listIndentPath)

        try {
            Starter.newContext(
                testName = "markflow-starter-full-product-journey",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectPath))
                    .useRelease(platformVersion),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val driver = this
                val markFlow = MarkFlowIdeDriver(driver)
                val bridge = driver.utility(FullProductProjectionBridgeRemote::class)

                // 1. Ordinary Markdown: real production opening, rendered presentation, source reveal,
                // source-local edit, paste, table interaction, Undo/Redo, save and reopen.
                val readme = markFlow.openMarkdown("README.md")
                check(markFlow.source(readme) == readmeSource)
                check(!markFlow.isDirty(readme))
                markFlow.assertProductionProjectionAttached(readme)

                val plainAnchor = readmeSource.indexOf("This document is opened")
                check(plainAnchor >= 0)
                markFlow.resetToSingleCaret(readme, plainAnchor)

                waitFor(
                    message = "canonical ordinary presentation is installed",
                    timeout = 10.seconds,
                    getter = {
                        OrdinaryState(
                            headingInlays = driver.withContext(OnDispatcher.EDT) {
                                bridge.headingOwnedInlays(readme.editor)
                            },
                            listInlays = markFlow.listOwnedInlays(readme),
                            tableInlays = markFlow.tableOwnedInlays(readme),
                        )
                    },
                    checker = { state ->
                        state.headingInlays >= 1 && state.listInlays >= 1 && state.tableInlays >= 1
                    },
                )

                val emphasis = "*emphasis*"
                val emphasisStart = readmeSource.indexOf(emphasis)
                check(emphasisStart >= 0)
                val emphasisEnd = emphasisStart + emphasis.length
                check(markFlow.hasProjection(readme, "EMPHASIS", emphasisStart, emphasisEnd))
                val stampBeforeReveal = markFlow.modificationStamp(readme)
                markFlow.clickText(readme, "emphasis")
                waitFor(
                    message = "canonical emphasis source reveal expands its delimiter",
                    timeout = 10.seconds,
                    getter = { markFlow.isFoldCollapsed(readme, emphasisStart, emphasisStart + 1) },
                    checker = { collapsed -> !collapsed },
                )
                check(markFlow.source(readme) == readmeSource)
                check(markFlow.modificationStamp(readme) == stampBeforeReveal)
                check(!markFlow.isDirty(readme))

                val formattingTarget = "formatme"
                val formattingStart = readmeSource.indexOf(formattingTarget)
                check(formattingStart >= 0)
                markFlow.selectRangeWithKeyboard(readme, formattingStart, formattingTarget.length)
                markFlow.invokeMarkdownBold(readme)
                waitFor(
                    message = "canonical Markdown formatting mutates only selected source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(readme) },
                    checker = { source ->
                        source == readmeSource.replaceRange(
                            formattingStart,
                            formattingStart + formattingTarget.length,
                            "**$formattingTarget**",
                        ) || source == readmeSource.replaceRange(
                            formattingStart,
                            formattingStart + formattingTarget.length,
                            "__" + formattingTarget + "__",
                        )
                    },
                )
                val formattedSource = markFlow.source(readme)
                markFlow.undo(readme)
                waitFor(
                    message = "canonical formatting Undo restores exact source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(readme) },
                    checker = { it == readmeSource },
                )
                markFlow.redo(readme)
                waitFor(
                    message = "canonical formatting Redo restores exact local edit",
                    timeout = 10.seconds,
                    getter = { markFlow.source(readme) },
                    checker = { it == formattedSource },
                )
                markFlow.undo(readme)
                waitFor(
                    message = "canonical formatting cleanup restores exact fixture",
                    timeout = 10.seconds,
                    getter = { markFlow.source(readme) },
                    checker = { it == readmeSource },
                )

                val pasteTarget = "outside-paste"
                val pasteStart = readmeSource.indexOf(pasteTarget)
                check(pasteStart >= 0)
                try {
                    markFlow.resetToSingleCaret(readme, pasteStart)
                    markFlow.selectRangeWithKeyboard(readme, pasteStart, pasteTarget.length)
                    markFlow.seedMarkdownClipboard("**canonical-paste**", "canonical-plain")
                    markFlow.paste(readme)
                    val pastedSource = readmeSource.replaceRange(
                        pasteStart,
                        pasteStart + pasteTarget.length,
                        "**canonical-paste**",
                    )
                    waitFor(
                        message = "canonical paste prefers Markdown outside code",
                        timeout = 10.seconds,
                        getter = { markFlow.source(readme) },
                        checker = { it == pastedSource },
                    )
                    markFlow.undo(readme)
                    waitFor(
                        message = "canonical paste Undo restores exact source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(readme) },
                        checker = { it == readmeSource },
                    )
                    markFlow.redo(readme)
                    waitFor(
                        message = "canonical paste Redo restores Markdown payload",
                        timeout = 10.seconds,
                        getter = { markFlow.source(readme) },
                        checker = { it == pastedSource },
                    )
                    markFlow.undo(readme)
                    waitFor(
                        message = "canonical paste cleanup restores exact source",
                        timeout = 10.seconds,
                        getter = { markFlow.source(readme) },
                        checker = { it == readmeSource },
                    )
                } finally {
                    markFlow.clearClipboard()
                }

                markFlow.resetToSingleCaret(readme, plainAnchor)
                waitFor(
                    message = "canonical table presentation is inactive and visible",
                    timeout = 10.seconds,
                    getter = { markFlow.tableOwnedInlays(readme) },
                    checker = { it >= 1 },
                )
                val tableRevealsBefore = markFlow.tableMouseReveals(readme)
                val sourceBeforeTableClick = markFlow.source(readme)
                val stampBeforeTableClick = markFlow.modificationStamp(readme)
                markFlow.clickNativeTableInlay(readme)
                waitFor(
                    message = "canonical table click reveals exact source",
                    timeout = 10.seconds,
                    getter = { markFlow.tableMouseReveals(readme) },
                    checker = { it == tableRevealsBefore + 1 },
                )
                check(markFlow.source(readme) == sourceBeforeTableClick)
                check(markFlow.modificationStamp(readme) == stampBeforeTableClick)

                markFlow.resetToSingleCaret(readme, readmeSource.length)
                val persistenceMarker = "\nCanonical full-product persistence marker"
                markFlow.appendAtEnd(readme, persistenceMarker)
                val persistedReadme = readmeSource + persistenceMarker
                check(markFlow.source(readme) == persistedReadme)
                markFlow.save(readme)
                check(
                    Files.readAllBytes(readmePath)
                        .contentEquals(persistedReadme.toByteArray(StandardCharsets.UTF_8))
                )
                markFlow.close(readme)
                val reopenedReadme = markFlow.openMarkdown("README.md")
                check(markFlow.source(reopenedReadme) == persistedReadme)
                check(!markFlow.isDirty(reopenedReadme))
                markFlow.close(reopenedReadme)

                // 2. Native list hierarchy interaction through IntelliJ Markdown Tab/Shift-Tab.
                val listIndent = markFlow.openMarkdown("LIST-INDENT.md")
                check(markFlow.source(listIndent) == listIndentSource)
                val childMarkerStart = listIndentSource.indexOf("- move-me")
                check(childMarkerStart >= 0)
                val childTextOffset = listIndentSource.indexOf("move-me", childMarkerStart) + 2
                val indentedListSource = listIndentSource.replaceRange(childMarkerStart, childMarkerStart, "  ")
                markFlow.resetToSingleCaret(listIndent, childTextOffset)
                waitFor(
                    message = "canonical flat list source is active before indent",
                    timeout = 10.seconds,
                    getter = { markFlow.listMaxDepth(listIndent) to markFlow.listOwnedInlays(listIndent) },
                    checker = { (depth, inlays) -> depth == 0 && inlays == 0 },
                )
                markFlow.indentMarkdownListItem(listIndent)
                waitFor(
                    message = "canonical list indent mutates exactly one hierarchy level",
                    timeout = 10.seconds,
                    getter = { markFlow.source(listIndent) to markFlow.listMaxDepth(listIndent) },
                    checker = { (source, depth) -> source == indentedListSource && depth == 1 },
                )
                markFlow.undo(listIndent)
                waitFor(
                    message = "canonical list indent Undo restores exact source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(listIndent) },
                    checker = { it == listIndentSource },
                )
                markFlow.redo(listIndent)
                waitFor(
                    message = "canonical list indent Redo restores exact nested source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(listIndent) },
                    checker = { it == indentedListSource },
                )
                val indentedTextOffset = indentedListSource.indexOf("move-me", childMarkerStart) + 2
                markFlow.resetToSingleCaret(listIndent, indentedTextOffset)
                markFlow.unindentMarkdownListItem(listIndent)
                waitFor(
                    message = "canonical list outdent restores exact flat source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(listIndent) to markFlow.listMaxDepth(listIndent) },
                    checker = { (source, depth) -> source == listIndentSource && depth == 0 },
                )
                markFlow.save(listIndent)
                check(Files.readString(listIndentPath) == listIndentSource)
                markFlow.close(listIndent)

                // 3. Task-list interaction through the real native checkbox, then restore the exact
                // fixture so later journey stages start from deterministic source.
                val tasks = markFlow.openMarkdown("TASKS.md")
                check(markFlow.source(tasks) == tasksSource)
                val taskTail = tasksSource.indexOf("Plain tail")
                check(taskTail >= 0)
                markFlow.resetToSingleCaret(tasks, taskTail)
                waitFor(
                    message = "canonical task-list native checkbox presentation is visible",
                    timeout = 10.seconds,
                    getter = {
                        Triple(
                            markFlow.taskRows(tasks),
                            markFlow.checkedTasks(tasks),
                            markFlow.listOwnedInlays(tasks),
                        )
                    },
                    checker = { (rows, checked, inlays) -> rows == 2 && checked == 1 && inlays == 1 },
                )
                val stateOffset = tasksSource.indexOf("[ ]") + 1
                check(stateOffset > 0)
                val toggledTasks = tasksSource.replaceRange(stateOffset, stateOffset + 1, "x")
                val taskTogglesBefore = markFlow.taskToggles(tasks)
                markFlow.clickFirstTaskCheckbox(tasks)
                waitFor(
                    message = "canonical native task click mutates exactly its state character",
                    timeout = 10.seconds,
                    getter = { markFlow.source(tasks) to markFlow.taskToggles(tasks) },
                    checker = { (source, toggles) ->
                        source == toggledTasks && toggles == taskTogglesBefore + 1
                    },
                )
                markFlow.undo(tasks)
                waitFor(
                    message = "canonical task Undo restores exact source",
                    timeout = 10.seconds,
                    getter = { markFlow.source(tasks) },
                    checker = { it == tasksSource },
                )
                markFlow.redo(tasks)
                waitFor(
                    message = "canonical task Redo restores exact toggle",
                    timeout = 10.seconds,
                    getter = { markFlow.source(tasks) },
                    checker = { it == toggledTasks },
                )
                markFlow.undo(tasks)
                waitFor(
                    message = "canonical task cleanup restores exact fixture",
                    timeout = 10.seconds,
                    getter = { markFlow.source(tasks) },
                    checker = { it == tasksSource },
                )
                markFlow.save(tasks)
                check(Files.readString(tasksPath) == tasksSource)
                markFlow.close(tasks)

                // 4. Retained production-rich consumers: local image/navigation, Mermaid, inline
                // and display KaTeX, and sanitized raw HTML in the same real IDE process.
                val production = markFlow.openMarkdown("PRODUCTION.md")
                check(markFlow.source(production) == productionSource)
                check(!markFlow.isDirty(production))
                val productionStamp = markFlow.modificationStamp(production)
                val inactiveRichAnchor = productionSource.indexOf("This fixture proves")
                check(inactiveRichAnchor >= 0)
                markFlow.resetToSingleCaret(production, inactiveRichAnchor)

                waitFor(
                    message = "canonical production derived and host presentation converges",
                    timeout = 30.seconds,
                    getter = {
                        driver.withContext(OnDispatcher.EDT) {
                            RichState(
                                hostLocalImages = bridge.hostLocalImages(production.editor),
                                hostExternalLinks = bridge.hostExternalLinks(production.editor),
                                derivedFragments = bridge.derivedFragments(production.editor),
                                pending = bridge.derivedPendingRequests(production.editor),
                                decoded = bridge.derivedDecodedArtifacts(production.editor),
                                inlays = bridge.derivedOwnedInlays(production.editor),
                                failures = bridge.derivedRendererFailures(production.editor),
                                missing = bridge.derivedMissingArtifacts(production.editor),
                                rawHtml = bridge.rawHtmlFragments(production.editor),
                            )
                        }
                    },
                    checker = { state ->
                        state.hostLocalImages >= 1 &&
                            state.hostExternalLinks >= 1 &&
                            state.derivedFragments >= 3 &&
                            state.pending == 0 &&
                            state.decoded >= 3 &&
                            state.inlays >= 3 &&
                            state.failures == 0L &&
                            state.missing == 0L &&
                            state.rawHtml >= 2
                    },
                )
                check(markFlow.source(production) == productionSource)
                check(markFlow.modificationStamp(production) == productionStamp)
                check(!markFlow.isDirty(production))
                check(Files.readString(productionPath) == productionSource)
                markFlow.close(production)
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private data class OrdinaryState(
        val headingInlays: Int,
        val listInlays: Int,
        val tableInlays: Int,
    )

    private data class RichState(
        val hostLocalImages: Int,
        val hostExternalLinks: Int,
        val derivedFragments: Int,
        val pending: Int,
        val decoded: Int,
        val inlays: Int,
        val failures: Long,
        val missing: Long,
        val rawHtml: Int,
    )
}

@Remote(value = "com.algorist.markflow.editor.native.NativeProjectionE2EBridge", plugin = "com.algorist.markflow")
private interface FullProductProjectionBridgeRemote {
    fun headingOwnedInlays(editor: Editor): Int
    fun hostLocalImages(editor: Editor): Int
    fun hostExternalLinks(editor: Editor): Int
    fun derivedFragments(editor: Editor): Int
    fun derivedPendingRequests(editor: Editor): Int
    fun derivedDecodedArtifacts(editor: Editor): Int
    fun derivedOwnedInlays(editor: Editor): Int
    fun derivedRendererFailures(editor: Editor): Long
    fun derivedMissingArtifacts(editor: Editor): Long
    fun rawHtmlFragments(editor: Editor): Int
}
