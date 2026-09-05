package com.algorist.markflow.runtime

import com.algorist.markflow.sync.AttachmentId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Unit tests for the strict, narrow [RuntimeReadinessCodec]. */
class RuntimeReadinessProtocolTest : BasePlatformTestCase() {

    fun testValidSignalDecodesExactly() {
        val signal = RuntimeReadinessCodec.decode(
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"attachment-a\",\"runtimeToken\":\"token-1\"}",
        )
        assertEquals(AttachmentId.of("attachment-a"), signal?.attachmentId)
        assertEquals("token-1", signal?.runtimeToken)
    }

    fun testMalformedShapesAreRejected() {
        val invalid = listOf(
            "",
            "not json",
            "null",
            "[]",
            "{}",
            "{\"type\":\"runtimeReady\"}",
            "{\"type\":\"otherType\",\"attachmentId\":\"a\",\"runtimeToken\":\"t\"}",
            // extra field
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"a\",\"runtimeToken\":\"t\",\"extra\":true}",
            // missing field
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"a\"}",
            // blank token
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"a\",\"runtimeToken\":\"\"}",
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"a\",\"runtimeToken\":\"   \"}",
            // control character in token
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"a\",\"runtimeToken\":\"t\\u0000\"}",
            // oversized token
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"a\",\"runtimeToken\":\"${"x".repeat(129)}\"}",
            // non-string attachmentId
            "{\"type\":\"runtimeReady\",\"attachmentId\":1,\"runtimeToken\":\"t\"}",
            // invalid attachmentId per AttachmentId rules (blank)
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"\",\"runtimeToken\":\"t\"}",
        )
        for (input in invalid) {
            assertNull("expected rejection for: $input", RuntimeReadinessCodec.decode(input))
        }
    }

    fun testMaximumLengthTokenIsAccepted() {
        val token = "x".repeat(128)
        val signal = RuntimeReadinessCodec.decode(
            "{\"type\":\"runtimeReady\",\"attachmentId\":\"a\",\"runtimeToken\":\"$token\"}",
        )
        assertEquals(token, signal?.runtimeToken)
    }
}
