package com.algorist.markflow.browser

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class MarkFlowLocalImageResourceTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("markflow-local-image-test-")
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

    fun testResolvesImageRelativeToMarkdownDocument() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val image = Files.createDirectories(documentDirectory.resolve("img")).resolve("file.png")
        Files.write(image, byteArrayOf(1, 2, 3))

        val resolved = MarkFlowWebviewResourceManager.resolveLocalResourcePath(
            documentDirectory = documentDirectory.toRealPath(),
            relativePath = "./img/file.png"
        )

        assertEquals(image.toRealPath(), resolved)
    }

    fun testRejectsParentRelativeImageOutsideDocumentDirectory() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val outsideImage = tempRoot.resolve("outside.png")
        Files.write(outsideImage, byteArrayOf(7, 8, 9))

        val resolved = MarkFlowWebviewResourceManager.resolveLocalResourcePath(
            documentDirectory = documentDirectory.toRealPath(),
            relativePath = "../outside.png"
        )

        assertNull(resolved)
    }

    fun testRejectsSymlinkEscapeOutsideDocumentDirectory() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val outsideImage = tempRoot.resolve("outside.png")
        Files.write(outsideImage, byteArrayOf(9, 8, 7))
        val link = documentDirectory.resolve("linked.png")
        try {
            Files.createSymbolicLink(link, outsideImage)
        } catch (_: UnsupportedOperationException) {
            return
        }

        val resolved = MarkFlowWebviewResourceManager.resolveLocalResourcePath(
            documentDirectory = documentDirectory.toRealPath(),
            relativePath = "linked.png"
        )

        assertNull(resolved)
    }

    fun testLocalImageEndpointServesRelativeImageBytes() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val document = documentDirectory.resolve("readme.md")
        Files.writeString(document, "![local](./img/file.png)")
        val imageBytes = byteArrayOf(10, 20, 30, 40)
        val image = Files.createDirectories(documentDirectory.resolve("img")).resolve("file.png")
        Files.write(image, imageBytes)

        val registration = MarkFlowWebviewResourceManager.registerLocalDocument(document.toString())
        assertNotNull(registration)

        try {
            val request = HttpRequest.newBuilder(URI.create(registration!!.baseUrl + "img/file.png")).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray())

            assertEquals(200, response.statusCode())
            assertTrue(imageBytes.contentEquals(response.body()))
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("image/"))
        } finally {
            MarkFlowWebviewResourceManager.unregisterLocalDocument(registration?.token)
        }
    }

    fun testLocalImageEndpointDoesNotServeNonImageFiles() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val document = documentDirectory.resolve("readme.md")
        Files.writeString(document, "test")
        Files.writeString(documentDirectory.resolve("secret.txt"), "not an image")

        val registration = MarkFlowWebviewResourceManager.registerLocalDocument(document.toString())
        assertNotNull(registration)

        try {
            val request = HttpRequest.newBuilder(URI.create(registration!!.baseUrl + "secret.txt")).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())

            assertEquals(404, response.statusCode())
        } finally {
            MarkFlowWebviewResourceManager.unregisterLocalDocument(registration?.token)
        }
    }

    fun testBrowserResolvedParentPathCannotReuseDocumentToken() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val document = documentDirectory.resolve("readme.md")
        Files.writeString(document, "test")
        Files.write(tempRoot.resolve("outside.png"), byteArrayOf(1, 2, 3))

        val registration = MarkFlowWebviewResourceManager.registerLocalDocument(document.toString())
        assertNotNull(registration)

        try {
            val escapedUrl = URI.create(registration!!.baseUrl).resolve("../outside.png")
            assertFalse(escapedUrl.toString().contains("/${registration.token}/"))

            val request = HttpRequest.newBuilder(escapedUrl).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
            assertEquals(404, response.statusCode())
        } finally {
            MarkFlowWebviewResourceManager.unregisterLocalDocument(registration?.token)
        }
    }
}
