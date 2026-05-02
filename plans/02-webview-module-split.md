# Webview Module Split Plan

## Goal
- `webview/src/main.ts`를 엔트리 전용으로 줄이고 기능별 모듈로 분리한다.

## Target Layout
- `webview/src/app/runtime-settings.ts`
- `webview/src/app/editor-state.ts`
- `webview/src/app/clipboard.ts`
- `webview/src/app/mermaid.ts`
- `webview/src/app/recovery.ts`
- `webview/src/app/bridge.ts`
- `webview/src/styles/mermaid.css`

## Implementation Steps
1. runtime settings resolve/normalize 로직을 분리한다.
2. editor state capture/apply/update helpers를 분리한다.
3. clipboard Markdown detection과 selection replace 로직을 분리한다.
4. Mermaid rendering queue, timeout, error handling을 분리한다.
5. recovery lease request/response flow를 분리한다.
6. `main.ts`는 모듈을 조립하고 `init`만 호출하도록 정리한다.

## Style Rules
- `webview/src/style.css`는 app shell, layout, shared UI만 유지한다.
- Mermaid-specific selectors는 `webview/src/styles/mermaid.css`로 이동한다.
- `webview/src/mermaid/style.css`는 소유권을 확정한 뒤 제거하거나 새 경로로 통합한다.

## Cleanup Targets
- `webview/src/counter.ts` 제거
- `webview/index.html`의 `MakFlow` 오타 수정
- template-era class names와 dead code 제거

## Verification
- `npm run build`
- JCEF boot smoke test
- Markdown paste / Mermaid preview / settings sync regression check
