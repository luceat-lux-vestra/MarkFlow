package com.algorist.markflow.e2e

import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
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

/** #153 proof that the installed plugin's normal opening path owns native presentation. */
class MarkFlowStarterProductionCutoverTest {
    @Test
    fun productionOpeningAutoAttachesNativePresentationAndLeavesLegacyProviderUnregistered() {
        val pluginPath = System.getProperty("path.to.build.plugin")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: error("path.to.build.plugin is required by the Starter/Driver integration-test task")
        val fixtureProject = Path.of("src/integrationTest/resources/projects/markdown-smoke")
            .toAbsolutePath()
            .normalize()
        val tempRoot = Files.createTempDirectory("markflow-production-cutover-")
        val projectPath = tempRoot.resolve("project")
        check(fixtureProject.toFile().copyRecursively(projectPath.toFile(), overwrite = true)) {
            "failed to copy deterministic production-cutover fixture project"
        }
        val fixturePath = projectPath.resolve("DEGRADED.md")
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
                testName = "markflow-production-native-cutover",
                testCase = TestCase(targetIde, LocalProjectInfo(projectPath)),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
            }.applyVMOptionsPatch {
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val markFlow = MarkFlowIdeDriver(this)
                val bridge = utility(NativeProductionCutoverBridgeRemote::class)
                val editor = markFlow.openMarkdown("DEGRADED.md")
                val stampBefore = markFlow.modificationStamp(editor)

                waitFor(
                    message = "production EditorFactory lifecycle auto-attaches MarkFlow presentation",
                    timeout = 10.seconds,
                    getter = {
                        withContext(OnDispatcher.EDT) {
                            bridge.isProductionOwned(editor.editor)
                        }
                    },
                    checker = { owned -> owned },
                )
                check(withContext(OnDispatcher.EDT) { !bridge.legacyBrowserProviderRegistered() }) {
                    "legacy JCEF-backed MarkFlow FileEditorProvider remains registered after production cutover"
                }
                check(withContext(OnDispatcher.EDT) { bridge.planReady(editor.editor) }) {
                    "production-owned native projection plan is not READY"
                }
                waitFor(
                    message = "hostile raw HTML is fail-closed by the production native owner",
                    timeout = 10.seconds,
                    getter = {
                        withContext(OnDispatcher.EDT) {
                            bridge.rawHtmlBlockedPreviews(editor.editor)
                        }
                    },
                    checker = { blocked -> blocked >= 1L },
                )
                check(markFlow.source(editor) == expectedSource) {
                    "production presentation changed authoritative Markdown while opening"
                }
                check(markFlow.modificationStamp(editor) == stampBefore) {
                    "production presentation changed Document modification stamp while opening"
                }
                check(!markFlow.isDirty(editor)) {
                    "production presentation dirtied the authoritative Document while opening"
                }

                val ownedBeforeClose = withContext(OnDispatcher.EDT) { bridge.productionOwnedEditors() }
                check(ownedBeforeClose >= 1) { "production manager owns no native editors" }
                markFlow.close(editor)
                waitFor(
                    message = "production EditorFactory release disposes the matching presentation owner",
                    timeout = 10.seconds,
                    getter = {
                        withContext(OnDispatcher.EDT) {
                            bridge.productionOwnedEditors()
                        }
                    },
                    checker = { owned -> owned < ownedBeforeClose },
                )
            }
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}

@Remote(
    value = "com.algorist.markflow.editor.native.NativeProductionCutoverE2EBridge",
    plugin = "com.algorist.markflow",
)
private interface NativeProductionCutoverBridgeRemote {
    fun isProductionOwned(editor: Editor): Boolean
    fun productionOwnedEditors(): Int
    fun legacyBrowserProviderRegistered(): Boolean
    fun planReady(editor: Editor): Boolean
    fun rawHtmlBlockedPreviews(editor: Editor): Long
}
