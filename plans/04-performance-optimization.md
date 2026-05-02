# Performance Optimization Plan

## Goal
- Typing lag and bridge overload in MarkFlow's hot path.
- Reduce JS <-> Kotlin IPC frequency and shorten EDT write pressure during continuous input.
- Keep visual rendering behavior unchanged unless it is directly tied to bridge or save overhead.

## Current Bottlenecks
- `webview/src/app/editor-session.ts`
  - `markdownUpdated` currently forwards content immediately through `sendToIntelliJ(...)`.
  - Hot-path diagnostics also call `emitToIntelliJLog(...)`, which adds extra bridge traffic.
- `webview/src/app/bridge.ts`
  - Each save payload sends the full markdown plus editor UI state through `cefQuery`.
- `src/main/kotlin/com/algorist/markflow/editor/MarkFlowEditor.kt`
  - Each update runs document replacement and save work on the IDE side right away.
- `src/main/kotlin/com/algorist/markflow/browser/MarkFlowBrowserLeasePool.kt`
  - Save/load/debug logging is verbose on the bridge path and may amplify IPC cost in slower environments.

## Implementation Strategy
1. Add input-path debouncing in the webview layer.
   - Debounce `markdownUpdated` bursts and flush only after a short idle window.
   - Keep immediate flush on boundary events such as blur, tab switch, editor deactivation, and unload.
2. Reduce bridge chatter on the hot path.
   - Keep functional save/update messages.
   - Minimize or gate repeated `emitToIntelliJLog(...)` calls that happen for every keystroke burst.
3. Add Kotlin-side coalescing as a second safety net.
   - If updates arrive faster than the IDE can process them, keep only the latest pending content.
   - Avoid repeated write/save work for intermediate states that are already obsolete.
4. Shorten EDT work where possible.
   - Keep document mutation minimal.
   - Separate "apply document text" from "persist to disk" if that can be done without changing behavior.
5. Revisit log level and message volume.
   - Move noisy hot-path logs from `INFO` to `DEBUG` where safe.
   - Preserve actionable error logs and lifecycle logs.

## Optional Follow-Ups
- If debounce alone is not enough, consider delta/patch-based markdown sync instead of full-document payloads.
- If large documents still lag, add size-based heuristics to reduce preview or logging work in extreme cases.
- If users report save latency but not typing latency, tune Kotlin-side coalescing before changing the webview again.

## Verification
- Burst typing should produce fewer bridge calls than before.
- Final content must still be saved on blur, detach, and dispose.
- No regression in IntelliJ -> webview synchronization.
- `./gradlew check`
- `npm run build` inside `webview/`
- Manual IDE smoke test on a large markdown file and a small markdown file

## Assumptions
- The main target is IPC and IDE thread pressure, not visual rendering.
- A fixed debounce window is acceptable for the first pass.
- Existing `cefQuery`/JSQuery transport should remain in place for now.
