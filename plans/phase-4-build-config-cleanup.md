# Phase 4: 빌드/설정 정리

## 목표

빌드 설정에서 하드코딩된 값을 외부화하고, 중복된 경로 계산을 단순화한다.

---

## 4.1 build.gradle.kts 버전명 생성 분리

**파일:** `build.gradle.kts` (21-23줄)

**문제:** 버전명 생성 로직(`LocalDateTime.now().format(...)`)이 `build.gradle.kts`에 인라인으로 포함되어 있음.

**현재 코드:**
```kotlin
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val buildTimestampVersion: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy.MM.dd.HHmmss"))
val resolvedPluginVersion: String = providers.gradleProperty("buildVersion").orNull ?: buildTimestampVersion
version = resolvedPluginVersion
```

**해결:** `gradle.properties`에 기본값 추가하고, `build.gradle.kts`는 단순화

**`gradle.properties` 변경:**
```properties
# Plugin version is generated at build time in `yy.MM.dd.HHmmss` format.
# Optional override for reproducible/manual release builds:
# buildVersion = 26.04.04.061255

# Default fallback timestamp format (used when buildVersion is not set).
# This is a placeholder - the actual value is generated in build.gradle.kts.
defaultBuildVersion = yy.MM.dd.HHmmss
```

**`build.gradle.kts` 변경:**
```kotlin
// Before
val buildTimestampVersion: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy.MM.dd.HHmmss"))
val resolvedPluginVersion: String = providers.gradleProperty("buildVersion").orNull ?: buildTimestampVersion
version = resolvedPluginVersion

// After - import도 단순화
val resolvedPluginVersion: String = providers.gradleProperty("buildVersion").orNull
    ?: java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yy.MM.dd.HHmmss"))
version = resolvedPluginVersion
```

> **참고:** 실제로 큰 변화는 없음. `LocalDateTime` import를 `java.time` 풀 네임으로 변경하여 import 줄을 줄인다. 또는 `gradle.properties`에 주석으로 기본 포맷을 기록만 한다.

---

## 4.2 vite.config.ts 경로 단순화

**파일:** `webview/vite.config.ts` (8줄)

**문제:** `__dirname` 기반 상대 경로 계산이 불필요하게 복잡함.

**현재 코드:**
```typescript
import {resolve, dirname} from "path";
import {fileURLToPath} from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const webviewOutputDir = resolve(__dirname, "../build/webview");
```

**해결:** `path.resolve`에 절대 경로 직접 전달로 단순화

```typescript
// Before
import {resolve, dirname} from "path";
import {fileURLToPath} from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const webviewOutputDir = resolve(__dirname, "../build/webview");

// After
import {resolve} from "path";

const webviewOutputDir = resolve(process.cwd(), "build", "webview");
```

> **참고:** `process.cwd()`는 Vite가 실행되는 디렉토리(`webview/`)를 기준으로 `../build/webview`를 계산하므로 동일한 결과. `@rollup/pluginutils`의 `fileURLToPath` 없이도 작동.

---

## 4.3 gradle.properties buildVersion 기본값 추가 (선택적)

**파일:** `gradle.properties`

**문제:** `buildVersion`이 주석 처리되어 있어 CI에서 재현 가능한 빌드가 어려울 수 있음.

**해결:** 주석 그대로 유지. 개발자는 `-PbuildVersion=26.04.18.120000` 플래그로 오버라이드 가능.

---

## 4.4 plugin.xml order 속성 추가 (선택적)

**파일:** `src/main/resources/META-INF/plugin.xml`

**문제:** 일부 provider/action에 `order="first"` 또는 `order="before|after"` 속성이 누락.

**해결:** 필요시 다음 속성 추가

```xml
<fileEditorProvider implementation="com.algorist.markflow.editor.MarkFlowEditorProvider" order="first"/>
```

> **참고:** 현재 `FileEditorPolicy.HIDE_DEFAULT_EDITOR`를 사용하므로 `order` 속성은 선택적.

---

## 체크리스트

- [ ] `build.gradle.kts` import 단순화
- [ ] `vite.config.ts` 경로 계산 단순화
- [ ] `plugin.xml` order 속성 확인 (선택적)
