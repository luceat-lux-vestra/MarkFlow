package com.algorist.markflow.browser

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class SourceNativeLocalImagePayloadLimitTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private val client = HttpClient.newHttpClient()

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("markflow-source-native-image-limit-")
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

    fun testSizePredicateIncludesExactLimitAndRejectsOutsideRange() {
        val limit = MarkFlowWebviewResourceManager.SOURCE_NATIVE_LOCAL_IMAGE_MAX_BYTES
        assertFalse(MarkFlowWebviewResourceManager.isAllowedSourceNativeLocalImageSize(-1))
        assertTrue(MarkFlowWebviewResourceManager.isAllowedSourceNativeLocalImageSize(limit - 1))
        assertTrue(MarkFlowWebviewResourceManager.isAllowedSourceNativeLocalImageSize(limit))
        assertFalse(MarkFlowWebviewResourceManager.isAllowedSourceNativeLocalImageSize(limit + 1))
    }

    fun testGetAndHeadEnforceSameBelowEqualAndAboveLimitPolicy() {
        val documentDirectory = Files.createDirectories(tempRoot.resolve("docs"))
        val document = Files.writeString(documentDirectory.resolve("readme.md"), "# test")
        val limit = MarkFlowWebviewResourceManager.SOURCE_NATIVE_LOCAL_IMAGE_MAX_BYTES

        Files.write(documentDirectory.resolve("below.png"), byteArrayOf(1, 2, 3, 4))
        createSparseFile(documentDirectory.resolve("equal.png"), limit)
        createSparseFile(documentDirectory.resolve("above.png"), limit + 1)

        val registration = MarkFlowWebviewResourceManager.registerSourceNativeLocalImage(document.toString())!!
        try {
            val belowGet = get(registration.baseUrl + "below.png", HttpResponse.BodyHandlers.discarding())
            assertEquals(200, belowGet.statusCode())
            assertEquals("4", belowGet.headers().firstValue("Content-Length").orElse(""))
            val belowHead = head(registration.baseUrl + "below.png")
            assertEquals(200, belowHead.statusCode())
            assertEquals("4", belowHead.headers().firstValue("Content-Length").orElse(""))

            val equalGet = get(registration.baseUrl + "equal.png", HttpResponse.BodyHandlers.discarding())
            assertEquals(200, equalGet.statusCode())
            assertEquals(limit.toString(), equalGet.headers().firstValue("Content-Length").orElse(""))
            val equalHead = head(registration.baseUrl + "equal.png")
            assertEquals(200, equalHead.statusCode())
            assertEquals(limit.toString(), equalHead.headers().firstValue("Content-Length").orElse(""))

            val aboveGet = get(registration.baseUrl + "above.png", HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(413, aboveGet.statusCode())
            assertEquals(0, aboveGet.body().size)
            val aboveHead = head(registration.baseUrl + "above.png", HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(413, aboveHead.statusCode())
            assertEquals(0, aboveHead.body().size)
        } finally {
            MarkFlowWebviewResourceManager.unregisterSourceNativeLocalImage(registration.token)
        }
    }

    private fun createSparseFile(path: Path, size: Long) {
        RandomAccessFile(path.toFile(), "rw").use { file ->
            file.setLength(size)
        }
    }

    private fun <T> get(url: String, bodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        return client.send(request, bodyHandler)
    }

    private fun head(url: String): HttpResponse<Void> = head(url, HttpResponse.BodyHandlers.discarding())

    private fun <T> head(url: String, bodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(request, bodyHandler)
    }
}
