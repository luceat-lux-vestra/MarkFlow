# Save Diagnostic — 왜 저장이 안 되는가?

**Date:** 2026-04-26
**Goal:** Save chain의 끊긴 지점을 로그로 추적 → 근본 원인 파악

---

## 문제 정의

- `save-fix-v4.md`의 28개 버그 모두 해결됨 (FIXED 12, RESOLVED 14)
- IDE에서 타이핑 시 **저장 관련 로그가 하나도 안 나옴**
- → 코드가 실행되지 않거나, 로그가 보이지 않는 것 중 하나

## Save Chain (전체 흐름)

```
[Frontend] User types
  → Crepe.markdownUpdated event fires
    → attachCrepeBridge listener (main.ts:1262-1277)
      → Guard 1: isCrepeReady && activeCrepe === crepe
      → Guard 2: !isUpdatingFromIntelliJ
      → Guard 3: markdown !== prevMarkdown
        → sendToIntelliJ() (main.ts:1083-1117)
          → Guard 4: window.cefQuery exists
          → Guard 5: JSON.stringify succeeds
            → window.cefQuery({ request, onSuccess, onFailure })
              ↓ (JCEF bridge)
[Kotlin] lease.jsQuery handler (SharedBrowserService.kt:352-440)
  → Guard 6: sessionId match (line 367)
  → Guard 7: action === "update" (line 372)
  → Guard 8: lease.attachedEditor != null (line 374)
    → targetEditor.applyWebUpdate() (MarkFlowEditor.kt:71-84)
      → LOG.info("MARKFLOW_SAVE applyWebUpdate...")
      → saveContentToDocumentAndFile(content) (line 86-109)
        → Guard 9: document != null (line 87)
        → Guard 10: text changed (line 92)
          → document.setText() + FileDocumentManager.saveDocument()
          → LOG.info("MARKFLOW_SAVE saveContentToDocumentAndFile: saved...")
```

## 발견된 문제

### 문제 1: Frontend 로그가 완전히 보이지 않음 (CRITICAL)
`emitToIntelliJLog`는 `console.info`를 호출하지 않음 (main.ts:236-244):
```typescript
const emitToIntelliJLog = (message: string) => {
    const logger = window.markflowLog;
    if (typeof logger !== "function") return;  // ← silently no-op
    try { logger(message); } catch { /* ignored */ }
};
```
- `window.markflowLog`가 undefined이면 모든 로그 침묵
- Devtools console에서도 안 보임 → `console.info` fallback 없음

### 문제 2: Kotlin이 MARKFLOW_SAVE를 LOG.debug로 라우팅 (CRITICAL)
`onConsoleMessage` (SharedBrowserService.kt:454-458):
```kotlin
if (safeMessage.contains("MARKFLOW_UI") || safeMessage.contains("MARKFLOW_DIAG")) {
    LOG.warn(...)   // ← INFO 레벨에서 가시적
} else {
    LOG.debug(...)  // ← INFO 레벨에서 보이지 않음
}
```
- `MARKFLOW_SAVE`는 `MARKFLOW_UI`도 `MARKFLOW_DIAG`도 아님
- → `LOG.debug`로 가 → IDE 로그에서 안 보임

### 문제 3: Kotlin JS bridge handler에 진입 로그 없음 (HIGH)
`setupQueries` (line 352-440):
- `addHandler` 진입 시 로그 없음
- sessionId mismatch, attachedEditor null 등 silent drop 지점에 로그 없음
- → Backend에서 메시지가 도착했는지조차 알 수 없음

## 적용된 진단 로그

### Frontend (main.ts)
| # | 위치 | 로그 내용 |
|---|------|----------|
| 1 | `attachCrepeBridge` (1261) | `markFlowStage("bridge:attachCrepeBridge:start")` — listener 등록 확인 |
| 2 | `attachCrepeBridge` (1272) | `console.info("MARKFLOW_UI SAVE:BLOCKED listener ...")` — Guard 1,2 실패 시 |
| 3 | `attachCrepeBridge` (1282) | `console.info("MARKFLOW_UI SAVE:FIRING len=...")` — Guard 3 통과 시 |
| 4 | `sendToIntelliJ` (1084) | `console.info("MARKFLOW_UI SAVE:ENTRY cefQuery=...")` — 함수 진입 확인 |
| 5 | `sendToIntelliJ` (1086) | `console.info("MARKFLOW_UI SAVE:BLOCKED cefQuery missing")` — Guard 4 실패 |
| 6 | `sendToIntelliJ` (1105) | `console.info("MARKFLOW_UI SAVE:BLOCKED request invalid")` — Guard 5 실패 |
| 7 | `sendToIntelliJ` (1110) | `console.info("MARKFLOW_UI SAVE:CEF_QUERY sessionId=...")` — cefQuery 호출 |
| 8 | `sendToIntelliJ` (1115) | `console.info("MARKFLOW_UI SAVE:ACK received")` — onSuccess |
| 9 | `sendToIntelliJ` (1119) | `console.info("MARKFLOW_UI SAVE:FAIL ...")` — onFailure |

### Kotlin (SharedBrowserService.kt)
| # | 위치 | 로그 내용 |
|---|------|----------|
| 1 | `onConsoleMessage` (454) | `MARKFLOW_SAVE`를 `LOG.warn`으로 변경 → IDE 로그에서 가시적 |
| 2 | `setupQueries` (357) | `LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED empty request")` |
| 3 | `setupQueries` (363) | `LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED not JSON")` |
| 4 | `setupQueries` (370) | `LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED session mismatch")` |
| 5 | `setupQueries` (375) | `LOG.info("MARKFLOW_SAVE setupQueries:received action=...")` — 메시지 도착 |
| 6 | `setupQueries` (379) | `LOG.warn("MARKFLOW_SAVE setupQueries:DROPPED no attachedEditor")` |
| 7 | `setupQueries` (383) | `LOG.info("MARKFLOW_SAVE setupQueries:DISPATCHING to applyWebUpdate ...")` |

## 진행 상황

- [x] 문제 분석 완료 (2026-04-26)
- [x] 진단 로그 주입 완료 (Frontend 9개, Kotlin 7개)
- [x] 빌드 완료 — 컴파일 성공
- [ ] IDE 실행 + 로그 확인
- [ ] 끊긴 지점 파악 → 근본 원인 수정

## 다음 스텝

1. `./gradlew buildPlugin` 실행
2. IDE에서 플러그인 로드
3. Markdown 파일 열기 → 타이핑
4. IDE Log에서 `MARKFLOW_SAVE` 로그 확인
5. 어느 지점에서 로그가 끊기는지 파악
