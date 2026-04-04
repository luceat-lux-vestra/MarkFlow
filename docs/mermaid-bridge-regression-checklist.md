# Mermaid + Bridge Regression Checklist

Scope: `webview/src/main.ts` and `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`

## A. Mermaid Rendering Pipeline

- [ ] A-01 LIVE mode: same Mermaid block updates repeatedly and only latest preview is visible.
  - Expected log: `MARKFLOW_UI mermaid:superseded id=...` for dropped stale results.
- [ ] A-02 DEBOUNCED mode: rapid typing in one block does not enqueue many renders for that block.
  - Expected: only one render per debounce window for that preview node.
- [ ] A-03 Multi-block DEBOUNCED: editing block A does not cancel pending block B render.
  - Expected: both previews update independently.
- [ ] A-04 MANUAL mode: repeated preview refresh on same block does not leak stale manual ids.
  - Expected: only latest button action for that block triggers rendering.
- [ ] A-05 Theme/security changes during rendering do not apply stale SVG result.
  - Expected log: `stale id=...` or `superseded id=...` for outdated requests.

## B. IntelliJ <-> Webview Bridge Sync

- [ ] B-01 Web -> IntelliJ coalescing: rapid web typing does not cause repeated document set races.
  - Expected: Kotlin applies latest content; no oscillation loop.
- [ ] B-02 IntelliJ -> Web stale drop: burst document changes do not allow old retry to overwrite latest state.
  - Expected log: `bridge:updateFromIntelliJ:dropped:<seq>` when superseded.
- [ ] B-03 Initial markdown sync cannot overwrite newer content pushed after load.
  - Expected log: `bridge:initialMarkdown:dropped:<seq>` when late.
- [ ] B-04 Runtime settings stale drop: old settings retry does not override newer settings push.
  - Expected log: `bridge:runtimeSettings:dropped:<seq>` when superseded.
- [ ] B-05 Initial settings sync cannot override newer runtime push.
  - Expected log: `bridge:initialSettings:dropped:<seq>` when late.

## C. Lifecycle and Recovery

- [ ] C-01 Tab inactive/active toggles do not deadlock preview updates.
- [ ] C-02 Reload path (`forceReload=true`) reapplies latest settings after webview reload.
- [ ] C-03 Editor dispose while pending retries does not throw and no repeated JS failures.

## D. Acceptance Criteria

- [ ] No stale Mermaid preview overwrite observed in A-01..A-05.
- [ ] No bridge feedback loop between Kotlin document listener and web markdownUpdated.
- [ ] No uncaught exceptions in JCEF console/IDE logs during all scenarios.
- [ ] All scenarios pass on macOS target IDE build used for release.

