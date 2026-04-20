# Phase 1: Frontend 구조 분해

## 목표

`main.ts`(1508줄)의 단일 파일을 관심사별 모듈로 분리하여 유지보수성과 테스트 용이성을 확보한다.

## 기존 구조

```
webview/src/
├── main.ts        (1508줄 - 모든 로직 단일 파일)
├── style.css      (494줄 - Vite 템플릿 주석 포함)
├── counter.ts     (9줄 - 미사용 Vite 템플릿 잔여)
└── vite-env.d.ts  (45줄 - 전역 타입 정의)
```

## 목표 구조

```
webview/src/
├── main.ts                          # ~60줄 - initEditor()만, 전역 window attach
├── style.css                        # ~160줄 - Vite 템플릿 주석 블록 삭제 후
├── vite-env.d.ts                    # 변경 없음
│
├── state.ts                         # 🔴 NEW: 모든 공유 상태 중앙 관리 (전역 변수 대체)
│                                   #   모듈들은 이 state.ts를 명시적으로 import한다.
│
├── bridge/                          # JCEF bridge
│   ├── index.ts                     # Barrel export
│   └── jcef.ts                      # sendToIntelliJ(), cefQuery serialize, emitToIntelliJLog
│                                   #   markFlowStage 포함 (DOM attribute 설정)
│
├── mermaid/                         # Mermaid 렌더링
│   ├── index.ts                     # Barrel export
│   │                              #   config, queue, renderer, crepe에서 필요한 것만 re-export
│   ├── config.ts                    # createMermaidPreviewConfig(), reconfigureMermaid() (~60줄)
│   ├── queue.ts                     # Render queue, debounce, lifecycle invalidation (~100줄)
│   ├── renderer.ts                  # wrapMermaidSvg(), renderMermaidError() (~40줄)
│   └── crepe.ts                     # createCrepeInstance(), attachCrepeBridge(), startCrepe() (~150줄)
│                                   #   recreateCrepeInstance(), flushPendingIntelliJState() 포함
│                                   #   beginExternalUpdateGuardLater/clear(), recoverEditorLayout() 포함
│                                   #   requestPreviewResumeRefresh(), safeReadMarkdown() 포함
│                                   #   isEditorViewContextError(), logEditorViewContextError() 포함
│                                   #   showBootError(), withTimeout(), enqueueMermaidRender() 포함
│                                   #   scheduleMermaidRender() 포함 (queue.ts와 공유 상태 필요)
│
├── settings/                        # Runtime settings
│   ├── index.ts                     # Barrel export
│   └── manager.ts                   # resolveRuntimeSettings(), applyRuntimeSettingsFromHost() (~180줄)
│                                   #   rerenderPreviewsAfterSettingsChange(), logThemeDiagnostics() 포함
│                                   #   🔴 resolveRuntimeSettings는 여기서 단 한 곳만 정의 (source of truth)
│                                   #   mermaid/renderer.ts, mermaid/crepe.ts 모두 settings에서 import
│
├── editor-state/                    # Editor UI state
│   ├── index.ts                     # Barrel export
│   └── capture.ts                   # captureEditorUiState(), getScrollElement(), clamp (~50줄)
│   └── sync.ts                      # applyEditorUiState(), replaceEditorMarkdown (~70줄)
│
├── paste/                           # Paste handling
│   ├── index.ts                     # Barrel export
│   └── handler.ts                   # normalizeClipboardMarkdown(), hasMarkdownTableStructure() (~100줄)
│                                   #   looksLikeMarkdownClipboard(), getMarkdownClipboardText() 포함
│                                   #   replaceSelectionWithMarkdown(), installMarkdownPasteHandler() 포함
│
├── recovery/                        # Leader/follower recovery
│   ├── index.ts                     # Barrel export
│   └── lease.ts                     # requestRecoveryLease(), notifyRecoveryOutcome() (~140줄)
│                                   #   clearRecoveryState(), RecoveryBridgeResponse 타입 포함
│                                   #   RecoveryRole 타입, getActiveRecoveryRole/getEpoch() 접근자 포함
│
└── force-rerender/                  # Force re-render
    ├── index.ts                     # Barrel export
    └── shortcut.ts                  # triggerForceRerender(), keyboard shortcut handler (~50줄)
```

