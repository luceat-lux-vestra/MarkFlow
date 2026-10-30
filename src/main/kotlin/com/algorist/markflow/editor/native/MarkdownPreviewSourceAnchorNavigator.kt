package com.algorist.markflow.editor.native

import com.intellij.openapi.application.ApplicationManager
import org.cef.browser.CefBrowser

/**
 * Test-only IDEA 2026.2.3 compatibility seam for Markdown Preview source anchors.
 *
 * The pinned bundled ScrollController caches currentScrollElement and may return
 * early after asynchronous layout shifts even when the cached element is no
 * longer in the viewport. Keep that platform-specific knowledge here so both
 * the controlled reference and the real bundled Preview acceptance path use
 * one current-DOM re-resolution rule.
 */
internal object MarkdownPreviewSourceAnchorNavigator {
    fun reResolveCurrentDom(browser: CefBrowser, sourceOffset: Int) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        require(sourceOffset >= 0) { "Invalid Markdown Preview source offset" }
        check(!browser.isClosing && !browser.isClosed) {
            "Markdown Preview source navigation outside active browser lifetime"
        }
        val javascript = """
            (() => {
              const controller = window.scrollController;
              if (!controller) return;
              controller.currentScrollElement = null;
              controller.scrollTo($sourceOffset, false);
            })();
        """.trimIndent()
        browser.executeJavaScript(javascript, browser.url, 0)
    }
}
