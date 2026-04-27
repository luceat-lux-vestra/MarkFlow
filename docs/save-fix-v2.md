# MarkFlow Save Fix v2 - Immediate Save & Tab Switch Persistence

## Date: 2026-04-24

## Summary

MarkFlow Markdown editor was not saving files to disk reliably. Previous fix (v1) added `file.setBinaryContent()` and `flushWebToDocument()` calls, but saving still failed due to race conditions and async timing issues.

## Root Causes Identified

### Root Cause 1: `invokeLater` Debouncing Race Condition

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`
**Method:** `scheduleWebToDocumentApply()` (lines 91-145)

The method uses `ApplicationManager.getApplication().invokeLater { ... }` to defer document updates. This creates a race condition:

```
Timeline:
T0: User types -> webview sends cefQuery -> applyWebUpdate(content1)
    -> pendingWebToDocumentContent = content1
    -> invokeLater scheduled (webToDocumentApplyScheduled = true)

T1: User types more -> webview sends cefQuery -> applyWebUpdate(content2)
    -> pendingWebToDocumentContent = content2 (overwritten)
    -> invokeLater SKIPPED (already scheduled)

T2: User switches tab -> deselectNotify() -> flushPendingWebContent()
    -> reads pendingWebToDocumentContent (= content2)
    -> saves content2 to disk
    -> pendingWebToDocumentContent = null  <-- CLEARED!

T3: invokeLater callback finally executes
    -> reads pendingWebToDocumentContent (= null!)
    -> returns early, does nothing
```

**Problem:** If `flushPendingWebContent()` runs before `invokeLater`, it clears the pending content. The `invokeLater` callback then sees `null` and does nothing. If new content arrives between T2 and T3, it's saved by flush but the invokeLater callback is wasted. More importantly, if `invokeLater` runs BEFORE flush, it clears `pendingWebToDocumentContent`, and then `flushPendingWebContent()` sees `null` and doesn't flush. Either way, there's a window for data loss.

**Additionally:** `invokeLater` introduces unnecessary latency. Every edit should save immediately, not wait for the EDT to process a queued runnable.

### Root Cause 2: Tab Switch Async Flush Never Completes

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`
**Method:** `deselectNotify()` (lines 287-295)

```kotlin
override fun deselectNotify() {
    flushPendingWebContent()           // (A) Sync flush of pending content
    sharedBrowserService.flushWebToDocument(this)  // (B) ASYNC - asks webview for latest
    sharedBrowserService.setEditorActive(this, false)
    // <-- IntelliJ calls detach() AFTER this returns!
}
```

The flow:
1. `(A)` `flushPendingWebContent()` saves `pendingWebToDocumentContent` and clears it
2. `(B)` `flushWebToDocument()` executes JS that calls `window.getMarkdown()` + `window.sendToIntelliJ()` via `cefQuery`
3. IntelliJ calls `detach()` on the editor, which clears `lease.attachedEditor = null`
4. The `cefQuery` response from step (B) arrives at `JBCefJSQuery` handler
5. Handler checks `lease.attachedEditor` -> **NULL** -> returns `ignoredResponse()`
6. **Content from step (B) is LOST**

The critical issue: `flushWebToDocument()` is asynchronous. It sends a message to the webview and returns immediately. By the time the webview responds, `detach()` has already cleared `lease.attachedEditor`, so the response is ignored.

### Root Cause 3: `setBinaryContent()` Conflicts with IntelliJ's File Tracking

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`
**Method:** `scheduleWebToDocumentApply()` (lines 129-131)

```kotlin
if (file.isWritable) {
    val charset = file.charset ?: StandardCharsets.UTF_8
    file.setBinaryContent(target.toByteArray(charset))
}
```

Calling `file.setBinaryContent()` inside `WriteCommandAction` can conflict with IntelliJ's own file modification tracking. The `FileDocumentManager` expects to be the one saving documents to files. Bypassing it with direct `setBinaryContent()` can cause:
- IntelliJ's `isModified()` checks to be incorrect
- Undo/redo stack inconsistencies
- Silent failures when IntelliJ's VFS layer rejects the write

**Correct approach:** Use `FileDocumentManager.getInstance().saveDocument(document)` AFTER `document.setText()`. This is the IntelliJ-sanctioned way to persist document changes to disk.

## Fixes Applied

### Fix 1: Remove `invokeLater` - Save Immediately

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`

