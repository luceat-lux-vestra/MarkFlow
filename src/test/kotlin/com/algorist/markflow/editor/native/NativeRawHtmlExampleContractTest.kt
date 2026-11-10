package com.algorist.markflow.editor.native

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class NativeRawHtmlExampleContractTest : BasePlatformTestCase() {
    fun testRepositoryManualExampleCoversAndClassifiesStaticInlineSubset() {
        val example = exampleSource()

        val safe = listOf(
            "<span>MF-SAFE-SPAN</span>",
            "<span class=\"highlight\" data-kind=\"safe\">MF-SAFE-ATTR-SPAN</span>",
            "<strong>MF-SAFE-STRONG</strong>",
            "<b>MF-SAFE-BOLD</b>",
            "<em>MF-SAFE-EMPHASIS</em>",
            "<i>MF-SAFE-ITALIC</i>",
            "<u>MF-SAFE-UNDERLINE</u>",
            "<s>MF-SAFE-STRIKE</s>",
            "<del>MF-SAFE-DELETE</del>",
            "<code>MF-SAFE-CODE</code>",
            "<kbd>MF-SAFE-KBD</kbd>",
            "<sub>2</sub>",
            "<sup>2</sup>",
            "<span><strong>MF-SAFE-NESTED</strong></span>",
            "<a>MF-SAFE-ANCHOR-TEXT-WITHOUT-HREF</a>",
            "<span>MF-SAFE-BR-BEFORE<br/>MF-SAFE-BR-AFTER</span>",
        )

        safe.forEach { fragment ->
            assertTrue("manual example lost safe fragment: $fragment", example.contains(fragment))
            assertTrue(
                "manual example safe fragment is not accepted by production sanitizer: $fragment",
                NativeRawHtmlSanitizer.sanitize(fragment) is NativeRawHtmlSanitizationResult.Safe,
            )
        }

        val attributed = NativeRawHtmlSanitizer.sanitize(
            "<span class=\"highlight\" data-kind=\"safe\">MF-SAFE-ATTR-SPAN</span>"
        )
        assertTrue(attributed is NativeRawHtmlSanitizationResult.Safe)
        val html = (attributed as NativeRawHtmlSanitizationResult.Safe).html
        assertTrue(html.contains("MF-SAFE-ATTR-SPAN"))
        assertFalse(html.contains("class="))
        assertFalse(html.contains("data-kind="))
    }

    fun testRepositoryManualExampleCoversCapabilityBearingFailClosedCases() {
        val example = exampleSource()
        val blocked = listOf(
            "<a href=\"https://example.com\" target=\"_blank\">MF-BLOCKED-HREF</a>",
            "<img src=\"https://example.com/markflow.png\" alt=\"MF-BLOCKED-REMOTE-IMAGE\">",
            "<div style=\"color:red\">MF-BLOCKED-STYLE</div>",
            "<div onclick=\"alert('markflow')\">MF-BLOCKED-EVENT-HANDLER</div>",
            "<a href=\"https://example.com/list\">MF-BLOCKED-LIST-HREF</a>",
        )

        blocked.forEach { fragment ->
            assertTrue("manual example lost blocked fragment: $fragment", example.contains(fragment))
            assertTrue(
                "capability-bearing raw HTML must fail closed: $fragment",
                NativeRawHtmlSanitizer.sanitize(fragment) is NativeRawHtmlSanitizationResult.Blocked,
            )
        }
    }

    fun testRepositoryManualExampleCoversUnsupportedBrowserSemantics() {
        val example = exampleSource()
        val unsupported = listOf(
            """<details open>
<summary>MF-UNSUPPORTED-SUMMARY</summary>
<p>MF-UNSUPPORTED-DETAILS-BODY</p>
</details>""",
            """<section aria-label="demo section">
  <h2>MF-UNSUPPORTED-SECTION-TITLE</h2>
  <p>MF-UNSUPPORTED-SECTION-BODY</p>
</section>""",
            "<mark>MF-UNSUPPORTED-MARK</mark>",
        )

        unsupported.forEach { fragment ->
            assertTrue("manual example lost unsupported fragment: $fragment", example.contains(fragment))
            assertTrue(
                "unsupported browser semantics must remain exact-source fallback: $fragment",
                NativeRawHtmlSanitizer.sanitize(fragment) is NativeRawHtmlSanitizationResult.Blocked,
            )
        }
    }

    fun testStandaloneLineBreakStaysExactSourceWhileBalancedFragmentOwnsNestedBreak() {
        val standalone = plan("MF-BR-BEFORE<br/>MF-BR-AFTER\n")
        assertTrue(NativeRawHtmlProjectionPlanner.plan(standalone).isEmpty())

        val nestedSource = "before <span>MF-SAFE-BR-BEFORE<br/>MF-SAFE-BR-AFTER</span> after\n"
        val nested = NativeRawHtmlProjectionPlanner.plan(plan(nestedSource))

        assertEquals(1, nested.size)
        assertEquals(NativeRawHtmlProjectionKind.INLINE, nested.single().kind)
        assertEquals(
            "<span>MF-SAFE-BR-BEFORE<br/>MF-SAFE-BR-AFTER</span>",
            nested.single().source,
        )
        assertTrue(
            NativeRawHtmlSanitizer.sanitize(nested.single().source) is NativeRawHtmlSanitizationResult.Safe
        )
    }

    private fun plan(source: String): NativeProjectionPlan =
        NativeMarkdownProjectionPlanner.plan(
            ProjectionSnapshot(
                ProjectionSourceIdentity(
                    modificationStamp = 23L,
                    source = source,
                    configGeneration = 5L,
                )
            )
        ).also { assertEquals(ProjectionPlanStatus.READY, it.status) }

    private fun exampleSource(): String = Files.readString(
        Path.of("examples", "raw-html-support.md").toAbsolutePath().normalize()
    )
}
