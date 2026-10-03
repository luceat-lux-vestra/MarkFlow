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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Exact production-opening proof for the #153 cutover wiring. */
class MarkFlowStarterProductionWiringTest {
    @Test
    fun productionOpeningAutomaticallyWiresNativeHostDerivedAndRawHtmlConsumersWithoutOwningSource() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-starter-production-wiring-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic Starter/Driver fixture project"
        }
        val fixturePath = projectPath.resolve("PRODUCTION.md")
        val expectedSource = Files.readString(fixturePath)
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
                testName = "markflow-starter-driver-production-wiring",
                testCase = targetTestCase,
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }.applyVMOptionsPatch {
                // This scenario proves the normal production derived-renderer path. The separate
                // degraded-path Starter test owns JCEF-disabled ordinary source-editing acceptance.
                addSystemProperty("ide.browser.jcef.enabled", true)
                addSystemProperty("ide.browser.jcef.testMode.enabled", true)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val driver = this
                val markFlow = MarkFlowIdeDriver(driver)
                val editor = markFlow.openMarkdown("PRODUCTION.md")
                val bridge = driver.utility(NativeProductionWiringBridgeRemote::class)
                val sourceBefore = markFlow.source(editor)
                val stampBefore = markFlow.modificationStamp(editor)

                check(sourceBefore == expectedSource) {
                    "production native opening changed the deterministic fixture source"
                }
                check(!markFlow.isDirty(editor)) {
                    "production native opening dirtied the authoritative Document"
                }

                waitFor(
                    message = "production native presentation controller attaches automatically",
                    timeout = 10.seconds,
                    getter = {
                        driver.withContext(OnDispatcher.EDT) {
                            bridge.isAttached(editor.editor) && bridge.planReady(editor.editor)
                        }
                    },
                    checker = { ready -> ready },
                )

                // The product contract reveals exact Markdown source for the active construct.
                // Starter can open a file with its primary caret inside the Mermaid block, which
                // would intentionally remove that inlay/fold and make an inactive-render assertion
                // self-contradictory. Move to a plain paragraph before proving rendered state.
                val inactiveDerivedCaretOffset = expectedSource.indexOf("This fixture proves")
                check(inactiveDerivedCaretOffset >= 0) {
                    "deterministic production fixture lost the plain-paragraph caret anchor"
                }
                markFlow.resetToSingleCaret(editor, inactiveDerivedCaretOffset)

                val rendererWaitFailure = runCatching {
                    waitFor(
                        message = "production Mermaid/KaTeX renderer produces decoded native presentation",
                        timeout = 30.seconds,
                        getter = {
                            driver.withContext(OnDispatcher.EDT) {
                                bridge.derivedFragments(editor.editor) >= 3 &&
                                    bridge.derivedPendingRequests(editor.editor) == 0 &&
                                    bridge.derivedDecodedArtifacts(editor.editor) >= 3 &&
                                    bridge.derivedOwnedInlays(editor.editor) >= 3 &&
                                    bridge.derivedOwnedFolds(editor.editor) >= 3 &&
                                    bridge.derivedRendererFailures(editor.editor) == 0L &&
                                    bridge.derivedMissingArtifacts(editor.editor) == 0L
                            }
                        },
                        checker = { ready -> ready },
                    )
                }.exceptionOrNull()
                if (rendererWaitFailure != null) {
                    val evidence = driver.withContext(OnDispatcher.EDT) {
                        "fragments=${bridge.derivedFragments(editor.editor)} " +
                            "pending=${bridge.derivedPendingRequests(editor.editor)} " +
                            "decoded=${bridge.derivedDecodedArtifacts(editor.editor)} " +
                            "inlays=${bridge.derivedOwnedInlays(editor.editor)} " +
                            "folds=${bridge.derivedOwnedFolds(editor.editor)} " +
                            "failures=${bridge.derivedRendererFailures(editor.editor)} " +
                            "missing=${bridge.derivedMissingArtifacts(editor.editor)}"
                    }
                    throw AssertionError(
                        "production Mermaid/KaTeX presentation did not converge: $evidence",
                        rendererWaitFailure,
                    )
                }

                val hostLocalImages = driver.withContext(OnDispatcher.EDT) {
                    bridge.hostLocalImages(editor.editor)
                }
                val hostExternalLinks = driver.withContext(OnDispatcher.EDT) {
                    bridge.hostExternalLinks(editor.editor)
                }
                val derivedFragments = driver.withContext(OnDispatcher.EDT) {
                    bridge.derivedFragments(editor.editor)
                }
                val derivedPendingRequests = driver.withContext(OnDispatcher.EDT) {
                    bridge.derivedPendingRequests(editor.editor)
                }
                val derivedDecodedArtifacts = driver.withContext(OnDispatcher.EDT) {
                    bridge.derivedDecodedArtifacts(editor.editor)
                }
                val derivedOwnedInlays = driver.withContext(OnDispatcher.EDT) {
                    bridge.derivedOwnedInlays(editor.editor)
                }
                val derivedOwnedFolds = driver.withContext(OnDispatcher.EDT) {
                    bridge.derivedOwnedFolds(editor.editor)
                }
                val derivedRendererFailures = driver.withContext(OnDispatcher.EDT) {
                    bridge.derivedRendererFailures(editor.editor)
                }
                val derivedMissingArtifacts = driver.withContext(OnDispatcher.EDT) {
                    bridge.derivedMissingArtifacts(editor.editor)
                }
                val rawHtmlFragments = driver.withContext(OnDispatcher.EDT) {
                    bridge.rawHtmlFragments(editor.editor)
                }
                check(hostLocalImages >= 1) {
                    "production controller did not wire the local-image host consumer: $hostLocalImages"
                }
                check(hostExternalLinks >= 1) {
                    "production controller did not wire the external-navigation host consumer: $hostExternalLinks"
                }
                check(derivedFragments >= 3) {
                    "production controller did not wire representative Mermaid/KaTeX fragments: $derivedFragments"
                }
                check(derivedPendingRequests == 0) {
                    "production derived renderer left pending requests: $derivedPendingRequests"
                }
                check(derivedDecodedArtifacts >= 3) {
                    "production derived renderer did not decode representative artifacts: $derivedDecodedArtifacts"
                }
                check(derivedOwnedInlays >= 3) {
                    "production derived renderer did not install representative native inlays: $derivedOwnedInlays"
                }
                check(derivedOwnedFolds >= 3) {
                    "production derived renderer did not install representative source folds: $derivedOwnedFolds"
                }
                check(derivedRendererFailures == 0L) {
                    "production derived renderer reported failures: $derivedRendererFailures"
                }
                check(derivedMissingArtifacts == 0L) {
                    "production derived renderer completed without presentation artifacts: $derivedMissingArtifacts"
                }
                check(rawHtmlFragments >= 2) {
                    "production controller did not wire representative raw HTML fragments: $rawHtmlFragments"
                }

                check(markFlow.source(editor) == sourceBefore) {
                    "production host/derived/raw-HTML wiring changed authoritative Markdown source"
                }
                check(markFlow.modificationStamp(editor) == stampBefore) {
                    "production host/derived/raw-HTML wiring changed the Document modification stamp"
                }
                check(!markFlow.isDirty(editor)) {
                    "production host/derived/raw-HTML wiring dirtied the authoritative Document"
                }
                check(Files.readString(fixturePath) == expectedSource) {
                    "production host/derived/raw-HTML wiring changed fixture bytes"
                }
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}

@Remote(value = "com.algorist.markflow.editor.native.NativeProjectionE2EBridge", plugin = "com.algorist.markflow")
private interface NativeProductionWiringBridgeRemote {
    fun isAttached(editor: Editor): Boolean
    fun planReady(editor: Editor): Boolean
    fun hostLocalImages(editor: Editor): Int
    fun hostExternalLinks(editor: Editor): Int
    fun derivedFragments(editor: Editor): Int
    fun derivedPendingRequests(editor: Editor): Int
    fun derivedDecodedArtifacts(editor: Editor): Int
    fun derivedOwnedInlays(editor: Editor): Int
    fun derivedOwnedFolds(editor: Editor): Int
    fun derivedRendererFailures(editor: Editor): Long
    fun derivedMissingArtifacts(editor: Editor): Long
    fun rawHtmlFragments(editor: Editor): Int
}
