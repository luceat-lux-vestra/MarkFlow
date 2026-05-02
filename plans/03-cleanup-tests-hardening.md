# Cleanup And Tests Hardening Plan

## Goal
- 리팩토링 후 깨지기 쉬운 부분을 테스트와 문서로 고정한다.

## Test Targets
- `MarkFlowSettingsService`
  - normalize 값 범위
  - IDE_SYNC theme resolution
  - runtime settings revision 증가
- `MarkFlowEditorState`
  - read/write round-trip
  - invalid/partial element handling
- `MarkFlowFileSupport`
  - markdown extension acceptance
  - fileType name fallback

## Additional Cleanup
- `src/test/kotlin/com/github/luceatluxvestra/markflow/MyPluginTest.kt`의 템플릿 XML 테스트를 MarkFlow 도메인 중심으로 교체한다.
- README와 changelog는 실제 동작 변화가 있을 때만 갱신한다.
- 예제 문서는 smoke test 용도로 유지하되 중복/불필요 샘플은 정리한다.

## Verification Matrix
- unit tests
- `./gradlew check`
- `./gradlew buildPlugin`
- `webview npm run build`

## Exit Criteria
- 신규 기능 추가 시에도 테스트가 경계 역할을 한다.
- 이전보다 파일이 작고, 함수가 더 예측 가능하게 분리되어 있다.
