package com.algorist.markflow.browser

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SourceNativeCspHardeningTest : BasePlatformTestCase() {
    private val client = HttpClient.newHttpClient()

    override fun setUp() {
        super.setUp()
        assertNotNull(MarkFlowWebviewResourceManager.acquire())
    }

    override fun tearDown() {
        try {
            MarkFlowWebviewResourceManager.release()
        } finally {
            super.tearDown()
        }
    }

    fun testBuiltSourceNativeEntryContainsOnlyExpectedViteNonceMarkers() {
        val sourceNative = resourceText("webview/source-native.html")
        val placeholder = SourceNativeCspPolicy.BUILD_NONCE_PLACEHOLDER
        val allMarkers = Regex(Regex.escape(placeholder)).findAll(sourceNative).count()
        val nonceAttributes = Regex("""nonce=([\"'])${Regex.escape(placeholder)}\1""")
            .findAll(sourceNative)
            .count()
        val nonceMeta = Regex("""<meta\b[^>]*\bproperty=([\"'])csp-nonce\1[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(sourceNative)
            .toList()

        assertTrue("Vite must emit nonce metadata plus nonce-bound active tags", allMarkers >= 3)
        assertEquals("the build placeholder must exist only in nonce attributes", allMarkers, nonceAttributes)
        assertEquals("exactly one Vite csp-nonce metadata element is required", 1, nonceMeta.size)
        assertTrue(nonceMeta.single().value.contains(placeholder))
    }

    fun testTemplateTransformationFailsClosedOnMissingDuplicateOrUnsafeMarkers() {
        val placeholder = SourceNativeCspPolicy.BUILD_NONCE_PLACEHOLDER
        val nonce = "A".repeat(43)
        val valid = """
            <meta property="csp-nonce" nonce="$placeholder">
            <style nonce="$placeholder"></style>
            <script nonce="$placeholder"></script>
        """.trimIndent()

        val transformed = SourceNativeCspPolicy.replaceBuildNoncePlaceholder(valid, nonce)
        assertNotNull(transformed)
        assertFalse(transformed!!.contains(placeholder))
        assertTrue(transformed.contains("nonce=\"$nonce\""))

        assertNull(SourceNativeCspPolicy.replaceBuildNoncePlaceholder(valid.replace("property=\"csp-nonce\"", "property=\"other\""), nonce))
        assertNull(SourceNativeCspPolicy.replaceBuildNoncePlaceholder(valid + "<!-- $placeholder -->", nonce))
        assertNull(SourceNativeCspPolicy.replaceBuildNoncePlaceholder(valid.replaceFirst("<meta", "<meta property=\"csp-nonce\" nonce=\"$placeholder\"><meta"), nonce))
        assertNull(SourceNativeCspPolicy.replaceBuildNoncePlaceholder(valid, "short"))
        assertNull(SourceNativeCspPolicy.replaceBuildNoncePlaceholder(valid, placeholder))
    }

    fun testConsecutiveSourceNativeGetsUseFreshNonceAndExactCspBinding() {
        val url = MarkFlowWebviewResourceManager.loadSourceNativeIndexUrl()
        assertNotNull(url)

        val first = get(url!!)
        val second = get(url)
        assertEquals(200, first.statusCode())
        assertEquals(200, second.statusCode())

        val firstNonce = assertResponseBinding(first)
        val secondNonce = assertResponseBinding(second)
        assertFalse("every source-native HTML response must mint a fresh nonce", firstNonce == secondNonce)
    }

    fun testSourceNativeCspHasRequiredDenialsAndNoBroadScriptEntitlement() {
        val url = MarkFlowWebviewResourceManager.loadSourceNativeIndexUrl()
        assertNotNull(url)
        val response = get(url!!)
        assertEquals(200, response.statusCode())

        val csp = response.headers().firstValue("Content-Security-Policy").orElse("")
        val nonce = extractScriptNonce(csp)
        val directives = parseDirectives(csp)

        assertEquals("'none'", directives["default-src"])
        assertEquals("'nonce-$nonce' 'strict-dynamic'", directives["script-src"])
        assertEquals("'none'", directives["style-src"])
        assertEquals("'nonce-$nonce'", directives["style-src-elem"])
        assertEquals("'unsafe-inline'", directives["style-src-attr"])
        assertEquals("'self'", directives["img-src"])
        assertEquals("'self'", directives["font-src"])
        for (directive in listOf(
            "connect-src",
            "object-src",
            "frame-src",
            "worker-src",
            "media-src",
            "manifest-src",
            "base-uri",
            "form-action",
            "frame-ancestors",
        )) {
            assertEquals("$directive must fail closed", "'none'", directives[directive])
        }

        val script = directives["script-src"].orEmpty()
        for (forbidden in listOf("'unsafe-inline'", "'unsafe-eval'", "data:", "'self'", "http:", "https:", "*")) {
            assertFalse("script-src must not contain broad allowance $forbidden", script.contains(forbidden))
        }
    }

    fun testSourceNativeEntryRejectsUnsupportedMethodsAndStaticAliases() {
        val url = MarkFlowWebviewResourceManager.loadSourceNativeIndexUrl()
        assertNotNull(url)

        val post = client.send(
            HttpRequest.newBuilder(URI.create(url!!)).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(405, post.statusCode())
        assertEquals("GET", post.headers().firstValue("Allow").orElse(""))

        val head = client.send(
            HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(405, head.statusCode())
        assertEquals("GET", head.headers().firstValue("Allow").orElse(""))

        assertEquals(404, get(url.replace("/source-native.html", "/x/../source-native.html")).statusCode())
        assertEquals(404, get(url.replace("/source-native.html", "/%73ource-native.html")).statusCode())
    }

    fun testLegacyEntryRemainsOutsideSourceNativeCspAndNonceServing() {
        val url = MarkFlowWebviewResourceManager.loadWebviewIndexUrl()
        assertNotNull(url)
        val response = get(url!!)

        assertEquals(200, response.statusCode())
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""))
        assertFalse(response.headers().firstValue("Content-Security-Policy").isPresent)
        assertFalse(response.body().contains("property=\"csp-nonce\""))
        assertFalse(response.body().contains(SourceNativeCspPolicy.BUILD_NONCE_PLACEHOLDER))
    }

    private fun assertResponseBinding(response: HttpResponse<String>): String {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
        val csp = response.headers().firstValue("Content-Security-Policy").orElse("")
        val nonce = extractScriptNonce(csp)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(nonce))

        val htmlNonces = Regex("""\bnonce=([\"'])([A-Za-z0-9_-]{43})\1""")
            .findAll(response.body())
            .map { it.groupValues[2] }
            .toList()
        assertTrue("served HTML must retain nonce-bound Vite and inline tags", htmlNonces.size >= 3)
        assertTrue("every served HTML nonce must exactly match response CSP", htmlNonces.all { it == nonce })
        assertFalse(response.body().contains(SourceNativeCspPolicy.BUILD_NONCE_PLACEHOLDER))
        return nonce
    }

    private fun extractScriptNonce(csp: String): String {
        val match = Regex("""'nonce-([A-Za-z0-9_-]{43})'""").find(csp)
        assertNotNull("CSP must contain one canonical script nonce", match)
        return match!!.groupValues[1]
    }

    private fun parseDirectives(csp: String): Map<String, String> =
        csp.split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .associate { directive ->
                val separator = directive.indexOf(' ')
                if (separator < 0) directive to "" else directive.substring(0, separator) to directive.substring(separator + 1)
            }

    private fun get(url: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun resourceText(path: String): String {
        val resource = javaClass.classLoader.getResource(path)
        assertNotNull("missing built webview resource: $path", resource)
        return resource!!.readText()
    }
}
