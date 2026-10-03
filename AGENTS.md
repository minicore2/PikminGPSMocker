# AGENTS.md - PikminGPSMocker (Android GPS Mock + Health Connect Steps)

## Project overview
Android GPS mocker that injects mock locations via `LocationManager.addTestProvider` and writes steps to Health Connect based on distance traveled (0.4 m/step). Key focus: GPS route simulation + step tracking via Health Connect.

## Tech stack
- Android (Kotlin 1.9.22), minSdk 34, targetSdk 34, compileSdk 36
- Gradle 8.13, AGP 8.9.1, KSP 1.9.22-1.0.17
- OSMDroid 6.1.18, Health Connect 1.1.0-rc01, Room 2.6.1, Coroutines 1.7.3

## Build & verification
This repo requires JDK 17. Gradle wrapper may be missing in this copy; if `./gradlew` doesn't exist, copy from a nearby sample or use system gradle. From repo root:

```bash
export JAVA_HOME=/usr/local/mcuxpressoide-25.6.136/ide/plugins/org.eclipse.justj.openjdk.hotspot.jre.full.linux.x86_64_17.0.9.v20231028-0858/jre
export PATH=$JAVA_HOME/bin:$PATH
cd /home/ckhsu/Work/android/claude.ai/GpsMockerV20.build
./gradlew --version
./gradlew assembleDebug
./gradlew testDebugUnitTest  # currently no unit tests; succeeds with NO-SOURCE
./gradlew lintDebug         # Android lint
```

No tests exist in the repo (no `app/src/test` or `app/src/androidTest`). `testDebugUnitTest` runs with NO-SOURCE and exits successfully.

## 強制編譯驗證（Mandatory compile gate）
- **修改完成前不可結束任務。** 所有程式碼變更（無論大小）在回報完成前，**必須**通過編譯驗證。
- **驗證指令：** `./gradlew assembleDebug`（建議作為最終驗證）。必要時也可先確認 `./gradlew compileDebugKotlin compileDebugJavaWithJavac`。
- **通過條件：** 必須顯示 `BUILD SUCCESSFUL`，且沒有新的編譯錯誤。**不得**在有編譯錯誤的情況下結束任務。
- **失敗處理：** 編譯失敗時，必須修正錯誤並重新驗證，直到通過為止。
- **Android Studio 路徑：** `/home/ckhsu/Work/android-studio`（環境參考，不影響 `./gradlew` 執行）。

## Architecture & key entrypoints
- `app/src/main/java/com/devtool/gpsmocker/`
  - `service/MockLocationService.kt` — core GPS injection (foreground service). Injects every `INTERVAL_MS=250ms`. Route simulation uses linear interpolation with Haversine, accumulates distance/steps (`METRES_PER_STEP=0.4`). Holds position at destination when not looping.
  - `ui/MainActivity.kt` — hosts ViewPager2 with 3 tabs (Map/Stats/Settings), keeps all fragments alive (`offscreenPageLimit=2`). Requests coarse/fine location permissions on start.
  - `ui/fragments/MapFragment.kt` — OSMDroid map, handles fixed-point vs multi-waypoint route, search (geocoding via `GeocoderHelper`), landmark features, binds to `MockLocationService` (lifecycle bound at fragment level to survive tab switches). Posts UI updates from service callbacks via main `Handler`.
  - `ui/fragments/StatsFragment.kt` — observes `SharedViewModel` for today/session steps, backend label, running state, coords, distance.
  - `ui/fragments/SettingsFragment.kt` — speed slider, start-from position (NONE/LAST_POSITION/DEVICE_GPS), step backend toggle (Health Connect/Local), HC connect, landmark import/export/fetch, DB management.
  - `ui/SharedViewModel.kt` — central state; batches step writes (flush every 20 steps) with time window (`windowStart/windowEnd`). Calls `StepManager.addSteps()` on flush; `flushSteps()` called on route finish.
  - `ui/PermissionRationaleActivity.kt` — Health Connect rationale activity (required by HC policy), declared in manifest with `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`.
- `utils/StepManager.kt` — active backend selection (HEALTH_CONNECT or LOCAL), prefers HC if available+granted, falls back to LOCAL on read/write failures, always persists to `LocalStepStore` first.
- `utils/HealthConnectHelper.kt` — HC client wrapper. Uses `Device(type=Device.TYPE_PHONE, manufacturer/model)` (not TYPE_UNKNOWN) and ensures non-zero interval for `StepsRecord`. Checks SDK availability (requires SDK_AVAILABLE).
- `utils/LocalStepStore.kt`, `LastPositionStore.kt`, `AppPrefs.kt` — local prefs/storage.
- `db/` + `Room` (KSP) — landmark storage (`WikiLandmarkHelper`).
- `AndroidManifest.xml` — declares `FOREGROUND_SERVICE_LOCATION`, `ACCESS_MOCK_LOCATION`, HC permissions (`READ_STEPS/WRITE_STEPS`), queries Health Connect package, registers `PermissionRationaleActivity` and `MockLocationService`.

