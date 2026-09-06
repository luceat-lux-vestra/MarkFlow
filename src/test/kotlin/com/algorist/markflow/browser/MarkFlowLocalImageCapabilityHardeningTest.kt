package com.algorist.markflow.browser

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class MarkFlowLocalImageCapabilityHardeningTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private val client = HttpClient.newHttpClient()

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("markflow-local-image-capability-")
        assertNotNull(MarkFlowWebviewResourceManager.acquire())
    }

    override fun tearDown() {
        try {
            MarkFlowWebviewResourceManager.release()
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testTargetCapabilityTokensAreOpaqueHighEntropyAndDistinct() {
        val document = createDocument()
        val first = MarkFlowWebviewResourceManager.registerSourceNativeLocalImage(document.toString())
        val second = MarkFlowWebviewResourceManager.registerSourceNativeLocalImage(document.toString())
        assertNotNull(first)
        assertNotNull(second)

        try {
            val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")
            assertTrue(tokenPattern.matches(first!!.token))
            assertTrue(tokenPattern.matches(second!!.token))
            assertFalse(first.token.contains(document.fileName.toString()))
            assertFalse(second.token.contains(document.fileName.toString()))
            assertNotEquals(first.token, second.token)
            assertTrue(first.baseUrl.contains(SourceNativeWebviewRoutePolicy.LOCAL_IMAGE_PREFIX))
        } finally {
            MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(first?.token)
            MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(second?.token)
        }
    }

    fun testWrongStaleAndQueryBearingTargetCapabilitiesFailClosed() {
        val document = createDocument()
        Files.write(document.parent.resolve("image.png"), byteArrayOf(1, 2, 3, 4))
        val registration = MarkFlowWebviewResourceManager.registerSourceNativeLocalImage(document.toString())!!

        val wrongToken = "A".repeat(43)
        val wrongUrl = registration.baseUrl.replace(registration.token, wrongToken) + "image.png"
        assertEquals(404, get(wrongUrl).statusCode())

        val validUrl = registration.baseUrl + "image.png"
        assertTrue(SourceNativeWebviewRoutePolicy.isCanonicalLocalImage(URI.create(validUrl).rawPath))
        assertEquals(200, get(validUrl).statusCode())
        assertEquals(404, get("$validUrl?unexpected=query").statusCode())
        MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(registration.token)
        assertEquals(404, get(validUrl).statusCode())
    }

    fun testTargetEncodedTraversalAndMalformedPathsFailClosed() {
        val document = createDocument()
        Files.write(tempRoot.resolve("outside.png"), byteArrayOf(9, 8, 7))
        val registration = MarkFlowWebviewResourceManager.registerSourceNativeLocalImage(document.toString())!!

        try {
            assertEquals(404, get(registration.baseUrl + "%2e%2e/outside.png").statusCode())
            assertEquals(404, get(registration.baseUrl + "%252e%252e%252foutside.png").statusCode())
            assertEquals(404, get(registration.baseUrl + "%2foutside.png").statusCode())

            assertNull(SourceNativeLocalImagePolicy.decodeRawRelativePath("bad%"))
            assertNull(SourceNativeLocalImagePolicy.decodeRawRelativePath("%GG"))
            assertNull(SourceNativeLocalImagePolicy.decodeRawRelativePath("%252e%252e%252foutside.png"))
            assertNull(SourceNativeLocalImagePolicy.decodeRawRelativePath("%5coutside.png"))
            assertNull(SourceNativeLocalImagePolicy.resolve(document.parent, "../outside.png"))
            assertNull(SourceNativeLocalImagePolicy.resolve(document.parent, "nested/../image.png"))
            assertNull(SourceNativeLocalImagePolicy.resolve(document.parent, "C:\\outside.png"))
        } finally {
            MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(registration.token)
        }
    }

    fun testTargetCapabilityRejectsSymlinkEscape() {
        val document = createDocument()
        val outside = Files.write(tempRoot.resolve("outside.png"), byteArrayOf(9, 8, 7))
        val link = document.parent.resolve("link.png")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }

        assertNull(SourceNativeLocalImagePolicy.resolve(document.parent, "link.png"))
    }

    fun testTargetCapabilityAllowsOnlyGetAndHead() {
        val document = createDocument()
        Files.write(document.parent.resolve("image.png"), byteArrayOf(1, 2, 3, 4))
        val registration = MarkFlowWebviewResourceManager.registerSourceNativeLocalImage(document.toString())!!
        val url = registration.baseUrl + "image.png"

        try {
            val post = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build()
            val postResponse = client.send(post, HttpResponse.BodyHandlers.discarding())
            assertEquals(405, postResponse.statusCode())
            assertEquals("GET, HEAD", postResponse.headers().firstValue("Allow").orElse(""))

            val head = HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
            val headResponse = client.send(head, HttpResponse.BodyHandlers.discarding())
            assertEquals(200, headResponse.statusCode())
            assertEquals("4", headResponse.headers().firstValue("Content-Length").orElse(""))
        } finally {
            MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(registration.token)
        }
    }

    fun testTargetCapabilityRejectsSvgAndNonImageMedia() {
        val document = createDocument()
        Files.writeString(document.parent.resolve("active.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")
        Files.writeString(document.parent.resolve("secret.txt"), "secret")
        val registration = MarkFlowWebviewResourceManager.registerSourceNativeLocalImage(document.toString())!!

        try {
            assertEquals(404, get(registration.baseUrl + "active.svg").statusCode())
            assertEquals(404, get(registration.baseUrl + "secret.txt").statusCode())
        } finally {
            MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(registration.token)
        }
    }

    fun testTargetRasterResponseHasSecurityAndPrivacyHeaders() {
        val document = createDocument()
        Files.write(document.parent.resolve("image.png"), byteArrayOf(1, 2, 3, 4))
        val registration = MarkFlowWebviewResourceManager.registerSourceNativeLocalImage(document.toString())!!

        try {
            val response = get(registration.baseUrl + "image.png")
            assertEquals(200, response.statusCode())
            assertEquals("image/png", response.headers().firstValue("Content-Type").orElse(""))
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
            assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""))
            assertEquals("same-origin", response.headers().firstValue("Cross-Origin-Resource-Policy").orElse(""))
        } finally {
            MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(registration.token)
        }
    }

    fun testLegacyEndpointRemainsOnItsOriginalNamespaceAndCachePolicy() {
        val document = createDocument()
        val legacyBytes = byteArrayOf(5, 4, 3, 2, 1)
        Files.write(document.parent.resolve("legacy.png"), legacyBytes)
        val registration = MarkFlowWebviewResourceManager.registerLocalDocument(document.toString())!!

        try {
            assertTrue(registration.baseUrl.contains("/__markflow_local__/"))
            assertFalse(registration.baseUrl.contains(SourceNativeWebviewRoutePolicy.LOCAL_IMAGE_PREFIX))
            val response = get(registration.baseUrl + "legacy.png")
            assertEquals(200, response.statusCode())
            assertTrue(legacyBytes.contentEquals(response.body()))
            assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""))
        } finally {
            MarkFlowWebviewResourceManager.unregisterLocalDocument(registration.token)
        }
    }

    private fun createDocument(): Path {
        val directory = Files.createDirectories(tempRoot.resolve("docs"))
        val document = directory.resolve("readme.md")
        Files.writeString(document, "# test")
        return document
    }

    private fun get(url: String): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }
}
