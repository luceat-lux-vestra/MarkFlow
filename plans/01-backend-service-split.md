# Backend Service Split Plan

## Goal
- `MarkFlowSharedBrowserService`를 조정자 역할로 축소한다.
- JCEF 브라우저 풀, webview resource serving, recovery state를 분리한다.

## Target Layout
- `src/main/kotlin/com/algorist/markflow/browser/MarkFlowWebviewResourceManager.kt`
- `src/main/kotlin/com/algorist/markflow/browser/MarkFlowRecoveryCoordinator.kt`
- `src/main/kotlin/com/algorist/markflow/browser/MarkFlowBrowserLease.kt` if lease data needs a stable home later

## Implementation Steps
1. webview 리소스 추출과 로컬 HTTP 서버를 별도 manager로 이동한다.
2. recovery lease state machine을 별도 coordinator로 이동한다.
3. `MarkFlowSharedBrowserService`는 attach/detach, bridge dispatch, editor lifecycle만 담당하도록 정리한다.
4. 필요한 경우 lease data class와 protocol response type을 전용 파일로 옮긴다.

## Behaviour To Preserve
- shared browser pre-warm 동작
- split editor에서 브라우저 재사용
- runtime settings 재전파
- recovery request/complete handshake
- load error / console logging

## Risks
- global state를 너무 많이 끊으면 서비스 간 공유가 깨질 수 있다.
- JCEF dispose 순서를 바꾸면 브라우저 누수가 생길 수 있다.
- recovery state를 분리할 때 filePath/session/leaseId의 일치 조건을 유지해야 한다.

## Verification
- `./gradlew check`
- `./gradlew buildPlugin`
- split editor 열기/닫기
- runtime settings 변경 후 즉시 반영
