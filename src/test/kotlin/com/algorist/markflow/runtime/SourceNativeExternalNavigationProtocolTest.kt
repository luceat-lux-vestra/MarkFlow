package com.algorist.markflow.runtime

import com.algorist.markflow.sync.AttachmentId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SourceNativeExternalNavigationProtocolTest : BasePlatformTestCase() {

    fun testValidHttpAndHttpsRequestsDecodeExactlyWithoutUrlRewriting() {
        for (url in listOf(
            "https://example.com/path?q=hello%20world#frag",
            "http://example.com/%ED%95%9C%EA%B8%80?q=%E2%9C%93",
            "https://example.com/한글?q=✓#부분",
            "https://example.com/a(b)?q=x&y#z:1",
        )) {
            val signal = SourceNativeExternalNavigationProtocol.decode(
                "{\"type\":\"openExternal\",\"attachmentId\":\"attachment-a\",\"runtimeToken\":\"token-1\",\"url\":\"$url\"}",
            )
            assertEquals(AttachmentId.of("attachment-a"), signal?.attachmentId)
            assertEquals("token-1", signal?.runtimeToken)
            assertEquals(url, signal?.url)
            assertEquals(url, SourceNativeExternalNavigationProtocol.validateHttpUrl(url)?.toString())
        }
    }

    fun testDeniedSchemesAndPathFormsFailClosed() {
        val denied = listOf(
            "javascript:alert(1)",
            "vbscript:msgbox(1)",
            "%6a%61vascript%3Aalert(1)",
            "file:///etc/passwd",
            "data:text/html,hello",
            "blob:https://example.com/id",
            "//example.com/path",
            "/absolute/path",
            "../parent",
            "relative.md",
            "#fragment",
            "mailto:test@example.com",
            "ftp://example.com/file",
            "https://user@example.com/path",
            "https://user:pass@example.com/path",
            "https://[::1",
            "https ://example.com",
            "https://example.com/has space",
            "https://example.com/line\nfeed",
            "https://example.com/foo\\bar",
            "<https://example.com/path>",
        )
        for (url in denied) {
            assertNull("expected rejection for $url", SourceNativeExternalNavigationProtocol.validateHttpUrl(url))
        }
    }

    fun testMalformedIdentityShapeAndExtraFieldsAreRejected() {
        val invalid = listOf(
            "",
            "not-json",
            "null",
            "[]",
            "{}",
            "{\"type\":\"other\",\"attachmentId\":\"a\",\"runtimeToken\":\"t\",\"url\":\"https://example.com\"}",
            "{\"type\":\"openExternal\",\"attachmentId\":\"a\",\"runtimeToken\":\"t\",\"url\":\"https://example.com\",\"extra\":true}",
            "{\"type\":\"openExternal\",\"attachmentId\":\"\",\"runtimeToken\":\"t\",\"url\":\"https://example.com\"}",
            "{\"type\":\"openExternal\",\"attachmentId\":\"a\",\"runtimeToken\":\"\",\"url\":\"https://example.com\"}",
            "{\"type\":\"openExternal\",\"attachmentId\":\"a\",\"runtimeToken\":\"t\\u0000\",\"url\":\"https://example.com\"}",
            "{\"type\":\"openExternal\",\"attachmentId\":1,\"runtimeToken\":\"t\",\"url\":\"https://example.com\"}",
            "{\"type\":\"openExternal\",\"attachmentId\":\"a\",\"runtimeToken\":\"t\",\"url\":1}",
        )
        for (input in invalid) {
            assertNull("expected rejection for $input", SourceNativeExternalNavigationProtocol.decode(input))
        }
    }

    fun testProtocolBoundsAreExactAndOversizedPayloadsFailBeforeParsing() {
        val maxUrl = "https://example.com/" + "a".repeat(
            SourceNativeExternalNavigationProtocol.MAX_URL_LENGTH - "https://example.com/".length,
        )
        assertEquals(SourceNativeExternalNavigationProtocol.MAX_URL_LENGTH, maxUrl.length)
        assertNotNull(SourceNativeExternalNavigationProtocol.validateHttpUrl(maxUrl))
        assertNull(SourceNativeExternalNavigationProtocol.validateHttpUrl(maxUrl + "a"))

        val oversizedRaw = "x".repeat(SourceNativeExternalNavigationProtocol.MAX_MESSAGE_LENGTH + 1)
        assertNull(SourceNativeExternalNavigationProtocol.decode(oversizedRaw))
    }
}
