package com.algorist.markflow.runtime

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SourceNativeAssetIsolationTest : BasePlatformTestCase() {
    fun testBuiltSourceNativeEntryUsesOnlyDedicatedAssetNamespace() {
        val sourceNative = resourceText("webview/source-native.html")
        val referencedPaths = Regex("(?:src|href)=\"([^\"]+)\"")
            .findAll(sourceNative)
            .map { it.groupValues[1] }
            .filter { it.endsWith(".js") || it.endsWith(".css") }
            .toList()

        assertTrue("source-native entry must reference executable/static assets", referencedPaths.isNotEmpty())
        assertTrue(
            "every target asset must remain under source-native-assets/: $referencedPaths",
            referencedPaths.all { it.startsWith("./source-native-assets/") },
        )
        assertFalse(sourceNative.contains("./assets/"))
    }

    fun testBuiltSourceNativeExecutableGraphContainsNoDirectFetchPrimitive() {
        val sourceNative = resourceText("webview/source-native.html")
        val scriptPaths = Regex("""src="([^"]+\.js)"""")
            .findAll(sourceNative)
            .map { it.groupValues[1].removePrefix("./") }
            .toList()

        assertTrue("source-native entry must reference at least one script", scriptPaths.isNotEmpty())
        for (scriptPath in scriptPaths) {
            val script = resourceText("webview/$scriptPath")
            assertFalse(
                "source-native executable graph must not contain a direct fetch() network primitive: $scriptPath",
                script.contains("fetch("),
            )
        }
    }

    fun testLegacyEntryDoesNotImportTargetAssetNamespace() {
        val legacy = resourceText("webview/index.html")
        assertFalse(legacy.contains("source-native-assets/"))
    }

    private fun resourceText(path: String): String {
        val resource = javaClass.classLoader.getResource(path)
        assertNotNull("missing built webview resource: $path", resource)
        return resource!!.readText()
    }
}
