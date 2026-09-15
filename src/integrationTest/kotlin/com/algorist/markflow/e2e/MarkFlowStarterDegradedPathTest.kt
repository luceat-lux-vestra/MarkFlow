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

class MarkFlowStarterDegradedPathTest {
    @Test
    fun keepsExactSourceEditableWhenProjectionDegradesWithJcefDisabled() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-driver-degraded-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver degraded-path fixture project"
        }
        val fixturePath = projectPath.resolve("DEGRADED.md")
        val expectedSource = Files.readString(fixturePath)

        val rendererTarget = "renderer-edit"
        val rendererEdited = "renderer-edited"
        val malformedTarget = "malformed-edit"
        val malformedEdited = "malformed-edited"
        val rawHtmlTarget = "opaque-edit"
        val rawHtmlEdited = "opaque-edited"

        requireUnique(expectedSource, rendererTarget)
        requireUnique(expectedSource, malformedTarget)
        requireUnique(expectedSource, rawHtmlTarget)
        check(expectedSource.startsWith("# ")) {
            "degraded-path fixture must start with one heading syntax marker"
        }
        check(expectedSource.contains("**$malformedTarget") && !expectedSource.contains("$malformedTarget**")) {
            "degraded-path fixture must retain unmatched Markdown syntax"
        }
        check(expectedSource.contains("<script>alert('$rawHtmlTarget')</script>")) {
            "degraded-path fixture must retain unsupported raw HTML"
        }

        val expectedRendererSource = expectedSource.replace(rendererTarget, rendererEdited)
        val expectedMalformedSource = expectedRendererSource.replace(malformedTarget, malformedEdited)
        val expectedPersistedSource = expectedMalformedSource.replace(rawHtmlTarget, rawHtmlEdited)

        val platformVersion = Properties().apply {
            Files.newInputStream(Path.of("gradle.properties")).use(::load)
        }.getProperty("platformVersion")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("platformVersion is required in gradle.properties")
        val targetIde = IdeInfo.IdeaUltimate.copy(version = platformVersion)

        try {
            Starter.newContext(
                testName = "markflow-starter-driver-degraded-paths",
                testCase = TestCase(targetIde, LocalProjectInfo(projectPath)),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }.applyVMOptionsPatch {
                // Ordinary source editing must remain a complete acceptance path without JCEF.
                addSystemProperty("ide.browser.jcef.enabled", false)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val markFlow = MarkFlowIdeDriver(this)
                val degradation = NativeDegradationE2EDriver(this)
                val editor = markFlow.openMarkdown("DEGRADED.md")

                check(markFlow.source(editor) == expectedSource) {
                    "opened degraded-path fixture differs from authoritative source"
                }
                check(!markFlow.isDirty(editor)) {
                    "opening unsupported/malformed source must not dirty the Document"
                }

                // Keep the heading inactive so the READY plan owns at least one syntax fold before
                // the synthetic renderer failure is injected through the narrow E2E seam.
                markFlow.clickText(editor, rendererTarget)
                val sourceBeforeDegrade = markFlow.source(editor)
                val stampBeforeDegrade = markFlow.modificationStamp(editor)
                markFlow.attachNativeProjection(editor)
                try {
                    check(markFlow.isNativeProjectionAttached(editor)) {
                        "native projection E2E controller did not attach"
                    }
                    check(markFlow.isNativeProjectionPlanReady(editor)) {
                        "degraded-path acceptance did not start from a READY projection plan"
                    }
                    waitFor(
                        message = "READY projection owns native syntax presentation before failure",
                        timeout = 10.seconds,
                        getter = { degradation.ownedFolds(editor) },
                        checker = { folds -> folds >= 1 },
                    )

                    degradation.degradeToSource(editor)
                    check(degradation.isDegradedToSource(editor)) {
                        "typed renderer failure did not leave the controller in DEGRADED_TO_SOURCE"
                    }
                    check(degradation.ownedHighlighters(editor) == 0) {
                        "source fallback retained MarkFlow-owned highlighters"
                    }
                    check(degradation.ownedFolds(editor) == 0) {
                        "source fallback retained MarkFlow-owned folds"
                    }
                    check(markFlow.source(editor) == sourceBeforeDegrade) {
                        "entering source fallback changed authoritative Markdown source"
                    }
                    check(markFlow.modificationStamp(editor) == stampBeforeDegrade) {
                        "entering source fallback changed Document modification stamp"
                    }
                    check(!markFlow.isDirty(editor)) {
                        "entering source fallback dirtied the authoritative Document"
                    }

                    val rendererStart = markFlow.source(editor).indexOf(rendererTarget)
                    check(rendererStart >= 0)
                    markFlow.selectRangeWithKeyboard(editor, rendererStart, rendererTarget.length)
                    check(degradation.isDegradedToSource(editor)) {
                        "caret/selection activity escaped typed source fallback before user edit"
                    }
                    markFlow.typeText(editor, rendererEdited)
                    waitFor(
                        message = "real keyboard edit succeeds while native projection is degraded",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedRendererSource },
                    )
                    check(markFlow.isDirty(editor)) {
                        "user edit during source fallback did not dirty the authoritative Document"
                    }

                    // A source mutation schedules the normal planner again. Recovery must not be a
                    // prerequisite for the edit above, but it must remain possible afterward.
                    waitFor(
                        message = "native projection recovers through the normal document refresh path",
                        timeout = 10.seconds,
                        getter = { markFlow.isNativeProjectionPlanReady(editor) },
                        checker = { ready -> ready },
                    )

                    markFlow.undo(editor)
                    waitFor(
                        message = "Undo restores exact source after editing during renderer failure",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedSource },
                    )
                    markFlow.redo(editor)
                    waitFor(
                        message = "Redo restores exact degraded-path user edit",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedRendererSource },
                    )

                    val malformedStart = markFlow.source(editor).indexOf(malformedTarget)
                    check(malformedStart >= 0)
                    markFlow.selectRangeWithKeyboard(editor, malformedStart, malformedTarget.length)
                    markFlow.typeText(editor, malformedEdited)
                    waitFor(
                        message = "unmatched Markdown remains exact-source editable",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedMalformedSource },
                    )
                    check(markFlow.source(editor).contains("**$malformedEdited")) {
                        "malformed Markdown syntax was normalized or rewritten"
                    }

                    val rawHtmlStart = markFlow.source(editor).indexOf(rawHtmlTarget)
                    check(rawHtmlStart >= 0)
                    markFlow.selectRangeWithKeyboard(editor, rawHtmlStart, rawHtmlTarget.length)
                    markFlow.typeText(editor, rawHtmlEdited)
                    waitFor(
                        message = "unsupported raw HTML remains exact-source editable",
                        timeout = 10.seconds,
                        getter = { markFlow.source(editor) },
                        checker = { source -> source == expectedPersistedSource },
                    )
                    check(markFlow.source(editor).contains("<script>alert('$rawHtmlEdited')</script>")) {
                        "unsupported raw HTML was normalized or rewritten"
                    }

                    markFlow.save(editor)
                    check(!markFlow.isDirty(editor)) {
                        "saving degraded-path edits must leave the authoritative Document clean"
                    }
                    check(
                        Files.readAllBytes(fixturePath)
                            .contentEquals(expectedPersistedSource.toByteArray(StandardCharsets.UTF_8))
                    ) {
                        "degraded-path save did not persist exact expected Markdown bytes"
                    }
                } finally {
                    if (markFlow.isNativeProjectionAttached(editor)) {
                        markFlow.detachNativeProjection(editor)
                    }
                }

                markFlow.close(editor)
                val reopened = markFlow.openMarkdown("DEGRADED.md")
                check(markFlow.source(reopened) == expectedPersistedSource) {
                    "reopened degraded-path source differs from exact persisted Markdown"
                }
                check(!markFlow.isDirty(reopened)) {
                    "reopened degraded-path source must be clean"
                }
                check(
                    Files.readAllBytes(fixturePath)
                        .contentEquals(expectedPersistedSource.toByteArray(StandardCharsets.UTF_8))
                ) {
                    "reopened degraded-path fixture bytes changed unexpectedly"
                }
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private fun requireUnique(source: String, target: String) {
        val first = source.indexOf(target)
        check(first >= 0 && source.lastIndexOf(target) == first) {
            "deterministic degraded-path fixture must contain exactly one '$target'"
        }
    }
}
