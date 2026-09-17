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
import java.util.Properties
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
        val platformVersion = Properties().apply {
            Files.newInputStream(Path.of("gradle.properties")).use(::load)
        }.getProperty("platformVersion")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("platformVersion is required in gradle.properties")
        val targetIde = IdeInfo.IdeaUltimate.copy(version = platformVersion)

        try {
            Starter.newContext(
                testName = "markflow-starter-driver-production-wiring",
                testCase = TestCase(targetIde, LocalProjectInfo(projectPath)),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }.applyVMOptionsPatch {
                // The production native owner must attach even when the optional JCEF renderer is absent.
                addSystemProperty("ide.browser.jcef.enabled", false)
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

                val hostLocalImages = driver.withContext(OnDispatcher.EDT) {
                    bridge.hostLocalImages(editor.editor)
                }
                val hostExternalLinks = driver.withContext(OnDispatcher.EDT) {
                    bridge.hostExternalLinks(editor.editor)
                }
                val derivedFragments = driver.withContext(OnDispatcher.EDT) {
                    bridge.derivedFragments(editor.editor)
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
    fun rawHtmlFragments(editor: Editor): Int
}
