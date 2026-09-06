package com.algorist.markflow.editor;

import com.intellij.openapi.editor.Document;

/**
 * Preserves IntelliJ's whole-document replacement semantics across the Kotlin/Java interop boundary.
 *
 * <p>{@link Document#setText(CharSequence)} marks the change as a whole-text replacement. Kotlin cannot
 * express this call as synthetic-property assignment because {@code getText()} returns {@link String}
 * while {@code setText(...)} accepts {@link CharSequence}, so the synthetic {@code text} property is
 * read-only. Keep the exact platform API call here rather than replacing it with range replacement,
 * which has different document-event semantics.</p>
 */
final class DocumentWholeTextReplacement {
    private DocumentWholeTextReplacement() {
    }

    static void apply(Document document, CharSequence text) {
        document.setText(text);
    }
}
