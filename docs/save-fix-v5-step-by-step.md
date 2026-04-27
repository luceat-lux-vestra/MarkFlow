# MarkFlow Save Fix v5 - Step by Step Investigation

## Date
2026-04-27

## Goal
- Webview(Milkdown)에서 타이핑한 내용이 IntelliJ 문서/파일로 즉시 반영되고 즉시 디스크에 저장되도록 복구
- 한 번에 큰 변경 없이 단계별로 원인 확인 -> 최소 수정 -> 검증

## Phase 0 - Baseline Read (완료)

### 읽은 자료
- `docs/save-diagnostic.md`
- `docs/save-fix-analysis.md`
- `docs/save-fix-v2.md`
- `docs/save-fix-v3.md`
- `docs/save-fix-v4.md`
- `src/main/kotlin/com/algorist/markflow/MarkFlowSharedBrowserService.kt`
- `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`
- `webview/src/main.ts`

### 현재 코드 기준 저장 경로
1. `webview/src/main.ts` `markdownUpdated` -> `sendToIntelliJ(...)`
2. `MarkFlowSharedBrowserService.setupQueries()` `action=update` 수신
3. `MarkFlowEditor.applyWebUpdate(...)`
4. `MarkFlowEditor.saveContentToDocumentAndFile(...)`
5. `WriteCommandAction.runWriteCommandAction { document.setText(...) }`
6. `FileDocumentManager.saveDocument(document)`

### 1차 가설 (정적 분석)
- 저장 실패가 재현된다면, 가장 가능성이 높은 지점은 `saveContentToDocumentAndFile()`의 스레드 문맥
- 이유:
  - `JBCefJSQuery.addHandler` 콜백이 항상 EDT 보장을 명확히 하지 않음
  - 현재 구현은 호출 스레드에서 즉시 `WriteCommandAction.runWriteCommandAction(...)` 실행
  - non-EDT 진입 시 write command 실패로 저장이 무시될 수 있음
- 기존 docs에서는 flush/bridge 경합 이슈를 주로 다뤘고, "write 실행 스레드 강제"는 아직 명시적으로 방어되지 않음

## Phase 1 - First Fix (완료)

### 적용 변경
- 파일: `src/main/kotlin/com/algorist/markflow/MarkFlowEditor.kt`
- 변경 내용:
  - `saveContentToDocumentAndFile(...)` 내부 저장 로직을 `saveAction`으로 캡슐화
  - `ApplicationManager.getApplication().isDispatchThread` 확인
  - EDT면 즉시 실행, non-EDT면 `invokeAndWait(saveAction)`로 EDT 전환 후 실행
  - non-EDT 진입 시 경고 로그 추가:
    - `MARKFLOW_SAVE saveContentToDocumentAndFile: non-EDT dispatch ...`

### 기대 효과
- JS bridge 콜백 스레드가 어디서 오더라도 write/save 경로가 안정적으로 실행
- "수정은 되는데 저장은 안 됨" 유형의 스레드 문맥 실패를 1차 차단

### 검증
- `./gradlew compileKotlin`: 성공 (BUILD SUCCESSFUL)
- `MarkFlowEditor.kt` 정적 오류: 없음

## Phase 2 - Runtime Evidence + Bridge Root Cause (완료)

### 실제 재현 로그 (사용자 제공)
- `MARKFLOW_UI SAVE:FIRING len=5458 prevLen=5459`
- `MARKFLOW_UI SAVE:ENTRY cefQuery=undefined len=5458`
- `MARKFLOW_UI SAVE:BLOCKED cefQuery missing`

### 확정 원인
- 파일: `src/main/kotlin/com/algorist/markflow/MarkFlowSharedBrowserService.kt`
- 문제 코드(기존):
  - `lease.jsQuery.inject("window.cefQuery")`
  - `lease.debugQuery.inject("window.markflowLog")`
- 원인 설명:
  - `JBCefJSQuery.inject(...)`는 "함수 선언"이 아니라 "쿼리 호출 스니펫"을 생성하는 API
  - 기존 코드는 `window.cefQuery` 함수를 실제로 정의하지 못했고, 결과적으로 프론트에서 `typeof window.cefQuery === "undefined"` 상태가 지속
  - 저장 경로가 `sendToIntelliJ` 초입에서 매번 차단됨

### 적용 수정
1. `injectBridgeAndBootstrap(...)`에서 브리지 래퍼를 명시적으로 정의
   - `window.cefQuery = function(payload) { ... }`
   - `window.markflowLog = function(message) { ... }`
2. `window.cefQuery` 내부에서
   - `request/onSuccess/onFailure`를 임시 전역 변수로 준비
   - `lease.jsQuery.inject(request, onSuccess, onFailure)` 스니펫 호출
3. `window.markflowLog` 내부에서
   - `lease.debugQuery.inject(...)` 스니펫 호출
4. `getCurrentMarkdown(...)`도 동일 API 오사용 수정
   - 기존의 "함수 주입" 방식 제거
   - `flushQuery.inject("md")` 호출 스니펫으로 현재 markdown을 직접 전송

### 기대 효과
- 프론트의 `sendToIntelliJ(...)`에서 `window.cefQuery`가 정상 함수로 동작
- 타이핑 이벤트가 Kotlin JSQuery 핸들러까지 도달 가능
- 동기 flush 경로(`getCurrentMarkdown`)도 API 의미에 맞게 동작

### 검증
- `./gradlew compileKotlin`: 성공 (BUILD SUCCESSFUL)

## Phase 3 - Runtime Recheck Plan (다음 단계)

### 확인할 로그 시퀀스
1. `MARKFLOW_UI SAVE:FIRING ...`
2. `MARKFLOW_UI SAVE:ENTRY cefQuery=function ...` (또는 undefined 아님)
3. `MARKFLOW_SAVE setupQueries:received action=update ...`
4. `MARKFLOW_SAVE applyWebUpdate ...`
5. `MARKFLOW_SAVE saveContentToDocumentAndFile: saved ...`

### 판정 기준
- 1~5가 순서대로 보이면 1차 목표(타이핑 즉시 저장) 달성
- 2에서 다시 undefined면 브리지 주입 타이밍 문제를 추가 조사
- 3 이후에서 끊기면 backend save 경로를 재점검

## Next
- IDE에서 동일 시나리오를 다시 재현해 저장 체인이 어디까지 진행되는지 확인 후 다음 최소 수정 적용
