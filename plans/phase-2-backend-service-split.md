# Phase 2: Backend 서비스 분해

## 목표

`MarkFlowSharedBrowserService.kt` (1088줄)의 단일 파일을 관심사별 서브패키지로 분리하여 단일 책임 원칙을 확보한다.

## 기존 구조

```
src/main/kotlin/com/algorist/markflow/
├── MarkFlowEditor.kt              (226줄)
├── MarkFlowEditorProvider.kt      (44줄)
├── MarkFlowEditorState.kt         (59줄)
├── MarkFlowFileSupport.kt         (17줄)
├── MarkFlowForceRerenderAction.kt (44줄)
├── MarkFlowSettingsConfigurable.kt (284줄)
├── MarkFlowSettingsService.kt     (192줄)
├── MarkFlowSharedBrowserService.kt (1088줄) ← 핵심 분해 대상
├── MarkFlowStartupActivity.kt     (49줄)
└── MyBundle.kt                    (변경 없음)
```

## 목표 구조

```
src/main/kotlin/com/algorist/markflow/
├── editor/
│   ├── MarkFlowEditor.kt
│   ├── MarkFlowEditorProvider.kt
│   ├── MarkFlowEditorState.kt
│   └── MarkFlowFileSupport.kt
│
├── settings/
│   ├── MarkFlowSettingsService.kt
│   └── MarkFlowSettingsConfigurable.kt
│
├── browser/
│   ├── MarkFlowSharedBrowserService.kt (~150줄 - orchestration만)
│   ├── BrowserPool.kt               (~200줄 - browser lease 관리)
│   ├── BrowserLease.kt              (~80줄 - BrowserLease data class)
│   ├── QueryHandler.kt              (~150줄 - JBCefJSQuery setup)
│   ├── JcefHandlers.kt              (~100줄 - CefDisplayHandler, CefLoadHandler)
│   └── RecoveryManager.kt           (~150줄 - leader/follower recovery)
│
└── [플랫]
    ├── MarkFlowStartupActivity.kt
    ├── MarkFlowForceRerenderAction.kt
    └── MyBundle.kt
```

---

## 파일별 상세 계획

### `browser/BrowserLease.kt` (~80줄)

**이관 대상:** `MarkFlowSharedBrowserService.kt`의 중첩 `BrowserLease` data class (12-69줄)

```kotlin
package com.algorist.markflow.browser

import com.algorist.markflow.MarkFlowEditor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import org.cef.CefApp
import org.cef.CefSettings
import java.awt.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class BrowserLease(
    val browser: JBCefBrowser,
    val editor: MarkFlowEditor,
    val acquiredAtMs: Long,
    val projectId: String
) {
    private val isReleased = AtomicBoolean(false)
    private val evictTimer = AppExecutorUtil.getAppScheduledExecutorService().schedule(
        {}, 0, TimeUnit.MILLISECONDS
    )

    val isReleased: Boolean get() = isReleased.get()
    val ageMs: Long get() = System.currentTimeMillis() - acquiredAtMs

    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            LOG.info("MARKFLOW_UI browser:leaseReleased editor=${editor.getFile().path} age=${ageMs}ms")
        }
    }

    fun isExpired(afterMs: Long): Boolean = ageMs > afterMs
}
```

---

### `browser/BrowserPool.kt` (~200줄)

**이관 대상:** `MarkFlowSharedBrowserService.kt`의 브라우저 풀 관리 로직 (71-138줄, 800-900줄)

```kotlin
package com.algorist.markflow.browser

import com.algorist.markflow.MarkFlowEditor
import com.algorist.markflow.MarkFlowSettingsService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.concurrency.AppExecutorUtil
import org.cef.CefApp
import org.cef.CefSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserPool(private val project: Project) {

    private val leases = ConcurrentHashMap<String, BrowserLease>()
    private val availableQueue = java.util.concurrent.ConcurrentLinkedQueue<JBCefBrowser>()
    private val browserCount = AtomicInteger(0)
    private val sequence = AtomicInteger(0)

    fun acquire(editor: MarkFlowEditor): BrowserLease
    fun release(lease: BrowserLease)
    fun evictIdleEditors()
    fun getActiveEditorCount(): Int
    fun shutdown()
}
```

