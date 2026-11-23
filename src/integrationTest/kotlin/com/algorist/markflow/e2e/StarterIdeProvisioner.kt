package com.algorist.markflow.e2e

import com.intellij.ide.starter.runner.TestContainer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pre-launch Starter IDE provisioning used by CI security policy.
 *
 * This deliberately calls the same pinned Starter resolution path as the E2E tests and records the
 * InstalledIde result. AppArmor must consume this output instead of reconstructing Starter cache,
 * build or workspace paths.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <platform-version> <metadata-output>" }
    val platformVersion = args[0].trim()
    require(platformVersion.isNotEmpty()) { "platform version must not be blank" }

    val output = Path.of(args[1]).toAbsolutePath().normalize()
    val projectRoot = Path.of("").toAbsolutePath().normalize()
    val target = starterTestCase(platformVersion, projectRoot)

    val (resolvedBuild, installedIde) = TestContainer.resolveIDE(target.ideInfo)
    require(resolvedBuild == installedIde.build) {
        "Starter installer build identity mismatch: resolved=" + resolvedBuild +
            " installed=" + installedIde.build
    }
    val installationPath = installedIde.installationPath.toRealPath()
    val executablePath = starterIdeExecutable(
        installationPath = installationPath,
        executableFileName = target.ideInfo.executableFileName,
    )

    output.parent?.let(Files::createDirectories)
    Files.writeString(
        output,
        listOf(
            "platform_version=$platformVersion",
            "resolved_build=$resolvedBuild",
            "installation_path=$installationPath",
            "executable_path=$executablePath",
        ).joinToString(separator = "\n", postfix = "\n"),
        StandardCharsets.UTF_8,
    )
}
