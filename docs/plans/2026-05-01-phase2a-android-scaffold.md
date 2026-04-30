# Phase 2a Implementation Plan — Android Scaffold + HTTP Skeleton

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Build a minimal Android app with a foreground service exposing `127.0.0.1:11434/healthz` (returns 200 "ok") and `127.0.0.1:11434/api/version` (returns JSON). Sign-and-install the APK on the S25, verify reachable from `adb shell curl`. **No LiteRT-LM integration in this plan** — that is 2b.

**Architecture:** standalone Gradle project under `android/` in this repo. Kotlin sources for the service + HTTP handler. NanoHTTPD as the embedded HTTP server. APK installs to S25 via `adb install`. Client verification uses `adb shell curl`.

**Tech stack:** OpenJDK 21 (host), Android SDK 35 (target), AGP 8.x, Gradle wrapper, Kotlin 2.x, NanoHTTPD 2.3.1, foreground service type `dataSync` (Android 14+ requires explicit type).

---

## Confirmed Constraints

- Project root: `/Users/setsuna-new/Documents/temux_llm`
- New subdirectory: `android/`
- ANDROID_HOME: `/Users/setsuna-new/development/android-sdk` (already set up)
- Test device for install: S25 (`RFCY71LAFYE`, Android 16)
- Bind: **must** be `127.0.0.1`, never `0.0.0.0`. Verified via NanoHTTPD constructor (`new NanoHTTPD("127.0.0.1", 11434)`).
- Port: 11434 (matches Ollama default for client compat).

---

## Stop conditions

1. If Gradle sync fails because of SDK / NDK / AGP version mismatch — fix versions, do not skip.
2. If `adb install` fails on S25 — debug install path; never disable PackageManager checks.
3. If `curl 127.0.0.1:11434/healthz` returns connection refused — fix bind address, do not proxy externally.
4. If APK launches but the service isn't visible in `dumpsys activity services` — fix manifest declaration / startForeground call.

---

## Task 1: Create `android/` directory + minimal Gradle project

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/local.properties` (gitignored)

**Step 1.1: Settings and root build files** — declare AGP, Kotlin, repos. Define `:app` subproject.

**Step 1.2: `local.properties` with `sdk.dir` pointing to `/Users/setsuna-new/development/android-sdk`.** Append `android/local.properties` to `.gitignore`.

**Step 1.3: Run `gradle wrapper --gradle-version 8.10` to bootstrap `gradlew`.** (Or write the wrapper files manually with curl from `https://services.gradle.org/distributions/gradle-8.10-bin.zip` resolution.) If host has no `gradle` binary, use the manual wrapper init: download `gradle-wrapper.jar` from a known version source.

**Step 1.4: Commit**

```
git add android/settings.gradle.kts android/build.gradle.kts android/gradle.properties android/gradlew* android/gradle/ .gitignore
git commit -m "feat(android): bootstrap Gradle wrapper for phase-2a scaffold"
```

---

## Task 2: `:app` module skeleton with manifest + Kotlin source

**Files:**
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/kotlin/dev/temuxllm/HelloLauncherActivity.kt` (just to give the app an icon entry point)
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/mipmap-*/ic_launcher.png` (default Android Studio icons; OK to use blank/auto-generated for MVP)

**Step 2.1: `app/build.gradle.kts`** — applicationId `dev.temuxllm.service`, minSdk 33, targetSdk 35, namespace `dev.temuxllm`. Dependencies: `androidx.core:core-ktx`, `androidx.appcompat:appcompat`, `org.nanohttpd:nanohttpd:2.3.1`.

