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

## Phase 3 - Runtime Recheck Result (완료)

### 사용자 재검증 로그 (2026-04-27 20:58)
- `MARKFLOW_UI SAVE:FIRING len=5458 prevLen=5459`
- `MARKFLOW_SAVE markdownUpdated:SEND len=5458 prevLen=5459`
- `MARKFLOW_UI SAVE:ENTRY cefQuery=function len=5458`
- `MARKFLOW_UI SAVE:CEF_QUERY sessionId=lease-2-session-6 contentLen=5458`
- `MARKFLOW_SAVE sendToIntelliJ:CEF_QUERY sessionId=lease-2-session-6 contentLen=5458`
- `MARKFLOW_SAVE saveContentToDocumentAndFile: non-EDT dispatch ... thread=CefHandlers-execution-0`
- `MARKFLOW_UI SAVE:ACK received`
- `MARKFLOW_SAVE sendToIntelliJ:ACK received`

### 판독
- `cefQuery=undefined` 이슈는 해소됨 (`cefQuery=function` 확인)
- 프론트 -> JCEF 브리지 전송 + ACK 왕복이 정상 동작
- 저장 호출도 실제로 실행됨 (`saveContentToDocumentAndFile` 진입 로그 확인)
- `non-EDT dispatch`는 오류가 아니라, non-EDT 수신을 EDT로 강제 전환하는 보호 로직이 동작했다는 의미

### 현재 상태 결론
- 브리지 단절로 저장이 막히던 핵심 문제는 해결된 것으로 판단
- 현재 로그 기준으로는 "타이핑 시 저장 경로 진입"까지 정상

### 남은 확인 (최종 안전 확인)
1. 같은 타이핑 직후 파일 디스크 내용이 실제 변경됐는지 확인
2. 탭 전환/에디터 닫기에서도 동일하게 내용 유지되는지 확인

## Phase 4 - Final Validation Plan (다음 단계)

### 확인 포인트
- IntelliJ 내부 상태 뿐 아니라 실제 파일 바이트/텍스트가 갱신되는지
- `deselectNotify()` / `dispose()` 경로의 flush가 데이터 손실 없이 동작하는지

### 성공 기준
- 타이핑 후 즉시 파일 재열기 시 변경 내용 유지
- 탭 전환 후 재열기 시 변경 내용 유지
- 에디터 닫기 후 재열기 시 변경 내용 유지
