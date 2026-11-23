package com.algorist.markflow.e2e

import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import java.nio.file.Files
import java.nio.file.Path

private val STARTER_EAP_BUILD_NUMBER = Regex("""^\d{3}\.\d+\.\d+$""")

internal fun isStarterEapBuildNumber(version: String): Boolean =
    STARTER_EAP_BUILD_NUMBER.matches(version)

internal fun starterTestCase(platformVersion: String, projectPath: Path) =
    TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectPath)).let { base ->
        require(platformVersion.isNotBlank()) { "Starter platform version must not be blank" }
        if (isStarterEapBuildNumber(platformVersion)) {
            base.useEAP(platformVersion)
        } else {
            base.useRelease(platformVersion)
        }
    }

internal fun starterIdeExecutable(context: IDETestContext): Path =
    starterIdeExecutable(
        installationPath = context.ide.installationPath,
        executableFileName = context.testCase.ideInfo.executableFileName,
    )

internal fun starterIdeExecutable(
    installationPath: Path,
    executableFileName: String,
): Path {
    require(executableFileName.isNotBlank()) { "Starter executable filename must not be blank" }
    val installation = installationPath.toRealPath()
    val binDirectory = installation.resolve("bin").toRealPath()
    check(binDirectory.startsWith(installation)) {
        "Starter bin directory escaped its installed IDE"
    }

    // Mirrors the pinned LinuxIdeDistribution launch selection: prefer the native launcher,
    // then its .sh fallback. Do not infer an IDEA-specific binary name or cache/build path.
    val executable = listOf(
        binDirectory.resolve(executableFileName),
        binDirectory.resolve("$executableFileName.sh"),
    ).firstOrNull { candidate ->
        Files.isRegularFile(candidate) && Files.isExecutable(candidate)
    }?.toRealPath() ?: error(
        "Starter executable is unavailable for resolved IDE: $executableFileName"
    )

    check(executable.startsWith(binDirectory)) {
        "Starter executable escaped its installed IDE bin directory"
    }
    return executable
}

internal fun starterAppArmorProfileMatches(
    runtimeEvidence: String,
    expectedExecutable: Path,
): Boolean {
    val expected = expectedExecutable.toRealPath().toString()
    val current = runtimeEvidence.lineSequence()
        .singleOrNull { it.startsWith("idea_apparmor_current=") }
        ?.substringAfter('=')
        ?.trim()
        ?: return false
    if (current == expected) return true
    val prefix = "$expected ("
    if (!current.startsWith(prefix) || !current.endsWith(')')) return false
    val mode = current.substring(prefix.length, current.length - 1)
    return mode.isNotBlank() && '(' !in mode && ')' !in mode &&
        current == "$expected ($mode)"
}