**Step 2.2: `AndroidManifest.xml`** — declare:
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>` (Android 14+)
- `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>` (Android 13+)
- `<application>` with one `<activity>` (HelloLauncherActivity, MAIN/LAUNCHER) and one `<service android:name=".LlmService" android:foregroundServiceType="dataSync" android:exported="false"/>`.

**Step 2.3: `HelloLauncherActivity`** — onCreate calls `startForegroundService(Intent(this, LlmService::class.java))`, then finishes. The user never sees an activity; the launcher icon just bootstraps the service.

**Step 2.4: Sync project**

```bash
cd android && ./gradlew :app:tasks --no-daemon
```

Expected: gradle resolves AGP, lists tasks. No actual build yet.

**Step 2.5: Commit**

```
git add android/app/...
git commit -m "feat(android): :app skeleton with manifest and launcher activity"
```

---

## Task 3: `LlmService` — foreground service with notification

**Files:**
- Create: `android/app/src/main/kotlin/dev/temuxllm/LlmService.kt`

**Step 3.1: Implement `LlmService : Service()`**

```kotlin
class LlmService : Service() {
    private val NOTIF_ID = 11434
    private val CHANNEL_ID = "llm-service"
    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "LiteRT-LM service", NotificationManager.IMPORTANCE_LOW
            ))
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiteRT-LM running")
            .setContentText("listening on 127.0.0.1:11434")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        startHttpServer()
    }
    private fun startHttpServer() { /* Task 4 */ }
    override fun onBind(intent: Intent): IBinder? = null
}
```

**Step 3.2: Build APK**

```bash
cd android && ./gradlew :app:assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` exists.

**Step 3.3: Install on S25**

```bash
adb -s RFCY71LAFYE install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`.

**Step 3.4: Launch and verify service runs**

```bash
adb -s RFCY71LAFYE shell am start -n dev.temuxllm.service/dev.temuxllm.HelloLauncherActivity
sleep 2
adb -s RFCY71LAFYE shell dumpsys activity services | grep -A 5 dev.temuxllm
```

Expected: shows `LlmService` running.

**Step 3.5: Commit**

```
git add android/app/src/main/kotlin/dev/temuxllm/LlmService.kt
git commit -m "feat(android): foreground service with notification (no HTTP yet)"
```

---

## Task 4: Embedded NanoHTTPD on `127.0.0.1:11434`

**Files:**
- Modify: `android/app/src/main/kotlin/dev/temuxllm/LlmService.kt`
- Create: `android/app/src/main/kotlin/dev/temuxllm/HttpServer.kt`

**Step 4.1: `HttpServer.kt`**

```kotlin
class HttpServer : NanoHTTPD("127.0.0.1", 11434) {
    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/healthz"     -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok\n")
            "/api/version" -> newFixedLengthResponse(
                Response.Status.OK, "application/json",
                """{"service":"temuxllm","phase":"2a","binary":"litert_lm_main v0.9.0 (not yet wired)"}""" + "\n"
            )
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404\n")
        }
    }
}
```

**Step 4.2: Hook from `LlmService.startHttpServer()`** — instantiate, `start()`, store reference; in `onDestroy` call `stop()`.

**Step 4.3: Re-build and re-install**

```bash
cd android && ./gradlew :app:assembleDebug --no-daemon
adb -s RFCY71LAFYE install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s RFCY71LAFYE shell am start -n dev.temuxllm.service/dev.temuxllm.HelloLauncherActivity
sleep 2
```

**Step 4.4: Verify endpoints from on-device shell**

```bash
adb -s RFCY71LAFYE shell curl -s 127.0.0.1:11434/healthz
adb -s RFCY71LAFYE shell curl -s 127.0.0.1:11434/api/version
```

Expected:
```
ok
{"service":"temuxllm","phase":"2a","binary":"litert_lm_main v0.9.0 (not yet wired)"}
```

**Step 4.5: Verify localhost-only binding**

```bash
adb -s RFCY71LAFYE shell ss -tnlp | grep 11434
```

Expected: line shows `LISTEN 0 ... 127.0.0.1:11434 ...`. **MUST NOT** show `0.0.0.0:11434` or `::*:11434`.

If the wrong address is shown, fix the NanoHTTPD constructor and re-install. Brief constraint #1.

**Step 4.6: Commit**

```
git add android/app/src/main/kotlin/dev/temuxllm/HttpServer.kt android/app/src/main/kotlin/dev/temuxllm/LlmService.kt
git commit -m "feat(android): NanoHTTPD on 127.0.0.1:11434 with /healthz and /api/version"
```

---

## Task 5: Phase 2a wrap-up doc

**Files:**
- Create: `docs/findings-2026-05-01-phase2a.md`

Sections to include:
- What was built (with apk size).
- `dumpsys activity services` excerpt proving service runs.
- `ss -tnlp` excerpt proving 127.0.0.1-only bind.
- `curl /healthz` and `/api/version` actual responses.
- Decision: GO for 2b (model integration) or any blocker found.

```
git add docs/findings-2026-05-01-phase2a.md
git commit -m "docs: phase 2a complete — HTTP skeleton ready for model wiring"
```

---

## Out of scope for 2a (will be 2b)

- Asset bundling of `litert_lm_main` + `.so` + `.litertlm`.
- ProcessBuilder spawning the binary.
- Parsing the binary's stdout for tokens + BenchmarkInfo.
- `/api/generate` endpoint.
- Streaming tokens via NDJSON / SSE.
- Testing from Termux (assumes Termux is installed; S25 doesn't have it).

---

## Risks specific to 2a

| Risk | Mitigation |
|---|---|
| `gradle wrapper` requires existing `gradle` on host | Manually drop in `gradle-wrapper.jar` + `gradle-wrapper.properties` for version 8.10. They are tiny and easy to vendor. |
| Android 14+ foreground-service-type enforcement | `FOREGROUND_SERVICE_DATA_SYNC` permission + matching `<service android:foregroundServiceType="dataSync"/>`. Documented above. |
| POST_NOTIFICATIONS permission denied on first install (Android 13+) | Service still starts even without the permission; the notification just doesn't show. We don't depend on user-visible notification, only on the service running. |
| `adb shell curl` not present on every Android | Both S21+ and S25 ship toybox curl. Will verify on first attempt. |