## Critical behaviors & gotchas (must not miss)
- **Mock location provider**: Service uses `LocationManager.GPS_PROVIDER` as test provider (`setupTestProvider()` adds/removes provider). App must be selected as "Mock location app" in Developer Options (documented in README).
- **Step distance**: `MockLocationService.METRES_PER_STEP = 0.4`. Each tick adds `stepM / METRES_PER_STEP` to accumulator; integer part becomes `newSteps` (floored). Speed affects tick distance: `stepM = speedMps * (INTERVAL_MS/1000.0)`.
- **Timing**: `INTERVAL_MS=250L` (0.25s). At 1.5 m/s → ~0.375m/tick → ~0.9375 steps/tick on average (accumulates over ticks).
- **Service lifecycle**: Bound at `MapFragment` level (in `onCreate`/`onDestroy` of Fragment) so tab switches don't unbind; callbacks (`onLocationUpdate`, `onRouteFinished`) set by MapFragment and cleared on stop. Coroutine uses `Dispatchers.Default + SupervisorJob`; UI dispatch via `Handler(Looper.getMainLooper())` in MapFragment.
- **Health Connect write**: `HealthConnectHelper.writeSteps()` creates `StepsRecord` with real device metadata (`Device.TYPE_PHONE`). Ensures interval is ≥1s (adjusts `safeStart` if needed). HC rejects zero-length intervals.
- **Step batching**: `SharedViewModel` batches writes - flushes every `WRITE_EVERY=20` steps or on `onRouteFinished()` via `flushSteps()`. Time window (`windowStart/windowEnd`) spans the batched ticks.
- **Backend fallback**: `StepManager` always writes locally first; if HC write fails while active, downgrades to LOCAL. On HC read failure, also downgrades to LOCAL.
- **ViewPager2**: `offscreenPageLimit=2` keeps all 3 fragments alive (prevents service unbind/NPE when switching tabs).
- **Map init**: `MapFragment.jumpToInitialPosition()` respects `AppPrefs.StartFrom` (NONE/LAST_POSITION/DEVICE_GPS). DEVICE_GPS tries cached last-known first, otherwise requests single live fix via `requestSingleUpdate` (deprecated API, see warnings).
- **Location permissions**: `MainActivity` requests `ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION`. Mock location requires app to be selected as mock provider; `ACCESS_MOCK_LOCATION` is declared.
- **Room + KSP**: Landmark DB via Room with KSP. Migrations not present (simple schema).

## Commands to know (exact)
Run from repo root with JAVA_HOME set (see above). No custom npm/gradle tasks beyond standard Android tasks. Build is standard.

```bash
# Assemble debug
./gradlew assembleDebug

# Unit tests (none exist)
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug

# Install debug APK
./gradlew installDebug
```

## Conventions / style
- Kotlin, official Kotlin code style (as per `gradle.properties`).
- ViewBinding used throughout (`buildFeatures { viewBinding true }`).
- Coroutines for async work; Default dispatcher for service simulation, IO for storage/HC.
- Logging via `android.util.Log` with simple TAGs.
- Chinese UI strings in many places (this is a Taiwanese/Chinese-localized app).
- No existing tests; when adding tests, put under `app/src/test/` (unit) or `app/src/androidTest/` (instrumentation).

## Files that contain repo-specific "must know"
- `service/MockLocationService.kt` — simulation math, step accumulation, provider setup.
- `utils/HealthConnectHelper.kt` — HC device metadata + interval safety (critical for Fit sync).
- `ui/SharedViewModel.kt` — batching semantics (WRITE_EVERY, window timing).
- `ui/fragments/MapFragment.kt` — service binding strategy, map init logic.
- `AndroidManifest.xml` — HC rationale + foreground service type.
- `app/build.gradle`, `build.gradle`, `gradle.properties` — toolchain versions (AGP 8.9.1 needs Gradle 8.13).

## Things to avoid
- Don't change `METRES_PER_STEP` or `INTERVAL_MS` without understanding step accumulation logic (accumulator uses fractional steps).
- Don't use `Device.TYPE_UNKNOWN` for HC writes — it breaks Fit sync (app already sets `TYPE_PHONE`).
- Don't unbind MockLocationService in `MapFragment.onDestroyView()` — it must stay bound across tab switches (binding is in `onCreate()`/`onDestroy()`).
- Don't batch-complete step writes differently without preserving time windows (affects HC record fidelity).
- Don't assume `./gradlew` exists in this checkout — may need to restore wrapper files if missing.

If making changes that affect GPS injection, Health Connect writes, or step accumulation, verify behavior compiles with the above toolchain. The repo has no automated tests to guard these behaviors.