**이관 기능:**
- `acquireEditor()` → `BrowserPool.acquire()`
- `releaseEditor()` → `BrowserPool.release()`
- `evictIdleEditors()` → `BrowserPool.evictIdleEditors()`
- `createBrowserForEditor()` → `BrowserPool.acquire()` 내부
- `browserCount` → `BrowserPool.browserCount`
- `allBrowserLeases` → `BrowserPool.leases`
- `availableBrowsers` → `BrowserPool.availableQueue`

---

### `browser/QueryHandler.kt` (~150줄)

**이관 대상:** `MarkFlowSharedBrowserService.kt`의 JBCefJSQuery 설정 및 action routing (140-250줄, 900-1000줄)

```kotlin
package com.algorist.markflow.browser

import com.algorist.markflow.MarkFlowEditor
import com.algorist.markflow.MarkFlowSharedBrowserService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.cef.callback.CefCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest

class QueryHandler(private val project: Project, private val service: MarkFlowSharedBrowserService) {

    fun setupDisplayHandler(browser: JBCefBrowser)
    fun setupLoadHandler(browser: JBCefBrowser)
    private fun handleCefQuery(request: String, browser: JBCefBrowser, editor: MarkFlowEditor)
}
```

**이관 기능:**
- `setupJcefQueryHandling()` → `QueryHandler.setupDisplayHandler()`, `QueryHandler.setupLoadHandler()`
- `handleCefQuery()` → `QueryHandler.handleCefQuery()`
- `CefDisplayHandlerAdapter` 내부 클래스 → `QueryHandler` 내부 메서드
- `CefLoadHandlerAdapter` 내부 클래스 → `QueryHandler` 내부 메서드
- `onLoadingStateChange` 로직 → `QueryHandler.setupDisplayHandler()`
- `onLoadStart/End` 로직 → `QueryHandler.setupLoadHandler()`
- `onLoadError` 로직 → `QueryHandler.setupLoadHandler()`

---

### `browser/JcefHandlers.kt` (~100줄)

**이관 대상:** `MarkFlowSharedBrowserService.kt`의 CEF 핸들러 관련 로직

```kotlin
package com.algorist.markflow.browser

import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.handler.CefDisplayHandler
import org.cef.handler.CefLoadHandler

class JcefHandlers {
    fun createDisplayHandler(callback: (Int?, String?, String?, Boolean) -> Unit): CefDisplayHandler
    fun createLoadHandler(callback: (JBCefBrowser, Int, String, Boolean) -> Unit): CefLoadHandler
}
```

> **참고:** `QueryHandler.kt`와 `JcefHandlers.kt`의 경계가 모호할 수 있음. `QueryHandler`가 CefDisplayHandler/CefLoadHandler를 직접 구현하고, `JcefHandlers`는 헬퍼 클래스로 남기는 것도 고려한다.

---

### `browser/RecoveryManager.kt` (~150줄)

**이관 대상:** `MarkFlowSharedBrowserService.kt`의 Recovery protocol (252-430줄, 900-1000줄)

```kotlin
package com.algorist.markflow.browser

import com.algorist.markflow.MarkFlowEditor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

class RecoveryManager(private val project: Project) {

    data class RecoveryState(
        val leader: MarkFlowEditor?,
        val followers: List<MarkFlowEditor>,
        val epoch: Int,
        val activeSessionId: String?
    )

    fun assignLeader(editor: MarkFlowEditor, sessionId: String): RecoveryState
    fun registerFollower(editor: MarkFlowEditor, sessionId: String): RecoveryState
    fun removeEditor(editor: MarkFlowEditor): RecoveryState?
    fun getRecoveryState(): RecoveryState?
    fun notifyLeaderComplete(epoch: Int, reason: String)
    fun notifyLeaderFailed(epoch: Int, reason: String)
}
```

**이관 기능:**
- `activeLeader` → `RecoveryManager.RecoveryState.leader`
- `activeFollowers` → `RecoveryManager.RecoveryState.followers`
- `recoveryEpoch` → `RecoveryManager.RecoveryState.epoch`
- `assignLeader()` → `RecoveryManager.assignLeader()`
- `registerFollower()` → `RecoveryManager.registerFollower()`
- `removeFollower()` → `RecoveryManager.removeEditor()`
- `notifyLeaderComplete()` → `RecoveryManager.notifyLeaderComplete()`
- `notifyLeaderFailed()` → `RecoveryManager.notifyLeaderFailed()`
- `notifyFollowerComplete()` → `RecoveryManager` 내부
- `notifyFollowerFailed()` → `RecoveryManager` 내부
- `isRecoveryInProgress()` → `RecoveryManager.getRecoveryState()`