---

## 🆕 state.ts — 모든 공유 상태 중앙 관리 (전역 변수 대체)

**이유:** 모듈들이 main.ts의 전역 변수를 직접 참조하면 결합도가 높아진다. 모든 상태를 state.ts에서 관리하고, 모듈들은 이를 명시적으로 import한다.

**이관 대상 변수 (main.ts lines 13-54):**
```typescript
// editor lifecycle state
isUpdatingFromIntelliJ, isCrepeReady, pendingMarkdownFromIntelliJ,
pendingEditorStateFromIntelliJ, removeMarkdownPasteHandler, isEditorActive

// mermaid state
manualMermaidRenderers, activeCrepe, mermaidPreviewEpoch, lastAppliedMermaidTheme,
lastAppliedSettingsRevision, pendingLayoutRecovery, pendingHostForceRerender

// recovery state
recoveryRequestInFlight, activeRecoveryEpoch, activeRecoveryRole, previewResumeRetryToken

// crepe state
crepeSessionSequence, activeCrepeSessionId, isRecreatingCrepe

// external update guard
externalUpdateGuardToken, pendingSettingsRerenderRevision, hasAppliedRuntimeSettingsOnce,
lastAppliedPreviewOnlyByDefault

// mermaid queue/debounce state (queue.ts가 직접 관리)
mermaidRenderQueues, mermaidRenderRequestId, mermaidDebounceTimers, allMermaidDebounceTimerIds
mermaidLoadingWatchdogTimers, manualPreviewIdByRenderer, mermaidPreviewRenderers,
mermaidPreviewIdByRenderer

// constants (상수이므로 각 모듈에서 직접 정의 가능, 중복 제거)
EXTERNAL_UPDATE_GUARD_MS, BOOT_READY_TIMEOUT_MS, MANUAL_MERMAID_SHORTCUT_KEY
MERMAID_RENDER_*_MS, MERMAID_LOADING_WATCHDOG_MS
```

**state.ts export:**
```typescript
// Editor lifecycle
export let isUpdatingFromIntelliJ = false;
export let isCrepeReady = false;
// ... 등 모든 변수를 여기서 export

// Accessors for modules that need to read/write shared state
export function getActiveCrepe(): Crepe | null;
export function setActiveCrepe(crepe: Crepe | null): void;
// ... 상태 읽기/쓰기를 위한 헬퍼 함수들
```

**main.ts:** state에서 import하고 initEditor()만 담당. 전역 window attach는 main.ts가 유지 (외부 API).

---

## 파일별 상세 계획

### `bridge/jcef.ts` (~100줄)

**이관 대상 함수/변수:**
- `sendToIntelliJ(markdownText, uiState)` - main.ts:1089-1120
- `sanitizeUiState(uiState)` - main.ts:1078-1086
- `emitToIntelliJLog(message)` - main.ts:236-244
- `markFlowStage(stage, detail)` - main.ts:252-260

**export:**
```typescript
export function sendToIntelliJ(markdownText: string, uiState: EditorUiState): void;
export function emitToIntelliJLog(message: string): void;
export function markFlowStage(stage: string, detail?: string): void;
```

### `mermaid/index.ts` (~10줄)

```typescript
export { createMermaidPreviewConfig, reconfigureMermaid } from './config';
export { registerMermaidPreviewRenderer, renderAllRegisteredPreviews, invalidateLifecycle } from './queue';
export { wrapMermaidSvg, renderMermaidError } from './renderer';
// crepe.ts는 main.ts에서 직접 import (대용량)
```

