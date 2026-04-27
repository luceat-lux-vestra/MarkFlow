# MarkFlow Save Fix v3 - Caching-Based Flush & JSON/Stringify Bug Fix

## Date: 2026-04-25

## Summary

MarkFlow Markdown editor was not saving files to disk in ANY situation (real-time typing, tab switch, IDE close). Previous fixes (v1: `commitDocument()`, v2: `CountDownLatch` + `saveDocument()`) both failed. Three additional root causes were identified and fixed using a caching-based approach.

## Root Causes Identified

### Root Cause 1: `JSON.stringify()` Bug in `getCurrentMarkdown()` (CRITICAL)

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowSharedBrowserService.kt`
**Line:** 300

```kotlin
// BEFORE (buggy):
window.__markflowFlushQuery(JSON.stringify(md));
```

`JSON.stringify("# Hello")` returns `"\"# Hello\""` (with surrounding quotes). The `JBCefJSQuery` handler stores this quoted string as-is. When `saveContentToDocumentAndFile()` calls `document.setText("\"# Hello\"")`, the file content is corrupted with extra quotes.

**Impact:** Any save via `flushPendingWebContent()` (tab switch, IDE close) corrupts the file with surrounding quotes.

### Root Cause 2: `CountDownLatch` EDT Deadlock (CRITICAL)

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowSharedBrowserService.kt`
**Line:** 305

```kotlin
val completed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
```

`CountDownLatch.await()` blocks the EDT (Event Dispatch Thread). `JBCefJSQuery.addHandler` callbacks are dispatched to the EDT via `invokeEdtFunc()`. If `invokeEdtFunc()` is NOT reentrant (IntelliJ 2025.2+), the handler can never execute while `await()` is blocking, creating a deadlock. The 2-second timeout expires, `getCurrentMarkdown()` returns `null`, and `flushPendingWebContent()` silently skips the save.

**Impact:** `flushPendingWebContent()` returns null, `deselectNotify()` and `dispose()` lose all pending content.

### Root Cause 3: `getMarkdown()` Called Before `crepe.create()` Completes (MEDIUM)

**File:** `webview/src/main.ts`
**Line:** 1409-1411

```typescript
window.getMarkdown = () => {
    if (!activeCrepe) return "";
    return safeReadMarkdown(activeCrepe, "", "window.getMarkdown");
};
```

Missing `isCrepeReady` check. `injectBridgeAndBootstrap()` is called from `onLoadEnd` which fires after page load but BEFORE `crepe.create()` (async) completes. `crepe.getMarkdown()` throws when Crepe is not ready, and `safeReadMarkdown()` returns empty string. `flushPendingWebContent()` saves empty content, wiping the file.

**Impact:** Early calls to `getCurrentMarkdown()` return empty string, potentially overwriting file with empty content.

## Why v1 and v2 Failed

| Fix | What It Did | Why It Failed |
|-----|-------------|---------------|
| v1: `commitDocument()` | Added `FileDocumentManager.commitDocument()` after `setText()` | Used `invokeLater` debouncing + `setBinaryContent()` race condition |
| v2: `CountDownLatch` + `saveDocument()` | Removed `invokeLater`, added synchronous `getCurrentMarkdown()` | `CountDownLatch` deadlock + `JSON.stringify()` corruption |

## Fixes Applied (v3)

### Fix 1: Cache Latest Markdown in `MarkFlowEditor`

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`

Added `cachedMarkdown` field. `applyWebUpdate()` caches incoming content. `flushPendingWebContent()` uses cache instead of calling `getCurrentMarkdown()` (avoids deadlock + JSON bug entirely).

```kotlin
// New field:
private var cachedMarkdown: String? = null

// In applyWebUpdate():
cachedMarkdown = content
saveContentToDocumentAndFile(content)

// In flushPendingWebContent():
cachedMarkdown?.let { saveContentToDocumentAndFile(it) }
```

**Benefit:** Zero dependency on JCEF synchronous query. No deadlock, no JSON corruption, no timing issues.

### Fix 2: Remove `JSON.stringify()` from `getCurrentMarkdown()`

**File:** `src/main/kotlin/com/algorist/markflow/MarkFlowSharedBrowserService.kt`

```kotlin
// BEFORE:
window.__markflowFlushQuery(JSON.stringify(md));

// AFTER:
window.__markflowFlushQuery(md || "");
```

The `JBCefJSQuery` handler receives the raw string. No quote wrapping. `|| ""` guards against `undefined` from failed `getMarkdown()`.

### Fix 3: Add `isCrepeReady` Guard to `getMarkdown()`

**File:** `webview/src/main.ts`

```typescript
// BEFORE:
window.getMarkdown = () => {
    if (!activeCrepe) return "";
    return safeReadMarkdown(activeCrepe, "", "window.getMarkdown");
};

// AFTER:
window.getMarkdown = () => {
    if (!activeCrepe || !isCrepeReady) return "";
    return safeReadMarkdown(activeCrepe, "", "window.getMarkdown");
};
```

Prevents `crepe.getMarkdown()` from throwing when Crepe is not fully initialized.

## Updated Save Flow Architecture

```
USER TYPES IN EDITOR (real-time save)
    |
    v
Crepe markdownUpdated -> sendToIntelliJ(markdown) -> cefQuery
    |
    v
JBCefJSQuery handler (EDT) -> applyWebUpdate(content)
    |
    +-> cachedMarkdown = content           <-- NEW CACHE
    |
    v
saveContentToDocumentAndFile(content)
    |
    +-> WriteCommandAction { document.setText(content) }
    +-> FileDocumentManager.saveDocument(document)  -> DISK
    v
FILE SAVED (immediate, no deadlock)

TAB SWITCH / IDE CLOSE (flush save)
    |
    v
deselectNotify() / dispose() -> flushPendingWebContent()
    |
    v
cachedMarkdown?.let { saveContentToDocumentAndFile(it) }  <-- USES CACHE
    |
    +-> No JCEF query, no CountDownLatch, no deadlock
    v
FILE SAVED (reliable, from cache)
```

## Files Modified

1. `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`
   - Added `cachedMarkdown: String?` field
   - `applyWebUpdate()` now caches incoming content
   - `flushPendingWebContent()` uses cache (removed `getCurrentMarkdown()` call)

2. `src/main/kotlin/com/algorist/markflow/MarkFlowSharedBrowserService.kt`
   - Removed `JSON.stringify()` from `getCurrentMarkdown()` script
   - Added `|| ""` null guard

3. `webview/src/main.ts`
   - Added `isCrepeReady` check to `window.getMarkdown`

## Build Status

- Kotlin compilation: **PASS** (`./gradlew compileKotlin` - BUILD SUCCESSFUL, 0 errors)
- TypeScript compilation: **PASS** (`npx tsc --noEmit` - 0 errors)

## Verification Steps

1. Build and install the plugin (`./gradlew buildPlugin`)
2. Open a markdown file in MarkFlow editor
3. Type some content -> verify file is saved immediately (check disk with `cat` or external editor)
4. Type more content, then switch to another tab -> verify content is preserved
5. Switch back -> verify content is still there
6. Close IDE without explicit save -> reopen -> verify all changes are persisted
7. Verify file content has NO extra quotes (Bug 1 fix verification)