---

### `browser/MarkFlowSharedBrowserService.kt` (~150줄)

**남을 코드:** orchestration만

```kotlin
package com.algorist.markflow.browser

import com.algorist.markflow.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import java.awt.Component
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)   // 🔴 계획서 수정: Service.ServiceLevel → Service.Level (IntelliJ API)
class MarkFlowSharedBrowserService(private val project: Project) {

    private val browserPool = BrowserPool(project)
    private val recoveryManager = RecoveryManager(project)
    private val queryHandler = QueryHandler(project, this)

    fun registerEditor(editor: MarkFlowEditor)
    fun unregisterEditor(editor: MarkFlowEditor)
    fun attach(editor: MarkFlowEditor, panel: Component)
    fun detach(editor: MarkFlowEditor, panel: Component)
    fun setEditorActive(editor: MarkFlowEditor, active: Boolean)
    fun pushMarkdownFromEditor(editor: MarkFlowEditor, content: String)
    fun executeForEditor(editor: MarkFlowEditor, script: String): Boolean
    fun reapplyRuntimeSettingsForEditor(editor: MarkFlowEditor, forceReload: Boolean)
    fun forceRerender(editor: MarkFlowEditor)
    fun preWarm()
    fun shutdown()

    companion object {
        fun getInstance(project: Project): MarkFlowSharedBrowserService
        fun notifyRuntimeSettingsChanged(forceReload: Boolean)
    }
}
```

---

## 단계별 실행 순서

1. `browser/BrowserLease.kt` 생성 - 중첩 `BrowserLease` data class 이관
2. `browser/BrowserPool.kt` 생성 - 브라우저 풀 관리 로직 이관
3. `browser/QueryHandler.kt` 생성 - JBCefJSQuery setup, action routing 이관
4. `browser/JcefHandlers.kt` 생성 - CefDisplayHandler/CefLoadHandler 이관
5. `browser/RecoveryManager.kt` 생성 - Recovery protocol 이관
6. `browser/MarkFlowSharedBrowserService.kt` 리팩토링 - orchestration만 남기고 이관된 코드 삭제
7. `MarkFlowEditor.kt` → `editor/MarkFlowEditor.kt` 이동
8. `MarkFlowEditorProvider.kt` → `editor/MarkFlowEditorProvider.kt` 이동
9. `MarkFlowEditorState.kt` → `editor/MarkFlowEditorState.kt` 이동
10. `MarkFlowFileSupport.kt` → `editor/MarkFlowFileSupport.kt` 이동
11. `MarkFlowSettingsService.kt` → `settings/MarkFlowSettingsService.kt` 이동
12. `MarkFlowSettingsConfigurable.kt` → `settings/MarkFlowSettingsConfigurable.kt` 이동
13. 모든 import 경로 업데이트
14. 빌드 테스트

---

## 주의사항

- `MarkFlowSharedBrowserService.notifyRuntimeSettingsChanged()`는 companion object이므로 `browser/` 패키지에 남겨두거나 `MarkFlowSettingsService`에서 직접 호출하도록 변경한다.
- `MarkFlowStartupActivity.kt`에서 `project.getService(MarkFlowSharedBrowserService::class.java)` 호출 시 package import 경로 변경 필요.
- `MarkFlowEditor.kt`에서 `sharedBrowserService` 참조 시 package import 경로 변경 필요.
- `MarkFlowEditorProvider.kt`에서 `MarkFlowFileSupport.EDITOR_TYPE_ID` 참조 시 package import 경로 변경 필요.
- `MarkFlowForceRerenderAction.kt`에서 `MarkFlowEditor` 참조 시 package import 경로 변경 필요.
- `MarkFlowStartupActivity.kt`에서 `MarkFlowFileSupport.isMarkFlowTarget()` 참조 시 package import 경로 변경 필요.
