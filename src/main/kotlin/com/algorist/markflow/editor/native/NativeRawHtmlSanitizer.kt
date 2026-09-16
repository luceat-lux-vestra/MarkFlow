package com.algorist.markflow.editor.native

import java.io.StringReader
import java.util.Locale
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator

internal sealed interface NativeRawHtmlSanitizationResult {
    data class Safe(val html: String) : NativeRawHtmlSanitizationResult
    data class Blocked(val code: String) : NativeRawHtmlSanitizationResult
}

/**
 * Fail-closed sanitizer for #149 derived raw-HTML previews.
 *
 * The authoritative Markdown source is never passed back from this component and is never mutated.
 * Preview input is bounded, active/capability-bearing elements or attributes reject the complete
 * preview, and accepted output contains only a small static formatting/table allowlist with all
 * source attributes stripped. In particular there is no resource URL, navigation, event, style,
 * form, script, embedded content, SVG or canvas capability in the output.
 *
 * ParserDelegator catches RuntimeException thrown from ParserCallback methods internally. Therefore
 * security rejection is latched in [SanitizerState] instead of using exceptions for callback control
 * flow; once blocked, every later parser callback is ignored and the final result is always Blocked.
 */
internal object NativeRawHtmlSanitizer {
    fun sanitize(source: String): NativeRawHtmlSanitizationResult {
        if (source.length > MAX_SOURCE_CHARS) {
            return NativeRawHtmlSanitizationResult.Blocked("SOURCE_TOO_LARGE")
        }
        if (source.isBlank()) {
            return NativeRawHtmlSanitizationResult.Blocked("EMPTY_SOURCE")
        }

        val state = SanitizerState()
        return try {
            ParserDelegator().parse(
                StringReader(source),
                SanitizingCallback(state),
                true,
            )
            state.blockedCode?.let { code ->
                return NativeRawHtmlSanitizationResult.Blocked(code)
            }
            val html = state.output.toString().trim()
            if (html.isEmpty()) {
                NativeRawHtmlSanitizationResult.Blocked("EMPTY_PREVIEW")
            } else {
                NativeRawHtmlSanitizationResult.Safe(html)
            }
        } catch (_: Exception) {
            NativeRawHtmlSanitizationResult.Blocked("PARSE_FAILED")
        }
    }

    private class SanitizingCallback(
        private val state: SanitizerState,
    ) : HTMLEditorKit.ParserCallback() {
        override fun handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) {
            if (!state.bumpNode()) return
            val name = tag.toString().lowercase(Locale.ROOT)
            if (name in STRUCTURAL_WRAPPERS) return
            if (!state.requireSafeTag(name)) return
            if (!state.requireSafeAttributes(attributes)) return
            state.append("<$name>")
        }

        override fun handleEndTag(tag: HTML.Tag, position: Int) {
            if (!state.bumpNode()) return
            val name = tag.toString().lowercase(Locale.ROOT)
            if (name in STRUCTURAL_WRAPPERS) return
            if (!state.requireSafeTag(name)) return
            if (name !in VOID_SAFE_TAGS) state.append("</$name>")
        }

        override fun handleSimpleTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) {
            if (!state.bumpNode()) return
            val name = tag.toString().lowercase(Locale.ROOT)
            if (name in STRUCTURAL_WRAPPERS) return
            if (!state.requireSafeTag(name)) return
            if (!state.requireSafeAttributes(attributes)) return
            state.append("<$name>")
        }

        override fun handleText(data: CharArray, position: Int) {
            if (!state.bumpNode()) return
            state.appendEscapedText(data.concatToString())
        }

        override fun handleComment(data: CharArray, position: Int) {
            state.bumpNode()
            // Comments carry no presentation value and are deliberately absent from the inert preview.
        }
    }

    private class SanitizerState {
        val output = StringBuilder()
        var blockedCode: String? = null
            private set
        private var nodes = 0

        fun bumpNode(): Boolean {
            if (blockedCode != null) return false
            nodes += 1
            if (nodes > MAX_NODES) block("NODE_LIMIT")
            return blockedCode == null
        }

        fun requireSafeTag(name: String): Boolean {
            if (name in BLOCKED_TAGS) block("ACTIVE_TAG")
            else if (name !in ALLOWED_TAGS) block("UNSUPPORTED_TAG")
            return blockedCode == null
        }

        fun requireSafeAttributes(attributes: MutableAttributeSet): Boolean {
            if (blockedCode != null) return false
            val names = attributes.attributeNames
            while (names.hasMoreElements()) {
                val key = names.nextElement()
                if (key == HTMLEditorKit.ParserCallback.IMPLIED) continue
                val name = key.toString().lowercase(Locale.ROOT)
                if (name.startsWith("on") || name in BLOCKED_ATTRIBUTES) {
                    block("ACTIVE_ATTRIBUTE")
                    return false
                }
                // Static attributes are intentionally stripped. Their source remains authoritative;
                // preview fidelity must not expand the capability surface just to retain decoration.
            }
            return true
        }

        fun append(value: String) {
            if (blockedCode != null) return
            if (output.length + value.length > MAX_OUTPUT_CHARS) {
                block("OUTPUT_LIMIT")
                return
            }
            output.append(value)
        }

        fun appendEscapedText(value: String) {
            if (blockedCode != null) return
            buildString(value.length) {
                value.forEach { character ->
                    when (character) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        else -> append(character)
                    }
                }
            }.let(::append)
        }

        private fun block(code: String) {
            if (blockedCode == null) blockedCode = code
        }
    }

    private val STRUCTURAL_WRAPPERS = setOf("html", "head", "body")

    private val ALLOWED_TAGS = setOf(
        "p", "div", "span", "br", "hr",
        "strong", "b", "em", "i", "u", "s", "strike", "del",
        "code", "pre", "blockquote", "ul", "ol", "li",
        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "sub", "sup", "kbd", "a",
    )

    private val VOID_SAFE_TAGS = setOf("br", "hr")

    private val BLOCKED_TAGS = setOf(
        "script", "style", "iframe", "frame", "frameset",
        "object", "embed", "applet", "img", "picture", "source", "track",
        "audio", "video", "link", "meta", "base",
        "form", "input", "button", "select", "option", "textarea", "label",
        "canvas", "svg", "math", "template", "noscript",
    )

    private val BLOCKED_ATTRIBUTES = setOf(
        "style", "href", "src", "srcset", "action", "formaction", "poster", "background",
        "ping", "cite", "data", "codebase", "archive", "usemap", "longdesc", "profile",
        "xlink:href", "xmlns", "http-equiv",
    )

    internal const val MAX_SOURCE_CHARS = 64 * 1024
    internal const val MAX_OUTPUT_CHARS = 64 * 1024
    private const val MAX_NODES = 4096
}