### `mermaid/config.ts` (~60줄)

**이관 대상:**
- `createMermaidPreviewConfig()` - main.ts:108-225
- `reconfigureMermaid()` - main.ts:227-233

**export:**
```typescript
export function createMermaidPreviewConfig(): MermaidConfig;
export function reconfigureMermaid(): void;
```

### `mermaid/renderer.ts` (~40줄) — 🔴 기존 계획에서 분할

**이관 대상:**
- `wrapMermaidSvg(svg)` - main.ts:507-518
- `renderMermaidError(applyPreview, error)` - main.ts:520-529

**export:**
```typescript
export function wrapMermaidSvg(svg: string): string;
export function renderMermaidError(applyPreview: (html: string) => void, error: unknown): void;
```

### `mermaid/queue.ts` (~100줄) — state.ts에서 mermaid 관련 상태 import

**이관 대상:**
- `scheduleMermaidRender(renderNow, applyPreviewKey)` - main.ts:544-575
- `enqueueMermaidRender(applyPreview, task)` - main.ts:577-591
- `clearAllMermaidDebounceTimers()` - main.ts:326-329
- `clearMermaidLoadingWatchdog()` - main.ts:331-337
- `invalidateMermaidPreviewLifecycle(reason)` - main.ts:339-348
- `registerMermaidPreviewRenderer(applyPreview, renderNow)` - main.ts:284-294
- `renderAllRegisteredMermaidPreviews()` - main.ts:296-298
- `renderAllManualMermaidPreviews()` - main.ts:278-282
- `renderAllMermaidAndLatexPreviews()` - main.ts:300-315
- `triggerForceRerender()` - main.ts:317-319

**이관 변수 (state.ts에서 import):**
- `manualMermaidRenderers` → state.ts가 소유, queue.ts는 read/write 접근자 사용
- `mermaidPreviewRenderers`, `mermaidPreviewIdByRenderer` → queue.ts가 직접 관리 (state에서 import)
- `mermaidDebounceTimers`, `allMermaidDebounceTimerIds` → queue.ts가 직접 관리
- `mermaidLoadingWatchdogTimers`, `manualPreviewIdByRenderer` → queue.ts가 직접 관리
- `mermaidRenderQueues`, `mermaidPreviewEpoch`, `mermaidRenderRequestId` → queue.ts가 직접 관리

**export:**
```typescript
export function registerMermaidPreviewRenderer(applyPreview: (html: string) => void, renderNow: () => void): void;
export function scheduleMermaidRender(renderNow: () => void, applyPreviewKey?: (html: string) => void): void;
export function triggerForceRerender(): void;
```

### `mermaid/crepe.ts` (~250줄) — 🔴 기존 계획에서 분리 (대용량 Crepe 수명 주기)

**이유:** `createCrepeInstance`, `attachCrepeBridge`, `startCrepe`, `recreateCrepeInstance` 등 Crepe 인스턴스 수명 주기 전체가 들어가면 단일 파일이 너무 커진다. 이 모듈을 별도로 분리한다.

**state.ts에서 import할 변수:**
- `activeCrepe`, `isRecreatingCrepe`, `pendingCrepeRecreate`
- `crepeSessionSequence`, `activeCrepeSessionId`

**settings/manager.ts에서 import할 함수:**
- `resolveRuntimeSettings(raw)` — **단 one source of truth** (mermaid/renderer.ts에서 직접 정의하지 않음)
- `runtimeSettings` 전역 변수

**이관 대상:**
- `createCrepeInstance(initialText, crepeSessionId)` - main.ts:1122-1262
  - `resolveRuntimeSettings(raw)` 호출 → **settings/manager.ts에서 import** (중복 금지!)
  - `resolveMermaidTheme()` → mermaid/config.ts에서 import (또는 inline)
  - `resolveDiagramSecurityLevel()` → mermaid/config.ts에서 import (또는 inline)
