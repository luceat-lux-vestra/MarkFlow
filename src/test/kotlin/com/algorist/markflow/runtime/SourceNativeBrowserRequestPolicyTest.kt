package com.algorist.markflow.runtime

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.cef.network.CefRequest

class SourceNativeBrowserRequestPolicyTest : BasePlatformTestCase() {
    private val entry =
        "http://127.0.0.1:43123/source-native.html?attachmentId=attachment-1&runtimeToken=runtime-1"

    fun testInitialMainNavigationIsExactAndSingleUse() {
        val policy = policy()

        assertTrue(policy.allowInitialMainNavigation(entry, "GET", isRedirect = false))
        assertFalse(policy.allowInitialMainNavigation(entry, "GET", isRedirect = false))
        assertFalse(policy().allowInitialMainNavigation(entry, "POST", isRedirect = false))
        assertFalse(policy().allowInitialMainNavigation(entry, "GET", isRedirect = true))
        assertFalse(
            policy().allowInitialMainNavigation(
                "http://127.0.0.1:43124/source-native.html?attachmentId=attachment-1&runtimeToken=runtime-1",
                "GET",
                isRedirect = false,
            ),
        )
        assertFalse(
            policy().allowInitialMainNavigation(
                "http://example.com/source-native.html?attachmentId=attachment-1&runtimeToken=runtime-1",
                "GET",
                isRedirect = false,
            ),
        )
        assertFalse(
            policy().allowInitialMainNavigation(
                "https://example.com/source-native.html?attachmentId=attachment-1&runtimeToken=runtime-1",
                "GET",
                isRedirect = false,
            ),
        )
    }

    fun testOnlyDedicatedCanonicalStaticNamespaceAndBrowserResourceClassesAreAllowed() {
        val policy = policy()
        val script = "http://127.0.0.1:43123/source-native-assets/sourceNative.js"

        assertTrue(
            policy.allowResourceRequest(
                script,
                "GET",
                CefRequest.ResourceType.RT_SCRIPT,
                isNavigation = false,
                isDownload = false,
            ),
        )
        assertTrue(
            policy.allowResourceRequest(
                "http://127.0.0.1:43123/source-native-assets/style.css",
                "HEAD",
                CefRequest.ResourceType.RT_STYLESHEET,
                isNavigation = false,
                isDownload = false,
            ),
        )

        assertDenied(script, resourceType = CefRequest.ResourceType.RT_XHR)
        assertDenied(script, resourceType = CefRequest.ResourceType.RT_SUB_RESOURCE)
        assertDenied(script, resourceType = CefRequest.ResourceType.RT_PREFETCH)
        assertDenied(script, resourceType = CefRequest.ResourceType.RT_IMAGE)
        assertDenied("http://127.0.0.1:43123/assets/bootstrap.js")
        assertDenied("http://127.0.0.1:43123/index.html")
        assertDenied("http://127.0.0.1:43123/source-native-assets/../assets/bootstrap.js")
        assertDenied("http://127.0.0.1:43123/source-native-assets/sourceNative.js?probe=1")
        assertDenied("http://127.0.0.1:43123/source%2Dnative-assets/sourceNative.js")
    }

    fun testLocalImagesRemainBoundToExactTargetCapabilityNamespaceAndImageType() {
        val token = "A".repeat(43)
        val image = "http://127.0.0.1:43123/__markflow_source_image__/$token/diagram.png"
        val policy = policy()

        assertTrue(
            policy.allowResourceRequest(
                image,
                "GET",
                CefRequest.ResourceType.RT_IMAGE,
                isNavigation = false,
                isDownload = false,
            ),
        )
        assertDenied(image, resourceType = CefRequest.ResourceType.RT_SCRIPT)
        assertDenied("$image?probe=1", resourceType = CefRequest.ResourceType.RT_IMAGE)
        assertDenied("http://127.0.0.1:43123/__markflow_local__/legacy/image.png", resourceType = CefRequest.ResourceType.RT_IMAGE)
        assertDenied(
            "http://127.0.0.1:43123/__markflow_source_image__/${"B".repeat(42)}/diagram.png",
            resourceType = CefRequest.ResourceType.RT_IMAGE,
        )
        assertDenied(
            "http://127.0.0.1:43123/__markflow_source_image__/${"B".repeat(44)}/diagram.png",
            resourceType = CefRequest.ResourceType.RT_IMAGE,
        )
    }

