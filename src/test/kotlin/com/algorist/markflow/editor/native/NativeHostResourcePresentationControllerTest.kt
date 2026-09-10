package com.algorist.markflow.editor.native

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativeHostResourcePresentationControllerTest : BasePlatformTestCase() {
    fun testExternalNavigationRequiresExplicitGestureAndCurrentAuthorizedLink() {
        val source = "[allowed](https://example.com/docs) and [blocked](javascript:alert(1))\n"
        myFixture.configureByText("navigation.md", source)
        val opened = mutableListOf<String>()
        val host = NativeHostResourcePresentationController(
            editor = myFixture.editor,
            documentPathProvider = { null },
            externalNavigator = { uri -> opened += uri.toString() },
            richPresentationEnabled = { false },
        )
        val controller = NativePresentationController(
            editor = myFixture.editor,
            hostResources = host,
        )
        try {
            val sourceBefore = myFixture.editor.document.text
            val stampBefore = myFixture.editor.document.modificationStamp
            val allowedOffset = source.indexOf("allowed") + 2
            val blockedOffset = source.indexOf("blocked") + 2

            assertFalse(host.activateExternalLinkAt(allowedOffset, explicitUserGesture = false))
            assertTrue(host.activateExternalLinkAt(allowedOffset, explicitUserGesture = true))
            assertFalse(host.activateExternalLinkAt(blockedOffset, explicitUserGesture = true))
            assertEquals(listOf("https://example.com/docs"), opened)

            val evidence = host.evidenceSnapshot()
            assertEquals(1, evidence.externalLinks)
            assertEquals(1L, evidence.navigationAccepted)
            assertEquals(2L, evidence.navigationRejected)
            assertEquals(sourceBefore, myFixture.editor.document.text)
            assertEquals(stampBefore, myFixture.editor.document.modificationStamp)
        } finally {
            Disposer.dispose(controller)
        }
    }

    fun testStaleDocumentIdentityRejectsPreviouslyPlannedNavigation() {
        val source = "[allowed](https://example.com/docs)\n"
        myFixture.configureByText("stale-navigation.md", source)
        val opened = mutableListOf<String>()
        val host = NativeHostResourcePresentationController(
            editor = myFixture.editor,
            documentPathProvider = { null },
            externalNavigator = { uri -> opened += uri.toString() },
            richPresentationEnabled = { false },
        )
        val controller = NativePresentationController(
            editor = myFixture.editor,
            hostResources = host,
        )
        try {
            val allowedOffset = source.indexOf("allowed") + 2
            WriteCommandAction.writeCommandAction(project)
                .withName("MarkFlow #147 stale navigation proof")
                .run<RuntimeException> {
                    myFixture.editor.document.insertString(myFixture.editor.document.textLength, "fresh")
                }

            assertFalse(host.activateExternalLinkAt(allowedOffset, explicitUserGesture = true))
            assertTrue(opened.isEmpty())
            assertEquals(1L, host.evidenceSnapshot().navigationRejected)
        } finally {
            Disposer.dispose(controller)
        }
    }

    fun testAccessibilityFallbackKeepsLocalImageSourceVisible() {
        val source = "before\n\n![alt](images/example.png)\n\nafter\n"
        myFixture.configureByText("accessibility-image.md", source)
        val host = NativeHostResourcePresentationController(
            editor = myFixture.editor,
            documentPathProvider = { error("image resolver must not run in accessibility fallback") },
            richPresentationEnabled = { false },
        )
        val controller = NativePresentationController(
            editor = myFixture.editor,
            hostResources = host,
        )
        try {
            val evidence = host.evidenceSnapshot()
            assertEquals(1, evidence.localImages)
            assertEquals(1L, evidence.accessibilityFallbacks)
            assertEquals(0, evidence.pendingImageLoads)
            assertEquals(0, evidence.ownedImageInlays)
            assertEquals(0, evidence.ownedImageFolds)
            assertEquals(source, myFixture.editor.document.text)
        } finally {
            Disposer.dispose(controller)
        }
    }

    fun testLocalImageSizingNeverUpscalesAndBoundsLargeHeight() {
        assertEquals(800 to 400, calculateNativeLocalImageDimensions(800, 400, 1200))
        assertEquals(400 to 200, calculateNativeLocalImageDimensions(800, 400, 400))
        assertEquals(102 to 1024, calculateNativeLocalImageDimensions(1000, 10_000, 2000))
    }
}