- `attachCrepeBridge(crepe)` - main.ts:1264-1274
  - `sendToIntelliJ` → bridge/jcef.ts에서 import
- `startCrepe(crepe, layoutReason, restoreState)` - main.ts:1276-1308
  - `applyRuntimeSettingsFromHost` → settings/manager.ts에서 import (중복 금지!)
- `recreateCrepeInstance(reason)` - main.ts:1310-1385
  - `requestRecoveryLease` → recovery/lease.ts에서 import (중복 금지!)
- `flushPendingIntelliJState(crepe)` - main.ts:1049-1070
  - `sanitizeUiState`, `sendToIntelliJ` → bridge/jcef.ts에서 import
- `beginExternalUpdateGuard()` - main.ts:858-861 → state.ts에서 직접 관리
- `clearExternalUpdateGuardLater()` - main.ts:848-856 → state.ts에서 직접 관리
- `recoverEditorLayout(reason)` - main.ts:831-846 → editor-state/sync.ts에서 import
- `requestPreviewResumeRefresh(reason)` - main.ts:531-542 → queue.ts에서 import
- `safeReadMarkdown(crepe, fallback, reason)` - main.ts:780-790
  - `isEditorViewContextError`, `logEditorViewContextError` → inline (소규모 헬퍼)
- `showBootError(stage, detail)` - main.ts:610-621 → bridge/jcef.ts에서 import
- `withTimeout(promise, timeoutMs)` - main.ts:593-608 → inline (소규모 헬퍼)
- `enqueueMermaidRender(applyPreview, task)` - main.ts:577-591 → queue.ts에서 import
- `scheduleMermaidRender(renderNow, applyPreviewKey)` - main.ts:544-575 → queue.ts에서 import
  > **참고:** scheduleMermaidRender의 debounce 로직은 queue.ts에 두고, crepe.ts는 `queue.schedule()`로 호출
- `isEditorViewContextError(error)` - main.ts:770-773 → inline
- `logEditorViewContextError(reason, error)` - main.ts:775-777 → inline
- `logMermaidTrace(detail)` - main.ts:246-250 → bridge/jcef.ts에서 import
- `logThemeDiagnostics(raw, appliedTheme)` - main.ts:262-270 → settings/manager.ts에서 import

**변수 이관 (state.ts가 소유, crepe.ts는 접근자 사용):**
- `activeCrepe` → state.activeCrepe (getter/setter)
- `isRecreatingCrepe`, `pendingCrepeRecreate` → state.isRecreatingCrepe, state.pendingCrepeRecreate
- `crepeSessionSequence`, `activeCrepeSessionId` → state.crepeSessionSequence, state.activeCrepeSessionId

**export:**
```typescript
export function createCrepeInstance(initialText: string, crepeSessionId: number): Crepe;
export function attachCrepeBridge(crepe: Crepe): void;
export async function startCrepe(crepe: Crepe, layoutReason: string): Promise<void>;
export async function recreateCrepeInstance(reason: string): Promise<void>;
```

### `settings/manager.ts` (~180줄) — 🔴 resolveRuntimeSettings 단 one source of truth

**이관 대상:**
- `resolveRuntimeSettings(raw)` - main.ts:76-84 ← **여기서만 정의, 다른 모듈에서 재정의 금지**
- `DEFAULT_RUNTIME_SETTINGS` - main.ts:56-74 ← **여기서만 정의**
- `applyRuntimeSettingsFromHost(raw)` - main.ts:446-505
- `rerenderPreviewsAfterSettingsChange()` - main.ts:413-444
- `logThemeDiagnostics(raw, appliedTheme)` - main.ts:262-270

**이관 변수:**
- `runtimeSettings` - main.ts:86 → settings/manager.ts가 직접 관리

