# MarkFlow Save Failure - Root Cause Analysis & Fix

## Summary

MarkFlow Markdown editor does not save files to disk. Changes made in the webview (Milkdown/Crepe) are reflected in the IntelliJ `Document` object in memory, but are never explicitly committed to the backing `VirtualFile` on disk.

## Root Cause

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`

**Method:** `scheduleWebToDocumentApply()` (lines 90-136)

The method applies incoming markdown content from the webview to the IntelliJ `Document` via `document.setText(target)` inside a `WriteCommandAction.runWriteCommandAction`. However, **there is no call to commit the document to disk** after the document is modified.

The plugin relies entirely on IntelliJ's auto-save mechanism to persist changes to disk. This is unreliable because:
- Auto-save may be disabled in the IDE
- Auto-save has a delay, creating a window for data loss
- If the IDE crashes or the editor is closed, unsaved changes are lost

## Save Flow Architecture

```
User types in webview (Crepe)
    |
    v
Crepe markdownUpdated listener fires (webview/src/main.ts:1266)
    |
    v
sendToIntelliJ(content, uiState) via window.cefQuery (webview/src/main.ts:1089)
    |
    v
JBCefJSQuery handler receives "update" action (MarkFlowSharedBrowserService.kt:353)
    |
    v
MarkFlowEditor.applyWebUpdate(content, ...) (MarkFlowEditor.kt:75)
    |
    v
scheduleWebToDocumentApply(content) - debounced invokeLater (MarkFlowEditor.kt:90)
    |
    v
WriteCommandAction.runWriteCommandAction { document.setText(content) }
    |
    v
IntelliJ Document modified in memory ONLY <-- NO DISK WRITE
    |
    v
IntelliJ auto-save (NOT triggered by plugin) eventually writes to disk
    |
    v
VirtualFile updated on disk (maybe, maybe not - unreliable)
```

## Fix Applied

### 1. Add explicit document commit after setText

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`

In `scheduleWebToDocumentApply()`, after `currentDocument.setText(target)`, added:

```kotlin
currentDocument.setText(target)
FileDocumentManager.getInstance().commitDocument(currentDocument)
```

`FileDocumentManager` was already imported at line 10. The `commitDocument()` method synchronizes the `Document` changes to the backing `VirtualFile` immediately, ensuring changes are written to disk.

### 2. Flush pending changes on dispose

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`

In the `dispose()` method (line 246), added a call to `sharedBrowserService.flushWebToDocument(this)` before cleanup to ensure any pending webview changes are flushed to the document:

```kotlin
override fun dispose() {
    if (disposed) return
    sharedBrowserService.flushWebToDocument(this)  // <-- ADDED
    disposed = true
    // ... rest of existing cleanup
}
```

This addresses the gap where the editor is disposed while the webview has unsaved changes (e.g., user closes the file tab).

## TypeScript Type Error Fix

### Root Cause

**File:** `webview/src/vite-env.d.ts`

**Errors:**
```
src/main.ts(1415,12): error TS2339: Property 'getMarkdown' does not exist on type 'Window & typeof globalThis'.
src/main.ts(1420,12): error TS2339: Property 'sendToIntelliJ' does not exist on type 'Window & typeof globalThis'.
```

`main.ts:1415` assigns `window.getMarkdown = () => {...}` and `main.ts:1420` assigns `window.sendToIntelliJ = (markdownText, uiState) => {...}`, but the `Window` interface in `vite-env.d.ts` was missing type declarations for these properties.

### Fix Applied

Added the missing type declarations to `webview/src/vite-env.d.ts`:

```typescript
type EditorUiState = {
    version: number;
    scrollTop: number;
    cursorOffset: number;
    selectionStart: number;
    selectionEnd: number;
};

interface Window {
    // ... existing properties ...
    getMarkdown?: () => string;
    sendToIntelliJ?: (markdownText: string, uiState: EditorUiState) => void;
}
```

The `EditorUiState` type was also moved from `main.ts` (line 812) to `vite-env.d.ts` so that it can be referenced in the `Window` interface.

## Additional Observations

### No explicit save action needed

The plugin uses a bidirectional sync model where the webview pushes content to IntelliJ and vice versa. There is no explicit "save" button - saving is implicit. The fix above ensures the implicit save actually persists to disk.

### `isModified()` pull-based check

The `isModified()` method (line 189) compares `document.text` against `file.contentsToByteArray()` on every call. After the fix, this check will remain accurate since the document and file will stay in sync.

### `deselectNotify()` flush

When the user switches away from the MarkFlow editor, `deselectNotify()` already calls `flushWebToDocument()` which forces the webview to send its current content. With the fix, this will now persist to disk immediately.

## Files Modified

1. `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`
   - Added `commitDocument()` call in `scheduleWebToDocumentApply()` (line 127)
   - Added `flushWebToDocument()` call in `dispose()` (line 248)
2. `webview/src/vite-env.d.ts`
   - Added `EditorUiState` type definition (moved from main.ts:812)
   - Added `getMarkdown?: () => string` to Window interface
   - Added `sendToIntelliJ?: (markdownText: string, uiState: EditorUiState) => void` to Window interface
3. `webview/src/main.ts`
   - Removed duplicate `EditorUiState` type definition (line 812) to avoid redeclaration
4. `docs/save-fix-analysis.md` - This file (created with fix details)

## Verification

After applying the fix:
1. Build and install the plugin
2. Open a markdown file in MarkFlow editor
3. Type some content in the webview
4. Close the file without explicit save
5. Reopen the file - content should be preserved on disk

## Build Status

- Kotlin compilation: **PASS** (`./gradlew compileKotlin` - BUILD SUCCESSFUL)
- TypeScript compilation: **PASS** (`npx tsc --noEmit` - no errors)
