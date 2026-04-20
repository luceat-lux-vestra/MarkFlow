# Phase 3: 버그 수정 및 정리

## 목표

리팩토링 과정에서 발견된 버그를 수정하고, 코드베이스에서 잔여물을 정리한다.

---

## 3.1 테스트 패키지 경로 불일치

**파일:** `src/test/kotlin/com/github/luceatluxvestra/markflow/MyPluginTest.kt`

**문제:** 파일이 `com.github/luceatluxvestra/markflow` 경로에 있지만, 실제 패키지는 `com.algorist.markflow`이고 파일 내부 `package` 선언도 `com.algorist.markflow`이다.

**해결:** 파일 이동 + 디렉토리 구조 변경

```
# 현재
src/test/kotlin/com/github/luceatluxvestra/markflow/MyPluginTest.kt

# 변경 후
src/test/kotlin/com/algorist/markflow/MyPluginTest.kt
```

**작업:**
1. `src/test/kotlin/com/algorist/markflow/` 디렉토리 생성
2. `MyPluginTest.kt`를 새 경로로 이동
3. `src/test/kotlin/com/github/luceatluxvestra/` 디렉토리 삭제

---

## 3.2 미사용 파일 삭제

**파일:** `webview/src/counter.ts`

**문제:** Vite 템플릿 잔여 파일. 프로젝트에서 전혀 사용되지 않음.

**작업:** 파일 삭제

```bash
rm webview/src/counter.ts
```

---

## 3.3 타이포그라피 수정

**파일:** `webview/index.html` (6줄)

**문제:** `<title>MakFlow Editor</title>` - "MarkFlow"가 "MakFlow"로 오타

**변경:**
```html
<!-- Before -->
<title>MakFlow Editor</title>

<!-- After -->
<title>MarkFlow Editor</title>
```

---

## 3.4 React import 잔여 제거

**상태:** ✅ 이미 완료됨 — main.ts에 `useState`, `useRef` import가 없음. 이 항목은 무시하면 됨.

---

## 3.5 Vite 스타일 주석 제거

**파일:** `webview/src/style.css` (1-331줄)

**문제:** Vite 템플릿의 hero/counter 스타일이 주석으로 331줄 남음. 실제 프로젝트에서 사용되지 않음.

**작업:** 1-331줄 주석 블록 전체 삭제

```css
/* 삭제할 주석 블록 (1-331줄) */
/*
:root {
  --text: #6b6375;
  ...
}
...
}
*/

/* 유지할 코드 (333줄부터) */
svg.flowchart,
.mermaid .flowchart svg {
  overflow: visible;
}
...
```

---

## 3.6 MarkFlowEditorState.toString() 구현

**파일:** `src/main/kotlin/com/algorist/markflow/editor/MarkFlowEditorState.kt`

**문제:** `toString()`이 구현되지 않아 디버깅 시 정보 부족

**작업:** `MarkFlowEditorState` data class에 `toString()` 추가

```kotlin
data class MarkFlowEditorState(
    val version: Int = CURRENT_VERSION,
    val scrollTop: Int = 0,
    val cursorOffset: Int = -1,
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1
) : FileEditorState {

    override fun toString(): String {
        return "MarkFlowEditorState(version=$version, scrollTop=$scrollTop, cursorOffset=$cursorOffset, selectionStart=$selectionStart, selectionEnd=$selectionEnd)"
    }

    // ... 기존 코드
}
```

---

## 3.7 중첩 data class 분리

**파일:** `src/main/kotlin/com/algorist/markflow/browser/MarkFlowSharedBrowserService.kt`

**문제:** `BrowserLease`, `RecoveryLease`, `RecoveryBridgeResponse`가 `MarkFlowSharedBrowserService` 내부에 중첩되어 있음.

**해결:** Phase 2에서 이미 별도 파일로 분리됨. 이 페이즈에서는 분리 후 남은 참조 코드를 정리한다.

**작업:**
1. `MarkFlowSharedBrowserService.kt`에서 중첩 클래스 정의 삭제
2. 해당 클래스들을 참조하는 모든 곳에 import 추가
3. `BrowserLease` → `com.algorist.markflow.browser.BrowserLease`
4. `RecoveryLease` → `com.algorist.markflow.browser.RecoveryManager.RecoveryState`
5. `RecoveryBridgeResponse` → `com.algorist.markflow.browser.RecoveryManager` 내부 타입

---

## 3.8 MarkdownFileSupport 타입 정의 분리 (선택적)

**파일:** `src/main/kotlin/com/algorist/markflow/editor/MarkFlowFileSupport.kt`

**문제:** 현재 17줄로 작지만, `EDITOR_TYPE_ID` 상수만 포함.

**해결:** 현재 구조 유지. `MarkFlowFileSupport` 객체에 상수만 포함하는 현행 유지가 적절함.

---

## 3.9 MarkFlowStartupActivity invokeLater 통합

**파일:** `src/main/kotlin/com/algorist/markflow/MarkFlowStartupActivity.kt`

**문제:** 2번 `invokeLater`를 호출하고 있음 (15-18줄, 21-28줄). 통합 가능.

**변경:**
```kotlin
// Before: 2번 invokeLater
ApplicationManager.getApplication().invokeLater {
    if (project.isDisposed) return@invokeLater
    sharedBrowserService.preWarm()
}

ApplicationManager.getApplication().invokeLater {
    if (project.isDisposed) return@invokeLater
    manager.openFiles
        .filter(MarkFlowFileSupport::isMarkFlowTarget)
        .forEach { file ->
            manager.setSelectedEditor(file, MarkFlowFileSupport.EDITOR_TYPE_ID)
        }
}

// After: 1번 invokeLater로 통합
ApplicationManager.getApplication().invokeLater {
    if (project.isDisposed) return@invokeLater
    sharedBrowserService.preWarm()
    manager.openFiles
        .filter(MarkFlowFileSupport::isMarkFlowTarget)
        .forEach { file ->
            manager.setSelectedEditor(file, MarkFlowFileSupport.EDITOR_TYPE_ID)
        }
}
```

---

## 체크리스트

- [ ] `MyPluginTest.kt` 패키지 경로 수정
- [ ] `counter.ts` 삭제
- [ ] `index.html` 타이포그라피 수정
- [ ] `main.ts` React import 제거
- [ ] `style.css` 주석 제거
- [ ] `MarkFlowEditorState.toString()` 구현
- [ ] 중첩 data class 분리 후 참조 정리
- [ ] `MarkFlowStartupActivity` invokeLater 통합