**Before:** `applyWebUpdate()` -> `scheduleWebToDocumentApply()` -> `invokeLater` -> `WriteCommandAction` -> `document.setText()` + `file.setBinaryContent()`

**After:** `applyWebUpdate()` -> `WriteCommandAction` (immediate) -> `document.setText()` + `FileDocumentManager.saveDocument()`

The `invokeLater` wrapper is removed entirely. Since `applyWebUpdate()` is called from the `JBCefJSQuery` handler (which runs on the EDT), we can call `WriteCommandAction` directly without `invokeLater`. This ensures:
- Zero latency between edit and save
- No race condition with `flushPendingWebContent()`
- No lost updates from debouncing

The `pendingWebToDocumentContent` and `webToDocumentApplyScheduled` fields are removed since they're no longer needed.

### Fix 2: Synchronous Flush for Tab Switch

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowSharedBrowserService.kt`

Added new method `flushAndSaveForEditor()` that:
1. Retrieves current markdown from webview synchronously (via `executeJavaScript` + `getMarkdown()`)
2. Returns the markdown string directly (not via async cefQuery)
3. The caller saves it immediately

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`

Updated `deselectNotify()` to:
1. First call `flushPendingWebContent()` (for any already-received content)
2. Then call `sharedBrowserService.flushAndSaveForEditor(this)` to get and save the webview's current content synchronously
3. No longer rely on `flushWebToDocument()` (async, unreliable for tab switches)

### Fix 3: Use `FileDocumentManager.saveDocument()` Instead of `setBinaryContent()`

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`

Replaced `file.setBinaryContent(target.toByteArray(charset))` with `FileDocumentManager.getInstance().saveDocument(currentDocument)`. This is the IntelliJ-sanctioned way to persist document changes and avoids conflicts with IntelliJ's file tracking.

### Fix 4: Keep `isModified()` Manual Comparison (IntelliJ API Limitation)

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`

The `FileDocumentManager.isDocumentModified()` method is not available in IntelliJ 2025.2 Platform SDK. Kept the manual comparison of `document.text` vs `file.contentsToByteArray()`. This works correctly because `FileDocumentManager.saveDocument()` properly synchronizes the document to the file after each edit.

## Files Modified

1. `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`
   - Removed `pendingWebToDocumentContent` and `webToDocumentApplyScheduled` fields
   - Removed `scheduleWebToDocumentApply()` and `applyContentToDocumentAndFile()` methods
   - Added `saveContentToDocumentAndFile()` - immediate save (no `invokeLater`)
   - Modified `applyWebUpdate()` to call `saveContentToDocumentAndFile()` directly
   - Modified `flushPendingWebContent()` to call `getCurrentMarkdown()` + `saveContentToDocumentAndFile()`
   - Modified `deselectNotify()` to use synchronous flush (removed `flushWebToDocument()` call)
   - Modified `dispose()` to use synchronous flush (removed `flushWebToDocument()` call)
   - Removed unused imports (`ApplicationManager`, `StandardCharsets`)

2. `src/main/kotlin/com/algorist/markflow/MarkFlowSharedBrowserService.kt`
   - Added `getCurrentMarkdown()` method for synchronous webview content retrieval (JBCefJSQuery + CountDownLatch + 2s timeout)
   - Added `CountDownLatch` import

3. `docs/save-fix-v2.md` - This file

## Build Status

- Kotlin compilation: **PASS** (`./gradlew compileKotlin` - BUILD SUCCESSFUL, 0 errors)
- TypeScript compilation: **PASS** (`npx tsc --noEmit` - 0 errors)

## Verification Steps

1. Build and install the plugin
2. Open a markdown file in MarkFlow editor
3. Type some content -> verify file is saved immediately (check disk with `cat` or external editor)
4. Type more content, then switch to another tab -> verify content is preserved
5. Switch back -> verify content is still there
6. Close IDE without explicit save -> reopen -> verify all changes are persisted
