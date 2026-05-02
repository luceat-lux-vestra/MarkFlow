# MarkFlow Audit And Boundary Map

## Goal
- 현재 코드 구조를 기능 경계 기준으로 다시 정리한다.
- 백엔드와 웹뷰의 책임을 분명히 나누고, 어떤 파일을 어디로 옮길지 결정한다.

## Current State
- `MarkFlowSharedBrowserService.kt`는 브라우저 풀, JCEF 브리지, recovery state, webview 호스팅을 한 파일에서 모두 처리한다.
- `webview/src/main.ts`는 부트스트랩, 브릿지, Mermaid 렌더링, 복구 흐름, clipboard, editor state를 모두 가지고 있다.
- 템플릿 잔재로 보이는 `webview/src/counter.ts`와 `webview/index.html`의 오타 `MakFlow`가 남아 있다.
- `webview/src/mermaid/style.css`는 트래킹 상태와 실제 워크트리가 어긋나 있어 소유권부터 정리해야 한다.

## Boundary Rules
- Kotlin backend:
  - IDE, VFS, file save/load, JSQuery transport만 담당한다.
  - 렌더링/DOM/프론트 상태는 다루지 않는다.
- TypeScript frontend:
  - UI rendering, editor state, markdown parsing, Mermaid/KaTeX preview만 담당한다.
  - 파일 저장이나 VirtualFile 접근은 하지 않는다.
- Shared protocol:
  - IntelliJ와 webview 사이의 메시지 포맷은 명시적인 타입과 helper 함수로만 전달한다.

## File Placement Rules
- 백엔드 분리는 `src/main/kotlin/com/algorist/markflow/browser/` 아래로 묶는다.
- 웹뷰 분리는 `webview/src/app/` 아래로 묶고, 스타일은 `webview/src/styles/` 아래로 모은다.
- 파일이 작고 단일 책임이면 원본과 같은 레벨에 남겨도 되지만, 큰 파일을 쪼갤 때는 새 폴더를 우선한다.
- 생성물, lockfile, sandbox, `.idea`, `.intellijPlatform`, `build`는 건드리지 않는다.

## Refactor Order
1. 백엔드에서 webview resource server와 recovery state를 분리한다.
2. 웹뷰에서 runtime settings, editor state, clipboard, Mermaid logic을 모듈로 분리한다.
3. 스타일과 템플릿 잔재를 제거한다.
4. 테스트와 smoke check를 보강한다.

## Acceptance Criteria
- 파일별 책임이 문서와 코드에서 일치한다.
- 큰 파일의 역할이 줄어들고, 새 모듈이 실제로 import/사용된다.
- 기존 기능이 유지되면서 나중에 기능 추가하기 쉬운 구조가 된다.
