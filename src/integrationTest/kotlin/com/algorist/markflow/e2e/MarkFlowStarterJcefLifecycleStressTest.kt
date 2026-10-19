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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Rectangle
import java.awt.Robot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * #353 diagnostic only: isolate repeated real IntelliJ Markdown Preview
 * create/paint/hide/native-close from the 89-case differential corpus.
 *
 * Opt in explicitly:
 * MARKFLOW_JCEF_LIFECYCLE_STRESS=1 ./gradlew integrationTest -x buildPlugin
 *   -PplatformVersion=2026.2.3 -PstarterPackagedPlugin=true
 *   --tests com.algorist.markflow.e2e.MarkFlowStarterJcefLifecycleStressTest
 *
 * Use the same pinned JCEF/AppArmor/Xvfb/plugin-package setup as the existing
 * Full Differential worker. This test is not a visual acceptance or release gate.
 * No retry, sharding, native-close timeout expansion, or corpus modification.
 */
class MarkFlowStarterJcefLifecycleStressTest {
    private val iterations = 50
    private val fileName = "JCEF-LIFECYCLE-STRESS.md"
    private val fixture = """
        # Minimal reference preview lifecycle

        One plain Markdown document is reopened for every iteration.
        The rendered text must become visible before the browser is closed.

        ## Stable sample

        Repeated text exercises the exact same Markdown Preview content.
        There are no Mermaid diagrams, images, tables, or external resources.
        Only the Preview creation and disposal sequence changes over time.

        The first simple paragraph has sufficient width to be captured.
        The second simple paragraph supplies another visible line of content.
        The third simple paragraph helps distinguish text from a blank panel.
        The fourth simple paragraph keeps the visible vertical span nontrivial.
    """.trimIndent() + "\n"