**export:**
```typescript
export function resolveRuntimeSettings(raw: MarkFlowRuntimeSettings | undefined): Required<MarkFlowRuntimeSettings>;
export function applyRuntimeSettingsFromHost(raw: MarkFlowRuntimeSettings | undefined): void;
```

### `settings/ui.ts` (~80줄) — 🔴 plan에 없던 파일 (기존 계획에서 추가 필요)

**이유:** `applyRuntimeUiSettings`, `ensureManualPreviewToolbar`, `ensureShortcutConflictNotice`는 UI 렌더링 관련 헬퍼로 settings/manager.ts와 분리하는 것이 좋다.

**이관 대상:**
- `applyRuntimeUiSettings()` - main.ts:272-276
- `ensureManualPreviewToolbar()` - main.ts:350-382
- `ensureShortcutConflictNotice()` - main.ts:384-411

**export:**
```typescript
export function applyRuntimeUiSettings(): void;
export function ensureManualPreviewToolbar(): void;
export function ensureShortcutConflictNotice(): void;
```

### `editor-state/capture.ts` (~50줄) — 🔴 plan에 없던 파일 (기존 계획에서 추가 필요)

**이관 대상:**
- `captureEditorUiState(crepe)` - main.ts:863-887
- `getScrollElement()` - main.ts:825-828
- `clamp(value, min, max)` - main.ts:823

**export:**
```typescript
export function captureEditorUiState(crepe: Crepe): EditorUiState;
```

### `editor-state/sync.ts` (~70줄) — 🔴 plan에 없던 파일 (기존 계획에서 추가 필요)

**이관 대상:**
- `applyEditorUiState(crepe, state)` - main.ts:889-921
- `replaceEditorMarkdown(crepe, newMarkdown, skipHistory)` - main.ts:923-945

**export:**
```typescript
export function applyEditorUiState(crepe: Crepe, state: Partial<EditorUiState>): void;
export function replaceEditorMarkdown(crepe: Crepe, newMarkdown: string, skipHistory?: boolean): void;
```

### `paste/handler.ts` (~100줄) — 🔴 plan에 없던 파일 (기존 계획에서 추가 필요)

**이관 대상:**
- `normalizeClipboardMarkdown(text)` - main.ts:948-950
- `hasMarkdownTableStructure(lines)` - main.ts:952-957
- `looksLikeMarkdownClipboard(text)` - main.ts:959-973
- `getMarkdownClipboardText(event)` - main.ts:975-987
- `replaceSelectionWithMarkdown(crepe, markdownText)` - main.ts:989-1014
- `installMarkdownPasteHandler(crepe)` - main.ts:1016-1047
- `removeMarkdownPasteHandler` - main.ts:17 → state.ts에서 관리 (paste/handler가 반환하는 cleanup 함수를 state에 저장)

**export:**
```typescript
export function installMarkdownPasteHandler(crepe: Crepe): () => void; // cleanup 함수 반환
```

### `recovery/lease.ts` (~140줄) — 🔴 plan에 없던 파일 (기존 계획에서 추가 필요)

**이관 대상:**
- `requestRecoveryLease(reason)` - main.ts:686-768
  - `notifyRecoveryOutcome` → recovery/lease.ts 내부에서 직접 정의 (self-contained)
  - `clearRecoveryState` → recovery/lease.ts 내부에서 직접 정의
- `notifyRecoveryOutcome(status, epoch, reason)` - main.ts:636-679
  - `safeReadMarkdown` → mermaid/crepe.ts에서 import (중복 금지!)
- `clearRecoveryState(reason)` - main.ts:623-634

**이관 대상 타입:**
- `RecoveryBridgeResponse` - main.ts:681-684 → recovery/lease.ts에서 정의
- `RecoveryRole` - main.ts:820 → recovery/lease.ts에서 정의

