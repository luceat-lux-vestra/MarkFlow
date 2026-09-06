package com.algorist.markflow.browser

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SourceNativeCspHardeningTest : BasePlatformTestCase() {
    private val client = HttpClient.newHttpClient()
    private val htmlNonce = Regex("""\bnonce="([A-Za-z0-9_-]{43})"""")
    private val policyNonce = Regex("""'nonce-([A-Za-z0-9_-]{43})'""")

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

    fun testSourceNativeGetUsesFreshNonceBoundToHeaderAndEveryHtmlNonce() {
        val url = MarkFlowWebviewResourceManager.loadSourceNativeIndexUrl()!!
        val first = get(url)
        val second = get(url)

        assertEquals(200, first.statusCode())
        assertEquals(200, second.statusCode())
        val firstNonce = assertNonceContract(first)
        val secondNonce = assertNonceContract(second)
        assertNotSame(firstNonce, secondNonce)
        assertFalse(first.body().contains(SourceNativeCspResourceHandler.NONCE_PLACEHOLDER))
        assertFalse(second.body().contains(SourceNativeCspResourceHandler.NONCE_PLACEHOLDER))
    }

    fun testSourceNativeCspDeniesExecutableAndNetworkFallbacks() {
        val response = get(MarkFlowWebviewResourceManager.loadSourceNativeIndexUrl()!!)
        assertEquals(200, response.statusCode())
        val csp = response.headers().firstValue("Content-Security-Policy").orElse("")

        for (directive in listOf(
            "default-src 'none'",
            "connect-src 'none'",
            "object-src 'none'",
            "frame-src 'none'",
            "worker-src 'none'",
            "media-src 'none'",
            "manifest-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'",
            "img-src 'self'",
        )) {
            assertTrue("missing CSP directive: $directive", csp.contains(directive))
        }
        val scriptDirective = csp.split("; ").single { it.startsWith("script-src ") }
        assertTrue(scriptDirective.contains("'strict-dynamic'"))
        assertTrue(policyNonce.containsMatchIn(scriptDirective))
        assertFalse(scriptDirective.contains("'self'"))
        assertFalse(scriptDirective.contains("'unsafe-inline'"))
        assertFalse(scriptDirective.contains("'unsafe-eval'"))
        assertFalse(scriptDirective.contains("data:"))

        val styleElementDirective = csp.split("; ").single { it.startsWith("style-src-elem ") }
        assertTrue(policyNonce.containsMatchIn(styleElementDirective))
        assertFalse(styleElementDirective.contains("'unsafe-inline'"))
        assertEquals("style-src-attr 'unsafe-inline'", csp.split("; ").single { it.startsWith("style-src-attr ") })

        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""))
        assertEquals("same-origin", response.headers().firstValue("Cross-Origin-Resource-Policy").orElse(""))
    }

    fun testSourceNativeHeadUsesSamePolicyAndPostFailsClosed() {
        val url = MarkFlowWebviewResourceManager.loadSourceNativeIndexUrl()!!
        val head = client.send(
            HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, head.statusCode())
        assertTrue(policyNonce.containsMatchIn(head.headers().firstValue("Content-Security-Policy").orElse("")))
        assertTrue(head.headers().firstValue("Content-Length").orElse("").toLong() > 0)
        assertEquals("", head.body())

        val post = client.send(
            HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(405, post.statusCode())
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElse(""))
        assertEquals("", post.body())
    }

    fun testLegacyEntryDoesNotGainSourceNativeCspOrNonceSemantics() {
        val response = get(MarkFlowWebviewResourceManager.loadWebviewIndexUrl()!!)
        assertEquals(200, response.statusCode())
        assertFalse(response.headers().firstValue("Content-Security-Policy").isPresent)
        assertFalse(response.body().contains("property=\"csp-nonce\""))
        assertFalse(response.body().contains(SourceNativeCspResourceHandler.NONCE_PLACEHOLDER))
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""))
    }

    fun testTemplateTransformRejectsMalformedOrAmbiguousNonceAuthority() {
        val placeholder = SourceNativeCspResourceHandler.NONCE_PLACEHOLDER
        val validNonce = "A".repeat(43)
        val valid = """
            <meta property="csp-nonce" nonce="$placeholder">
            <style nonce="$placeholder"></style>
            <script nonce="$placeholder"></script>
        """.trimIndent()
        val rendered = SourceNativeCspResourceHandler.renderTemplate(valid, validNonce)
        assertNotNull(rendered)
        assertFalse(rendered!!.contains(placeholder))
        assertTrue(htmlNonce.findAll(rendered).all { it.groupValues[1] == validNonce })

        assertNull(SourceNativeCspResourceHandler.renderTemplate(valid.replaceFirst(placeholder, "foreign"), validNonce))
        assertNull(SourceNativeCspResourceHandler.renderTemplate(valid + placeholder, validNonce))
        assertNull(SourceNativeCspResourceHandler.renderTemplate(valid.replace("<style", "<meta property=\"csp-nonce\"", limit = 1), validNonce))
        assertNull(SourceNativeCspResourceHandler.renderTemplate(valid, "short"))
    }

    private fun get(url: String): HttpResponse<String> {
        return client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun assertNonceContract(response: HttpResponse<String>): String {
        val headerNonces = policyNonce.findAll(
            response.headers().firstValue("Content-Security-Policy").orElse("")
        ).map { it.groupValues[1] }.toSet()
        assertEquals(1, headerNonces.size)
        val nonce = headerNonces.single()
        assertTrue(SourceNativeCspResourceHandler.isValidNonce(nonce))

        val bodyNonces = htmlNonce.findAll(response.body()).map { it.groupValues[1] }.toList()
        assertTrue("expected Vite meta/style/script nonce attributes", bodyNonces.size >= 3)
        assertTrue(bodyNonces.all { it == nonce })
        assertEquals(1, Regex("""property="csp-nonce"""").findAll(response.body()).count())
        return nonce
    }
}