    @Test
    @EnabledIfEnvironmentVariable(named = "MARKFLOW_JCEF_LIFECYCLE_STRESS", matches = "1")
    fun repeatedBundledPreviewCloseCompletesInOneIdeSession() {
        val root = Path.of("").toAbsolutePath().normalize()
        val plugin = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")) {
            "Starter requires path.to.build.plugin"
        })
        val version = requireNotNull(System.getProperty("markflow.test.platformVersion")) {
            "Starter requires markflow.test.platformVersion"
        }
        check(version == "2026.2.3") { "Use the pinned Full Differential platform" }
        val renderMode = System.getenv("MARKFLOW_JCEF_STRESS_RENDER_MODE") ?: "osr"
        require(renderMode == "osr" || renderMode == "windowed")
        val expectedOsr = renderMode == "osr"
        val sourceHead = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(root.toFile()).start().let { process ->
                val sha = process.inputStream.bufferedReader().readText().trim()
                check(process.waitFor() == 0 && sha.matches(Regex("[0-9a-f]{40}")))
                sha
            }
        System.getenv("STARTER_SOURCE_SHA")?.takeIf(String::isNotBlank)?.let {
            check(it == sourceHead) { "Diagnostic plugin checkout is not exact-source" }
        }

        val output = root.resolve("build/e2e-evidence/derived-content/jcef-lifecycle-stress")
        Files.createDirectories(output)
        Files.writeString(output.resolve("run-contract.txt"),
            "schema=markflow-jcef-lifecycle-stress/v1\n" +
                "source_head=$sourceHead\nide_version=$version\n" +
                "iterations=$iterations\nfixture=$fileName\n" +
                "scope=diagnostic-only-not-full-differential-acceptance\n" +
                "render_mode=$renderMode\nretries=0\n", StandardCharsets.UTF_8)

        val sandbox = Files.createTempDirectory("markflow-353-jcef-stress-")
        val project = sandbox.resolve("project")
        var completed = 0
        var deferredFailure: Throwable? = null
        try {
            val base = root.resolve("src/integrationTest/resources/projects/markdown-smoke")
            check(base.toFile().copyRecursively(project.toFile(), overwrite = true))
            val fixturePath = project.resolve(fileName)
            check(!Files.exists(fixturePath)) { "Lifecycle fixture name collision" }
            val fixtureBytes = fixture.toByteArray(StandardCharsets.UTF_8)
            Files.write(fixturePath, fixtureBytes)

            Starter.newContext(
                testName = "markflow-353-minimal-jcef-lifecycle-stress",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project))
                    .useRelease(version)
            ).apply {
                PluginConfigurator(this).installPluginFromPath(plugin)
            }.applyVMOptionsPatch {
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("ide.browser.jcef.jsQueryPoolSize", "10000")
                if (!expectedOsr) addSystemProperty("ide.browser.jcef.markdownView.osr.enabled", false)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("sun.java2d.uiScale", "1.0")
                addSystemProperty("ide.ui.scale", "1.0")
            }.runIdeWithDriver(runTimeout = 35.minutes).useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val driver = this
                val markFlow = MarkFlowIdeDriver(driver)
                val preview = driver.utility(JcefStressPreviewRemote::class)
                val chrome = driver.utility(JcefStressVisualRemote::class)
                driver.withContext(OnDispatcher.EDT) {
                    chrome.prepareEditorChromeBeforeOpen()
                }
                Files.writeString(output.resolve("jcef-runtime-evidence.txt"),
                    driver.withContext(OnDispatcher.EDT) {
                        preview.jcefRuntimeEvidence()
                    }, StandardCharsets.UTF_8)
                val robot = Robot()
                val nonblank = MarkFlowStarterDifferentialPreviewSmokeTest()
                for (number in 1..iterations) {
                    val iteration = number.toString().padStart(3, '0')
                    val dest = output.resolve("iteration-$iteration")
                    Files.createDirectories(dest)
                    fun record(phase: String, evidence: String = "") {
                        Files.writeString(dest.resolve("lifecycle.txt"),
                            "phase=$phase\n" + evidence,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                        Files.writeString(output.resolve("progress.tsv"),
                            "$iteration\t$phase\t" + System.currentTimeMillis() + "\n",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    }

                    record("open-start")
                    try {
                        // Close the whole FileEditor after every iteration so the
                        // next open necessarily exercises the bundled browser path.
                        val opened = markFlow.openMarkdown(fileName)
                        markFlow.assertProductionProjectionAttached(opened)
                        val remoteEditor = opened.editor
                        check(markFlow.source(opened) == fixture)
                        val stamp = markFlow.modificationStamp(opened)
                        record("source-opened")
                        driver.withContext(OnDispatcher.EDT) {
                            preview.showReferenceAtSourceLine(remoteEditor, 0)
                        }
                        waitFor(
                            message = "Stress Preview visible: $iteration",
                            timeout = 20.seconds,
                            getter = { driver.withContext(OnDispatcher.EDT) {
                                preview.referenceShowing(remoteEditor) &&
                                    preview.referencePanelShowing(remoteEditor)
                            } },
                            checker = { it }
                        )
                        val identity = driver.withContext(OnDispatcher.EDT) {
                            preview.referenceBrowserIdentity(remoteEditor)
                        }
                        record("panel-showing", identity)
                        check(identity.contains("registry_markdown_osr=$expectedOsr\n") &&
                            identity.contains("browser_offscreen_rendering=$expectedOsr\n")) {
                            "JCEF rendering mode mismatch for $renderMode iteration $iteration: $identity"
                        }
                        check(identity.contains("jbcef_disposed=false\n") &&
                            identity.contains("panel_showing=true\n")) {
                            "Stress iteration has no live bundled JCEF browser: $iteration"
                        }
                        val browserIdentity = identity.lineSequence()
                            .first { it.startsWith("browser_identity=") }
                            .substringAfter('=')
                        val xywh = driver.withContext(OnDispatcher.EDT) {
                            preview.referenceBounds(remoteEditor).split(',').map(String::toInt)
                        }
                        check(xywh.size == 4)
                        val bounds = Rectangle(xywh[0], xywh[1], xywh[2], xywh[3])
                        record("first-paint-wait")
                        waitFor(
                            message = "Bundled JCEF first paint: stress iteration $iteration",
                            timeout = 20.seconds,
                            getter = {
                                robot.waitForIdle()
                                val image = robot.createScreenCapture(bounds)
                                check(ImageIO.write(image, "png",
                                    dest.resolve("reference-paint-probe.png").toFile()))
                                nonblank.referenceHasVisibleContent(image)
                            },
                            checker = { it }
                        )
                        record("first-paint-observed", "first_paint_nonblank=true\n")
                        check(driver.withContext(OnDispatcher.EDT) {
                            preview.sourceText(remoteEditor) == fixture &&
                                preview.sourceStamp(remoteEditor) == stamp &&
                                !preview.sourceUnsaved(remoteEditor)
                        }) { "Reference paint changed the source: $iteration" }

                        val marker = dest.resolve("native-close.marker").toAbsolutePath().normalize()
                        record("hide-start", "close_marker=$marker\n")
                        driver.withContext(OnDispatcher.EDT) {
                            preview.armDetachedReferenceBrowserCloseMarker(marker.toString())
                            preview.restoreNative(remoteEditor)
                        }
                        waitFor(
                            message = "Bundled JCEF panel detached: stress iteration $iteration",
                            timeout = 20.seconds,
                            getter = { driver.withContext(OnDispatcher.EDT) {
                                preview.nativeShowing(remoteEditor) &&
                                    !preview.referencePanelAttached(remoteEditor)
                            } },
                            checker = { it }
                        )
                        record("panel-detached", driver.withContext(OnDispatcher.EDT) {
                            preview.detachedReferenceBrowserEvidence()
                        })
                        // This is host process polling only: do not call Driver/EDT
                        // from the wait getter. A missing callback is a hard FAIL.
                        waitFor(
                            message = "Bundled native onBeforeClose: stress iteration $iteration",
                            timeout = 20.seconds,
                            getter = { Files.exists(marker) },
                            checker = { it }
                        )
                        val markerBody = Files.readString(marker)
                        check(markerBody.contains("cef_before_close_observed=true\n") &&
                            markerBody.contains("browser_identity=$browserIdentity\n")) {
                            "Native close marker is not for this exact browser: $iteration"
                        }
                        val closed = driver.withContext(OnDispatcher.EDT) {
                            preview.detachedReferenceBrowserEvidence()
                        }
                        record("native-close-observed", closed)
                        check(closed.contains("jbcef_disposed=true\n") &&
                            closed.contains("cef_before_close_observed=true\n") &&
                            closed.contains("native_close_complete=true\n")) {
                            "JBCef wrapper/native close did not both complete: $iteration"
                        }
                        check(driver.withContext(OnDispatcher.EDT) {
                            preview.sourceText(remoteEditor) == fixture &&
                                preview.sourceStamp(remoteEditor) == stamp &&
                                !preview.sourceUnsaved(remoteEditor)
                        }) { "Lifecycle close changed the source: $iteration" }
                        check(Files.readAllBytes(fixturePath).contentEquals(fixtureBytes))
                        markFlow.closeMarkdown(remoteEditor, fileName)
                        completed++
                        record("file-editor-closed")
                    } catch (cause: Throwable) {
                        // Preserve the first substantive failure and its last
                        // screenshot/marker evidence. Do not rerun or recover.
                        record("failed", "exception=" + cause.javaClass.name +
                            "\nmessage=" + (cause.message ?: "NONE") + "\n")
                        deferredFailure = cause
                        break
                    }
                }
                // As in Full Differential, defer a fatal exception until the
                // Starter IDE shutdown boundary so diagnostics aren't gathered
                // through a potentially dead Driver/EDT.
            }
            deferredFailure?.let { throw it }
            check(completed == iterations) {
                "Stress diagnostic executed only $completed/$iterations lifecycle iterations"
            }
            Files.writeString(output.resolve("result.txt"),
                "result=PASS\ncompleted=$completed\n" +
                    "scope=JCEF_LIFECYCLE_ONLY_NOT_VISUAL_ACCEPTANCE\n",
                StandardCharsets.UTF_8)
        } catch (cause: Throwable) {
            Files.writeString(output.resolve("failure.txt"),
                "result=FAIL\ncompleted=$completed\n" +
                    "exception=" + cause.javaClass.name + "\n" +
                    "message=" + (cause.message ?: "NONE") + "\n",
                StandardCharsets.UTF_8)
            throw cause
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeVisualAcceptanceE2EBridge",
    plugin = "com.algorist.markflow"
)
private interface JcefStressVisualRemote {
    fun prepareEditorChromeBeforeOpen()
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeDifferentialPreviewE2EBridge",
    plugin = "com.algorist.markflow"
)
private interface JcefStressPreviewRemote {
    fun showReferenceAtSourceLine(editor: Editor, line: Int): String
    fun referenceShowing(editor: Editor): Boolean
    fun referencePanelShowing(editor: Editor): Boolean
    fun referencePanelAttached(editor: Editor): Boolean
    fun referenceBrowserIdentity(editor: Editor): String
    fun referenceBounds(editor: Editor): String
    fun jcefRuntimeEvidence(): String
    fun sourceText(editor: Editor): String
    fun sourceStamp(editor: Editor): Long
    fun sourceUnsaved(editor: Editor): Boolean
    fun armDetachedReferenceBrowserCloseMarker(markerPath: String)
    fun restoreNative(editor: Editor)
    fun detachedReferenceBrowserEvidence(): String
    fun nativeShowing(editor: Editor): Boolean
}
