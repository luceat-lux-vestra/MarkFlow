package com.algorist.markflow.runtime

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class SourceNativeLocalImageCapabilityTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private val client: HttpClient = HttpClient.newHttpClient()

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("markflow-source-native-image-")
    }

    override fun tearDown() {
        try {
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testServesOnlyDocumentRelativeRasterImage() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val document = documentDirectory.resolve("readme.md")
        Files.writeString(document, "![local](./img/file.png)")
        val bytes = byteArrayOf(1, 2, 3, 4)
        val image = Files.createDirectories(documentDirectory.resolve("img")).resolve("file.png")
        Files.write(image, bytes)

        val capability = SourceNativeLocalImageCapability.create(document.toString())
        assertNotNull(capability)
        try {
            val response = get(capability!!.baseUrl + "img/file.png")
            assertEquals(200, response.statusCode())
            assertTrue(bytes.contentEquals(response.body()))
            assertEquals("image/png", response.headers().firstValue("Content-Type").orElse(null))
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null))
            assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(null))
        } finally {
            capability?.dispose()
        }
    }

    fun testHeadIsAllowedButPostIsRejected() {
        val (document, image) = createDocumentAndImage("image.png")
        Files.write(image, byteArrayOf(7, 8, 9))
        val capability = SourceNativeLocalImageCapability.create(document.toString())!!
        try {
            val head = HttpRequest.newBuilder(URI.create(capability.baseUrl + "image.png"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            val headResponse = client.send(head, HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(200, headResponse.statusCode())
            assertEquals(0, headResponse.body().size)

            val post = HttpRequest.newBuilder(URI.create(capability.baseUrl + "image.png"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            val postResponse = client.send(post, HttpResponse.BodyHandlers.discarding())
            assertEquals(405, postResponse.statusCode())
            assertEquals("GET, HEAD", postResponse.headers().firstValue("Allow").orElse(null))
        } finally {
            capability.dispose()
        }
    }

    fun testRejectsParentNestedEncodedAndDoubleEncodedTraversal() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val nested = Files.createDirectories(documentDirectory.resolve("a"))
        val document = documentDirectory.resolve("readme.md")
        Files.writeString(document, "test")
        Files.write(nested.resolve("inside.png"), byteArrayOf(1))
        Files.write(tempRoot.resolve("outside.png"), byteArrayOf(2))

        val capability = SourceNativeLocalImageCapability.create(document.toString())!!
        try {
            for (suffix in listOf(
                "../outside.png",
                "a/../../outside.png",
                "%2e%2e/outside.png",
                "%252e%252e/outside.png",
                "%2E%2E%2Foutside.png",
            )) {
                val response = get(capability.baseUrl + suffix)
                assertEquals("expected traversal rejection for $suffix", 404, response.statusCode())
            }
        } finally {
            capability.dispose()
        }
    }

    fun testRejectsSymlinkEscape() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val document = documentDirectory.resolve("readme.md")
        Files.writeString(document, "test")
        val outside = tempRoot.resolve("outside.png")
        Files.write(outside, byteArrayOf(9))
        val link = documentDirectory.resolve("linked.png")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }

        val capability = SourceNativeLocalImageCapability.create(document.toString())!!
        try {
            assertEquals(404, get(capability.baseUrl + "linked.png").statusCode())
        } finally {
            capability.dispose()
        }
    }

    fun testRejectsNonImageAndSvgEvenWhenExtensionIsImageLike() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val document = documentDirectory.resolve("readme.md")
        Files.writeString(document, "test")
        Files.writeString(documentDirectory.resolve("secret.txt"), "secret")
        Files.writeString(documentDirectory.resolve("active.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")

        val capability = SourceNativeLocalImageCapability.create(document.toString())!!
        try {
            assertEquals(404, get(capability.baseUrl + "secret.txt").statusCode())
            assertEquals(404, get(capability.baseUrl + "active.svg").statusCode())
        } finally {
            capability.dispose()
        }
    }

    fun testWrongTokenAndStaleCapabilityCannotServe() {
        val (document, image) = createDocumentAndImage("image.png")
        Files.write(image, byteArrayOf(5))
        val capability = SourceNativeLocalImageCapability.create(document.toString())!!
        val validUrl = capability.baseUrl + "image.png"
        val wrongTokenUrl = capability.baseUrl.replace(
            Regex("/__markflow_source_native_image__/[^/]+/"),
            "/__markflow_source_native_image__/wrong-token/",
        ) + "image.png"

        assertEquals(404, get(wrongTokenUrl).statusCode())
        assertEquals(200, get(validUrl).statusCode())

        capability.dispose()
        assertTrue(capability.isDisposed)
        capability.dispose() // idempotent

        try {
            get(validUrl)
            fail("disposed capability unexpectedly remained reachable")
        } catch (_: ConnectException) {
            // Per-capability server is stopped; stale URLs have no listener.
        } catch (_: IOException) {
            // Platform-dependent connection failure after server shutdown is equivalent evidence.
        }
    }

    fun testSizeAndMimeBoundariesAreExplicit() {
        assertTrue(SourceNativeLocalImageCapability.isAllowedLocalImageSize(0))
        assertTrue(SourceNativeLocalImageCapability.isAllowedLocalImageSize(SourceNativeLocalImageCapability.MAX_LOCAL_IMAGE_BYTES))
        assertFalse(SourceNativeLocalImageCapability.isAllowedLocalImageSize(SourceNativeLocalImageCapability.MAX_LOCAL_IMAGE_BYTES + 1))
        assertFalse(SourceNativeLocalImageCapability.isAllowedLocalImageSize(-1))

        assertTrue(SourceNativeLocalImageCapability.isAllowedLocalImageContentType("image/png"))
        assertTrue(SourceNativeLocalImageCapability.isAllowedLocalImageContentType("IMAGE/JPEG; charset=binary"))
        assertFalse(SourceNativeLocalImageCapability.isAllowedLocalImageContentType("image/svg+xml"))
        assertFalse(SourceNativeLocalImageCapability.isAllowedLocalImageContentType("text/html"))
        assertFalse(SourceNativeLocalImageCapability.isAllowedLocalImageContentType(null))
    }

    private fun createDocumentAndImage(imageName: String): Pair<Path, Path> {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val document = documentDirectory.resolve("readme.md")
        Files.writeString(document, "test")
        return document to documentDirectory.resolve(imageName)
    }

    private fun get(url: String): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }
}
