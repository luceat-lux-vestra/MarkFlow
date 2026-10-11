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
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * #350 real OS pointer pilot. This intentionally opens a normal production
 * MarkFlow editor inside the actual IDEA process. No synthetic EditorMouseEvent,
 * direct reveal calls, or parallel test-owned presentation controller are used.
 */
class MarkFlowStarterHeadingRobotTest {
    @Test
    fun realRobotClickRevealsSourceReplacingHeadingAndPreservesAuthoritativeDocument() {
        val plugin = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
        val version = requireNotNull(System.getProperty("markflow.test.platformVersion"))
        check(version == "2026.2.3")
        val source = "# Robot Heading\n\nTail\n"
        val tail = source.indexOf("Tail") + 1
        val headingContent = source.indexOf("Robot Heading")
        val sandbox = Files.createTempDirectory("markflow-350-heading-robot-")
        val project = sandbox.resolve("project")
        val fixture = project.resolve("heading-robot.md")
        val output = Path.of("build/e2e-evidence/derived-content/heading-robot").toAbsolutePath()
        Files.createDirectories(output)
        try {
            check(Path.of("src/integrationTest/resources/projects/markdown-smoke")
                .toAbsolutePath().toFile().copyRecursively(project.toFile(), overwrite = true))
            Files.writeString(fixture, source, StandardCharsets.UTF_8)
            Starter.newContext(
                testName = "markflow-350-real-os-heading-fold-click",
                testCase = TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(project)).useRelease(version)
            ).apply {
                PluginConfigurator(this).installPluginFromPath(plugin)
            }.applyVMOptionsPatch {
                addSystemProperty("markflow.starter.headingRobotPilot", true)
                addSystemProperty("ide.browser.jcef.enabled", false)
                addSystemProperty("idea.trust.all.projects", true)
                addSystemProperty("jb.consents.confirmation.enabled", false)
            }.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val driver = this
                val markFlow = MarkFlowIdeDriver(driver)
                val bridge = driver.utility(HeadingRobotProductionRemote::class)
                val opened = markFlow.openMarkdown("heading-robot.md")
                markFlow.assertProductionProjectionAttached(opened)
                check(markFlow.source(opened) == source)
                check(!markFlow.isDirty(opened))
                val initialStamp = markFlow.modificationStamp(opened)
                markFlow.resetToSingleCaret(opened, tail)
                fun state(): Map<String, String> = driver.withContext(OnDispatcher.EDT) {
                    bridge.headingRobotEvidence(opened.editor)
                }.split(';').associate { pair ->
                    val pieces = pair.split('=', limit = 2)
                    check(pieces.size == 2) { "Invalid production heading evidence $pair" }
                    pieces[0] to pieces[1]
                }
                waitFor(
                    message = "actual production controller installed one custom heading fold",
                    timeout = 20.seconds,
                    getter = { state() },
                    checker = { evidence ->
                        evidence["models"] == "1" && evidence["custom"] == "1" &&
                            evidence["physicalCustom"] == "1" && evidence["folds"] == "1" &&
                            evidence["inlays"] == "0" && evidence["showing"] == "true" &&
                            evidence["reveals"] == "0"
                    },
                )
                val before = state()
                check(before["caret"] == tail.toString())
                check(before["stamp"] == initialStamp.toString())
                val point = driver.withContext(OnDispatcher.EDT) {
                    bridge.headingRobotScreenPoint(opened.editor)
                }.split(',').map(String::toInt)
                check(point.size == 2 && point.all { it >= 0 })
                val (x, y) = point
                val screen = Toolkit.getDefaultToolkit().screenSize
                check(x < screen.width && y < screen.height) { "Pointer outside display: $point" }
                val snapX = (x - 80).coerceIn(0, maxOf(0, screen.width - 240))
                val snapY = (y - 80).coerceIn(0, maxOf(0, screen.height - 160))
                val rect = Rectangle(
                    snapX, snapY, minOf(240, screen.width - snapX), minOf(160, screen.height - snapY)
                )
                val robot = Robot()
                robot.waitForIdle()
                check(ImageIO.write(robot.createScreenCapture(rect), "png",
                    output.resolve("before-click.png").toFile()))
                robot.mouseMove(x, y)
                robot.delay(100)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.delay(60)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                robot.waitForIdle()
                waitFor(
                    message = "OS Robot click invokes owned custom fold source-reveal path",
                    timeout = 20.seconds,
                    getter = { state() },
                    checker = { evidence ->
                        evidence["reveals"] == "1" && evidence["folds"] == "0" &&
                            evidence["custom"] == "0" && evidence["physicalCustom"] == "0" &&
                            evidence["caret"] == headingContent.toString()
                    },
                )
                val after = state()
                check(after["stamp"] == initialStamp.toString())
                check(markFlow.source(opened) == source)
                check(!markFlow.isDirty(opened))
                check(ImageIO.write(robot.createScreenCapture(rect), "png",
                    output.resolve("after-click.png").toFile()))
                markFlow.resetToSingleCaret(opened, tail)
                waitFor(
                    message = "moving source caret outside heading reattaches custom fold",
                    timeout = 20.seconds,
                    getter = { state() },
                    checker = { it["custom"] == "1" && it["folds"] == "1" && it["reveals"] == "1" },
                )
                check(markFlow.source(opened) == source)
                check(markFlow.modificationStamp(opened) == initialStamp)
                check(!markFlow.isDirty(opened))
                check(Files.readString(fixture, StandardCharsets.UTF_8) == source)
                Files.writeString(output.resolve("source.md"), source, StandardCharsets.UTF_8)
                Files.writeString(output.resolve("evidence.txt"),
                    "schema=markflow-real-os-heading-robot/v1\n" +
                        "source_head=" + requireNotNull(System.getenv("STARTER_SOURCE_SHA")) + "\n" +
                        "ide=" + version + "\n" +
                        "robot_screen_point=" + point.joinToString(",") + "\n" +
                        "before=" + before + "\n" +
                        "after=" + after + "\n" +
                        "after_reattach=" + state() + "\n", StandardCharsets.UTF_8)
                markFlow.assertProductionProjectionStillAttached(opened)
                markFlow.close(opened)
            }
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }
}

@Remote(value = "com.algorist.markflow.editor.native.NativeProjectionE2EBridge",
    plugin = "com.algorist.markflow")
private interface HeadingRobotProductionRemote {
    fun headingRobotEvidence(editor: Editor): String
    fun headingRobotScreenPoint(editor: Editor): String
}