    fun testWrongOriginSchemesMethodsDownloadsAndReplacementNavigationFailClosed() {
        assertDenied("http://127.0.0.1:43124/source-native-assets/sourceNative.js")
        assertDenied("http://example.com/source-native-assets/sourceNative.js")
        assertDenied("https://example.com/source-native-assets/sourceNative.js")
        assertDenied("//example.com/source-native-assets/sourceNative.js")
        assertDenied("file:///tmp/sourceNative.js")
        assertDenied("data:text/javascript,alert(1)")
        assertDenied("blob:http://127.0.0.1:43123/id")
        assertDenied("custom:payload")
        assertDenied(
            "http://127.0.0.1:43123/source-native-assets/sourceNative.js",
            method = "POST",
        )
        assertDenied(
            "http://127.0.0.1:43123/source-native-assets/sourceNative.js",
            isDownload = true,
        )

        val policy = policy()
        assertFalse(
            policy.allowResourceRequest(
                "http://127.0.0.1:43123/source-native.html?attachmentId=other&runtimeToken=other",
                "GET",
                CefRequest.ResourceType.RT_MAIN_FRAME,
                isNavigation = true,
                isDownload = false,
            ),
        )
        assertFalse(
            policy.allowResourceRequest(
                entry,
                "HEAD",
                CefRequest.ResourceType.RT_MAIN_FRAME,
                isNavigation = true,
                isDownload = false,
            ),
        )
        assertTrue(
            policy.allowResourceRequest(
                entry,
                "GET",
                CefRequest.ResourceType.RT_MAIN_FRAME,
                isNavigation = true,
                isDownload = false,
            ),
        )
        assertFalse(
            policy.allowResourceRequest(
                entry,
                "GET",
                CefRequest.ResourceType.RT_MAIN_FRAME,
                isNavigation = true,
                isDownload = false,
            ),
        )
    }

    fun testMalformedOrNonProductionEntryUrlsCannotCreatePolicy() {
        assertNull(SourceNativeBrowserRequestPolicy.fromEntryUrl("http://source-native/source-native.html?a=b"))
        assertNull(SourceNativeBrowserRequestPolicy.fromEntryUrl("http://127.0.0.1/source-native.html?a=b"))
        assertNull(SourceNativeBrowserRequestPolicy.fromEntryUrl("http://127.0.0.1:43123/index.html?a=b"))
        assertNull(SourceNativeBrowserRequestPolicy.fromEntryUrl("http://127.0.0.1:43123/source-native.html"))
        assertNull(SourceNativeBrowserRequestPolicy.fromEntryUrl("//127.0.0.1:43123/source-native.html?a=b"))
        assertNull(SourceNativeBrowserRequestPolicy.fromEntryUrl("data:text/html,source-native"))
        assertNull(SourceNativeBrowserRequestPolicy.fromEntryUrl("not a url"))
    }

    private fun assertDenied(
        url: String,
        method: String = "GET",
        resourceType: CefRequest.ResourceType = CefRequest.ResourceType.RT_SCRIPT,
        isDownload: Boolean = false,
    ) {
        assertFalse(
            policy().allowResourceRequest(
                url,
                method,
                resourceType,
                isNavigation = false,
                isDownload = isDownload,
            ),
        )
    }

    private fun policy(): SourceNativeBrowserRequestPolicy =
        SourceNativeBrowserRequestPolicy.fromEntryUrl(entry)!!
}
