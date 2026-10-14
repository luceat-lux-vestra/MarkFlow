package com.algorist.markflow.browser

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class MarkFlowWebviewJarExtractionTest : BasePlatformTestCase() {

    fun testExtractsOnlyRendererEntriesAndNestedAssets() {
        withExtractionFixture(
            listOf(
                "webview/derived-renderer.html" to "<html>renderer</html>",
                "webview/assets/runtime.js" to "export default true",
                "unrelated/secret.txt" to "not extracted",
            )
        ) { root, _ ->
            assertEquals("<html>renderer</html>", Files.readString(root.resolve("derived-renderer.html")))
            assertEquals("export default true", Files.readString(root.resolve("assets/runtime.js")))
            assertFalse(Files.exists(root.resolve("unrelated/secret.txt")))
        }
    }

    fun testRejectsArchivePathTraversalWithoutWritingOutsideRoot() {
        for (malicious in listOf(
            "webview/../escape.txt",
            "webview/assets/../../escape.txt",
            "webview/../../../escape.txt",
            "webview//tmp/escape.txt",
        )) {
            val sandbox = Files.createTempDirectory("markflow-jar-path-test-")
            try {
                val root = Files.createDirectory(sandbox.resolve("renderer"))
                val jar = createJar(sandbox.resolve("bundle.jar"), listOf(malicious to "pwned"))
                try {
                    JarFile(jar.toFile()).use {
                        try {
                            MarkFlowWebviewResourceManager.extractWebviewJarEntries(it, root)
                            fail("Path traversal was accepted: $malicious")
                        } catch (_: IllegalArgumentException) {
                            // Fail closed, without ever creating a destination outside root.
                        }
                    }
                    assertFalse("Outside file created by $malicious", Files.exists(sandbox.resolve("escape.txt")))
                    assertFalse("An unexpected asset was extracted", Files.list(root).use { it.findAny().isPresent })
                } finally {
                    // The production owner performs the same rollback on extraction failure.
                    root.toFile().deleteRecursively()
                }
            } finally {
                sandbox.toFile().deleteRecursively()
            }
        }
    }

    fun testRejectsSymlinkEscape() {
        val sandbox = Files.createTempDirectory("markflow-jar-symlink-test-")
        try {
            val root = Files.createDirectory(sandbox.resolve("renderer"))
            val outside = Files.createDirectory(sandbox.resolve("outside"))
            try {
                Files.createSymbolicLink(root.resolve("assets"), outside)
            } catch (_: UnsupportedOperationException) {
                return // File system does not support symbolic links.
            } catch (_: java.nio.file.FileSystemException) {
                return // Current runner does not permit symbolic links.
            }
            val jar = createJar(sandbox.resolve("bundle.jar"), listOf("webview/assets/escape.txt" to "pwned"))
            JarFile(jar.toFile()).use {
                try {
                    MarkFlowWebviewResourceManager.extractWebviewJarEntries(it, root)
                    fail("Symlink escape was accepted")
                } catch (_: IllegalArgumentException) {
                    // Directory symlink resolved outside the owned temp root.
                }
            }
            assertFalse(Files.exists(outside.resolve("escape.txt")))
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    private fun withExtractionFixture(entries: List<Pair<String, String>>, check: (Path, Path) -> Unit) {
        val sandbox = Files.createTempDirectory("markflow-jar-positive-test-")
        try {
            val root = Files.createDirectory(sandbox.resolve("renderer"))
            val jar = createJar(sandbox.resolve("bundle.jar"), entries)
            JarFile(jar.toFile()).use { MarkFlowWebviewResourceManager.extractWebviewJarEntries(it, root) }
            check(root, sandbox)
        } finally {
            sandbox.toFile().deleteRecursively()
        }
    }

    private fun createJar(path: Path, entries: List<Pair<String, String>>): Path {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            for ((name, value) in entries) {
                output.putNextEntry(JarEntry(name))
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.closeEntry()
            }
        }
        return path
    }
}