**이관 변수 (state.ts가 소유):**
- `activeRecoveryEpoch` → state.activeRecoveryEpoch
- `activeRecoveryRole` → state.activeRecoveryRole
- `recoveryRequestInFlight` → state.recoveryRequestInFlight

**export:**
```typescript
export type RecoveryRole = "leader" | "follower";
export function requestRecoveryLease(reason: string): Promise<void>;
export function notifyRecoveryOutcome(status: "complete" | "failed", epoch: number, reason: string): void;
export function clearRecoveryState(reason: string): void;
```

### `force-rerender/shortcut.ts` (~50줄) — 🔴 plan에 없던 파일 (기존 계획에서 추가 필요)

**이관 대상:**
- `triggerForceRerender()` - main.ts:317-319 → queue.ts에서 import
- `renderAllMermaidAndLatexPreviews()` - main.ts:300-315 → queue.ts에서 import
- `window.addEventListener("markflowForceRerender", ...)` - main.ts:321-324
- `window.addEventListener("keydown", ...)` - main.ts:1423-1437

**export:**
```typescript
export function setupForceRerender(): void; // window event listener 등록
```

### `main.ts` (~60줄) — 🔴 state.ts에서 모든 상태 import, initEditor만

**남을 코드:**
```typescript
// main.ts (~60줄)
import { Crepe } from "@milkdown/crepe";
import { editorViewCtx, parserCtx } from "@milkdown/core";
import { Slice } from "@milkdown/prose/model";
import { TextSelection } from "@milkdown/prose/state";
import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame.css";
import "katex/dist/katex.min.css";
import mermaid from "mermaid";
import "./style.css";

// 🔴 모든 상태/모듈을 명시적으로 import (결합도 명확화)
import { state } from "./state"; // 모든 공유 상태 import (main.ts가 전역 attach)
import { emitToIntelliJLog, markFlowStage } from "./bridge";
import { applyRuntimeSettingsFromHost } from "./settings/manager";
import { setupForceRerender } from "./force-rerender";

// 전역 window attach (외부 API — IntelliJ plugin side에서 호출)
window.applyMarkFlowSettingsFromIntelliJ = (raw) => { ... }; // settings/manager에서 import
window.setMarkFlowEditorActive = (active) => { ... }; // state.update
window.updateFromIntelliJ = (markdown, uiState) => { ... }; // bridge에서 import
window.applyEditorStateFromIntelliJ = (state) => { ... }; // editor-state에서 import

// initEditor 함수만 남김
async function initEditor() { ... }

initEditor();
```

---

## 의존성 그래프 (🔴 중요 — 순환 참조 금지)

```
main.ts                    → state, bridge, settings/manager, force-rerender, editor-state/sync
state.ts                   → (no imports — 모든 모듈이 여기로 의존)

bridge/jcef.ts             → state, settings/manager (runtimeSettings 참조 시)
mermaid/config.ts          → (no imports — 상수만 사용, runtimeSettings는 settings에서)
mermaid/queue.ts           → state (debounce 상태), bridge (emitToIntelliJLog)
mermaid/renderer.ts        → mermaid/queue, state
mermaid/crepe.ts           → state, bridge (sendToIntelliJ), settings/manager (resolveRuntimeSettings)
                             editor-state/sync, recovery/lease, mermaid/queue

settings/manager.ts        → state (runtimeSettings), bridge (emitToIntelliJLog, markFlowStage)
settings/ui.ts            → state (runtimeSettings), bridge

editor-state/capture.ts   → state
editor-state/sync.ts       → (no imports — 순수 함수)

paste/handler.ts           → state (removeMarkdownPasteHandler), editor-state/sync
recovery/lease.ts          → state, mermaid/crepe (safeReadMarkdown), bridge
force-rerender/shortcut.ts → state, mermaid/queue (triggerForceRerender)
```

**🔴 핵심 규칙:** `settings/manager.ts`가 `resolveRuntimeSettings`, `DEFAULT_RUNTIME_SETTINGS`, `runtimeSettings`의 **단 one source of truth**이다. 다른 모듈에서 이 것을 재정의하면 안 된다.

---

## 단계별 실행 순서 (기존 15단계 → 수정된 버전)

> **🔴 변경사항:** state.ts를 가장 먼저 만들고, 모든 모듈이 이를 import하도록 한다.

1. **`state.ts` 생성** — 🔴 NEW: 모든 전역 상태 변수 이관
2. **`bridge/jcef.ts` 생성** — `sendToIntelliJ`, `sanitizeUiState`, `emitToIntelliJLog`, `markFlowStage` 이관
3. **`mermaid/config.ts` 생성** — `createMermaidPreviewConfig`, `reconfigureMermaid` 이관
4. **`mermaid/renderer.ts` 생성** — `wrapMermaidSvg`, `renderMermaidError` 이관 (소규모)
5. **`mermaid/queue.ts` 생성** — render queue/debounce 관련 이관 (state에서 mermaid 상태 import)
6. **`mermaid/crepe.ts` 생성** — 🔴 NEW: `createCrepeInstance`, `attachCrepeBridge`, `startCrepe` 등 대용량 Crepe 수명 주기 이관
7. **`settings/manager.ts` 생성** — 🔴 resolveRuntimeSettings 단 one source of truth. `applyRuntimeSettingsFromHost` 이관
8. **`settings/ui.ts` 생성** — 🔴 NEW: `applyRuntimeUiSettings`, `ensureManualPreviewToolbar` 이관
9. **`editor-state/capture.ts` 생성** — 🔴 NEW: `captureEditorUiState` 이관
10. **`editor-state/sync.ts` 생성** — 🔴 NEW: `applyEditorUiState`, `replaceEditorMarkdown` 이관
11. **`paste/handler.ts` 생성** — 🔴 NEW: clipboard/paste handling 이관
12. **`recovery/lease.ts` 생성** — 🔴 NEW: recovery lease protocol 이관
13. **`force-rerender/shortcut.ts` 생성** — 🔴 NEW: keyboard shortcut handler 이관
14. 각 모듈에 `index.ts` barrel file 생성 (bridge/index, mermaid/index 등)
15. **`main.ts` 리팩토링** — state에서 import하고 함수 호출로 교체
16. `main.ts`에서 이관된 코드 삭제 (전역 변수, 함수 정의)
17. **TypeScript 빌드 테스트** — 오류 없으면 Git 커밋

---

## 주의사항 (기존에서 수정)

- **🔴 state.ts가 모든 공유 상태의 단 one source of truth.** 모듈들은 `state.`를 통해 읽고 쓴다. 전역 변수로 직접 접근하지 않는다.
- **🔴 `resolveRuntimeSettings`, `DEFAULT_RUNTIME_SETTINGS`는 `settings/manager.ts`에 단 한 곳만 정의.** mermaid/renderer.ts, mermaid/crepe.ts에서 재정의하지 않는다.
- `window.applyMarkFlowSettingsFromIntelliJ`, `window.setMarkFlowEditorActive`, `window.updateFromIntelliJ`, `window.applyEditorStateFromIntelliJ`는 main.ts에서 전역 attach한다.
- `window.__markflowRenderMermaidPreview`도 main.ts에서 전역 attach한다.
- `window.markflowLog`, `window.cefQuery`, `window.intelliJ_initialMarkdown`, `window.intelliJ_markFlowSettings`는 외부에서 주입받는 전역 객체이므로 그대로 사용한다.
- `window.applyEditorStateFromIntelliJ`에서 `notifyRecoveryOutcome` 호출은 recovery/lease.ts로 이관하되, main.ts에서 window attach는 유지한다.
- **순환 참조 금지:** 위 의존성 그래프를 반드시 준수한다 (예: `mermaid/crepe.ts → settings/manager`는 OK, 역방향은 금지).
