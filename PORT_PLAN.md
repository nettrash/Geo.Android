# Geo Android: Port Plan from iOS

Source of truth: `/Users/nettrash/Develop/nettrash.me/Geo/` (iOS).
Target: `/Users/nettrash/Develop/nettrash.me/Geo.Android/` (this repo).
Scope (per your request): main app + widget + Watch app + tests. Feature parity + UI/UX polish.

Mountains JSON (`list.json`) is already byte-identical — md5 `87349923d30110d29ac510050a36fd52` — so the data layer needs no porting.

---

## Summary of gaps

Working through every iOS file and comparing to its Android counterpart, the gaps cluster into seven areas. Numbers in brackets show the rough effort weight (S/M/L).

1. **Wear OS module — entirely missing.** [L]
2. **AR Nature view is shallow vs iOS.** [L]
3. **Widget is less capable than iOS.** [M]
4. **Permissions UX (banner + pre-AR copy).** [S]
5. **Localization is almost empty.** [S]
6. **Persistence / cloud sync gap.** [M, partly optional]
7. **Tests, logging, and app-lifecycle plumbing.** [S]

The plan that follows enumerates every gap I'd close, the files I'd touch, and the order I'd do it in.

---

## 1. Wear OS module (was: iOS Geo Watch App + Watch Widget)

Current state: Android has no Wear module at all. iOS has a full watchOS app + four complication families.

### 1.1 New Gradle module `:wear`
- `settings.gradle.kts`: add `include(":wear")`.
- `wear/build.gradle.kts`: Wear OS application module, `minSdk 28`, Compose for Wear (`androidx.wear.compose`), `play-services-wearable`, Hilt, kotlinx-serialization, `androidx.wear.tiles` (Tiles ≈ iOS complications).
- `wear/src/main/AndroidManifest.xml`: declares `<uses-feature android:name="android.hardware.type.watch" />`, standalone=true meta-data, `WearableListenerService` for incoming data, Tile services.
- `gradle/libs.versions.toml`: add wear-compose, wear-tiles, play-services-wearable.

### 1.2 Watch app source — mirror iOS Watch App
| iOS file | Android (Wear) target |
|---|---|
| `GeoApp.swift` + `GeoWatchAppDelegate.swift` | `WearGeoApplication.kt`, `WearMainActivity.kt`, `WearBarometerManager.kt` |
| `ContentView.swift` (barometer + altitude readings, 20-sample rolling history) | `WearGeoScreen.kt` (Wear Compose) — vertical rotated labels, monospaced numbers, 4 decimals for pressure, 0 decimals for altitude |
| Graph/* (GraphView, AxisShape, DataSetShape, DataItem, etc.) | `wear/ui/graph/*.kt` — Compose Canvas equivalents (the existing phone `GeoGraphView` is too large for Wear; need a slimmer 56-sp variant) |
| `WatchConnectivityManager.swift` | `WatchConnectivityClient.kt` using `Wearable.getDataClient(...)` + `MessageClient`. Publishes phone `InformationToken` on the `/geo/snapshot` path and listens for inbound barometer messages from watch |

### 1.3 Tiles (≈ iOS complication families)
iOS ships 4 complication families (Corner, Circular, Inline, Rectangular). Wear OS calls these Tiles. Map each iOS complication 1:1 to a Tile service:
- `AltitudeTileService` (single tile, layout matches `AltitudeRectangularView` — title, altitude with gauge, pressure).
- `AltitudeShortTileService` (compact, ≈ `AltitudeCircularView`).
Both backed by `WatchBarometerRepository` which mirrors iOS `GeoProvider` (read live `Sensor.TYPE_PRESSURE` once with 5 s timeout, fall back to stored snapshot, write to DataStore, refresh policy 5 min).

### 1.4 Wear → phone bridge on the phone side
- Add `me.nettrash.geo.connectivity.WearConnectivityService` (extends `WearableListenerService`).
- On inbound `/geo/snapshot` data event, parse `InformationToken`, hand to `HistoryRepository.insert(...)` (so a Wear sample backfills the phone DB exactly like iOS `PhoneConnectivityManager.handleInbound`).
- Outbound: extend `GeoViewModel.updateWidget()` to also call `WearMessageBridge.send(token)` so the phone GPS snapshot reaches the watch.
- Manifest registration in the **phone** module.

### 1.5 Files to add (phone module)
- `Geo/src/main/java/me/nettrash/geo/connectivity/WearConnectivityService.kt`
- `Geo/src/main/java/me/nettrash/geo/connectivity/WearMessageBridge.kt` (DI singleton)
- Manifest receiver entry.

### 1.6 Watch tests
iOS Watch test stubs are empty — no functional behaviour to port. Add a small Robolectric/JUnit test for the altitude formula on the watch side so the Wear module has at least one verifying test.

---

## 2. AR Nature view — close the gap with iOS

iOS pipeline is far more sophisticated than Android. Android currently bearing-windows a `Box.padding(start=..., top=...)` overlay; iOS projects every point through the AR camera's view+projection matrix and runs occlusion against LiDAR mesh / planes.

### 2.1 Pixel-accurate AR projection (was iOS `ARSessionManager` + `PeakOverlayView`)
Add:
- `me/nettrash/geo/ar/ArSceneController.kt` — wraps `ARSceneView`, exposes per-frame `viewMatrix`, `projectionMatrix`, `cameraTransform`, `viewportSize`, `isTracking` as `StateFlow`s. Mirrors `ARSessionManager`.
- `me/nettrash/geo/ar/ArProjection.kt` — `projectWorld(world: FloatArray3): Offset?` equivalent of `ARSessionManager.projectToScreen`. Pure math, fully testable.
- `me/nettrash/geo/ar/GeoToWorld.kt` — `gpsToEnu(...)` is already in `GeoCalculations`; add the camera-origin offset trick from iOS PeakOverlayView (anchor world points to the camera's current position, not the AR session origin).

Replace the inside of `NatureScreen.PeakOverlay` with a Canvas/`Box` that calls `ArProjection.projectWorld` and positions each marker absolutely via `Modifier.offset`. Add the same opacity/scale fade-by-distance, plus stable IDs so SwiftUI/Compose doesn't blow markers away on every 5-second refresh (Android's `NearbyPeak.create` already uses stable UUIDs — good — but the iOS-style merge-by-ID hysteresis needs to be ported into `PeakFinder`).

### 2.2 Terrain-aware horizon (was iOS `HorizonOverlayView` + `SkylineCalculator` + `TerrainElevationService`)
Add three files:
- `me/nettrash/geo/ar/TerrainElevationService.kt` — Kotlin actor-ish object (use `Mutex`-guarded `HashMap<String, Double>` cache, ~110 m grid quantisation), backed by Open-Elevation REST API via OkHttp. POST batches of 100 points; 8 s timeout.
- `me/nettrash/geo/ar/SkylineCalculator.kt` — same algorithm as iOS (180 bearing rays × 13 log-spaced distances, pick max apparent-altitude-angle sample per bearing). Coroutine + cancellation safe. Exposes `samples: StateFlow<List<SkylineSample>>` and `isComputing`.
- `me/nettrash/geo/ar/HorizonOverlay.kt` — Composable that consumes `ArSceneController` + `SkylineCalculator.samples` + barometer altitude, draws skyline polyline + cardinal labels via Compose Canvas. Falls back to geometric horizon when samples list is empty.

`Geometry.swift` helpers to port into `GeoCalculations`:
- `horizonDistance(observerAltitude)`
- `project(from, bearing, distance)` (great-circle forward projection)
- `apparentAltitudeAngle(observer, target, distance)`

### 2.3 Occlusion (was iOS `AROcclusionManager`)
Add `me/nettrash/geo/ar/ArOcclusionManager.kt`:
- Subscribes to ARCore depth API (`Frame.acquireDepthImage16Bits()`) on supported devices.
- For each tracked plane/depth-pixel: returns occluded IDs (Set<UUID>) for points behind the surface.
- Confidence counter (2 consecutive hits to flip, drops to 0 to clear) — direct port of iOS `occlusionThreshold` behaviour.
- Outdoor heuristic (location horizontal-accuracy > 25 m **or** ≥3 distant peaks) → skip plane occlusion.
- Center-of-screen depth display ("LiDAR" / "Depth" badge in top bar).

### 2.4 NatureScreen polish
- Active-when-visible gate: only run periodic peak/history refresh + occlusion loop when `lifecycle.currentState == RESUMED && Nature tab selected` (today's loop runs whenever camera permission is granted, draining battery on background tabs).
- Distance source badge (LiDAR / Depth / Plane / —) like iOS top bar.
- "Scanning" pulse indicator while scene is initialising.
- "Skyline" pulse indicator while SkylineCalculator is computing.
- Crosshair distance label.
- Heading icon rotation already done — keep.
- Pre-AR copy: align with iOS wording ("About the Nature view" / "The Nature view shows nearby mountain peaks overlaid on the camera. Continue to choose whether to grant camera access." / on second denial swap button to "Open Settings" deep-link via `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`).

### 2.5 Peak source parity
iOS searches Apple Maps (`MKLocalSearch`, "mountain peak", returns POIs) **in parallel with** OSM Overpass. Android only uses OSM. To match the iOS peak density without adding a Google Places billing surface, instead:
- Keep OSM as primary.
- Also accept results from any other peak-tagged Overpass element type (`way["natural"="peak"]`, `relation["natural"="peak"]`) to widen the net.
- Implement the same TTL-based eviction + hysteresis from iOS PeakFinder (`peakTTL = 60 * 60`, `dropRadius = 2 * searchRadius`, `maxRetainedPeaks = 200`).
- Recompute distance/bearing for surviving peaks against the new user location on each refresh (Android currently rebuilds the list, so distances are always correct but markers flash when the OSM endpoint returns nothing).

(If you want literal MKLocalSearch parity we'd have to wire Google Places, which costs money — flag if you want that.)

---

## 3. Widget

iOS widget is configurable (toggles for showing GPS / barometer), has its own `AppIntent`, and reads/writes the shared `InformationToken` model. Android widget is fixed.

### 3.1 Widget configuration (Glance has an answer)
- Add `ConfigureWidgetActivity` invoked via `appwidget-provider/@android:configure`. Two checkboxes: "Show GPS" / "Show Barometer".
- Persist via `androidx.glance.appwidget.state.GlanceStateDefinition` keyed by widget ID.
- `GeoWidget.WidgetContent` reads config + hides the section it shouldn't show — matches iOS `WidgetEntryView` conditional rendering.

### 3.2 Make the worker actually run
Today, `BarometerRefreshWorker.schedule(...)` exists but is never called. Fix:
- Call `BarometerRefreshWorker.schedule(context)` from `GeoApplication.onCreate()`.
- Also nudge from `GeoViewModel.onCleared()` so it's enqueued if the user has never opened the app from cold-boot since reboot.

### 3.3 Snapshot model unification
- Move `InformationToken` from `data/model` into a `core/` package shared by widget, worker, and (later) wear connectivity client.
- Rename `WidgetDataStore` to `SharedSnapshotStore` to match iOS, expose `read()`, `write(token)`, plus a ring buffer (`readBuffer`/`writeBuffer`/`clearBuffer`, capacity 12) so background-captured snapshots can be backfilled into Room on the next app launch — direct port of iOS `SharedSnapshotStore`.

### 3.4 Backfill on launch
- Add `GeoViewModel.restoreFromSharedStorage()` called from `init {}`:
  - Re-hydrate `BarometerManager` pressure/height/everest if the live sensor hasn't produced a sample yet.
  - Drain `SharedSnapshotStore.readBuffer()` into Room via `HistoryRepository.insert(...)`, dedup by `recordDate`, then `clearBuffer()`.
- Direct port of iOS `GeoAppDelegate.restoreFromSharedStorage()`.

---

## 4. Permissions UX

### 4.1 Permissions banner (was iOS `PermissionsBanner` + `PermissionsMonitor`)
- Add `me/nettrash/geo/permissions/PermissionsMonitor.kt` (Hilt singleton, `StateFlow<Boolean> locationDenied / motionDenied / hasAnyDenial`).
- Re-check on `Lifecycle.Event.ON_RESUME` (mirrors iOS's `.onAppear` + scenePhase re-check).
- Add `PermissionsBanner.kt` composable above the tab bar (in `MainScreen.Scaffold`), tap → `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.
- Strings localized; mirror iOS wording exactly:
  - "Location & motion access required" / "Location access required" / "Motion access required"
  - "Tap to enable Location and Motion in Settings." etc.

### 4.2 Pre-AR view wording
- Replace "Camera Access Required" with "About the Nature view".
- Replace the static button with `Continue` when status is undetermined, `Open Settings` when permanently denied (use `shouldShowRequestPermissionRationale` to distinguish).

### 4.3 Background location
The Android manifest declares `ACCESS_BACKGROUND_LOCATION` but no UI requests it, and iOS only asks for WhenInUse. Drop `ACCESS_BACKGROUND_LOCATION` from the manifest to align with iOS data-minimisation posture and avoid a Play review flag (background location requires a separate review).

---

## 5. Localization (was iOS `en.lproj/Localizable.strings`)

iOS has 30+ strings; Android has 2.

- Move every English string in current Kotlin files into `res/values/strings.xml` (tab labels, watermarks, "Pressure", "Altitude", "Coordinates", "Velocity", "Directions", "Show on Map", "Name", "Distance", "% Everest", "TRACKING", "STATISTICS", "BAROMETER", "SATELLITE", "CLOSEST MOUNTAIN", "HIGHEST MOUNTAIN", "Continue", "Open Settings", "About the Nature view", AR explanatory copy, history-detail labels, etc.).
- Mirror iOS keys 1:1 so the two projects share a translation memory.
- Add `res/values-ru/strings.xml` with Russian copies (Play release-notes already ship in ru-RU, so a Russian-speaking audience exists).
- Add `res/values-en-rGB/strings.xml` (currently identical to en-US, but the file is required for the play release-notes locale to "feel" complete).

---

## 6. Persistence + cloud sync

### 6.1 Schema parity
iOS CoreData `HistoryItem` has the same 7 fields as Android Room `HistoryItem` — already matched. No schema changes needed.

### 6.2 Cloud sync (optional)
iOS uses CloudKit for free cross-device history sync. Android has no equivalent.

Two options, both deferrable:

- **Skip** — declare cloud sync as iPhone-only. Cheap, lowest risk.
- **Add Drive AppData scoping** — periodic JSON dump of `HistoryItem` rows to the user's hidden Google Drive `appDataFolder`. Sketch only — would need ~300 LOC + OAuth flow. **Recommend deferring** unless you tell me otherwise.

### 6.3 History.refreshIfNeeded() dirty-flag pattern
iOS `History` has `isDirty` + `markDirty()` + `refreshIfNeeded()` so heavy CoreData fetches don't fire on every tab switch. Android `GeoViewModel.refreshHistory()` rebuilds three datasets on every history insert. Port the dirty flag → add a `HistoryRepository.isDirty: StateFlow<Boolean>` and only rebuild datasets when the flag is set.

---

## 7. Misc plumbing

### 7.1 `AppLog` (iOS `os.Logger`)
Add `me/nettrash/geo/util/AppLog.kt` — wraps `android.util.Log` with a uniform set of tags (`app`, `location`, `barometer`, `widget`, `connectivity`, `ar`, `history`, `background`). Then replace `e.printStackTrace()` / `println(...)` calls (PeakFinder, BarometerRefreshWorker, etc.).

### 7.2 Scene-phase widget pushes
iOS pushes data to the widget on `applicationWillResignActive`. Android equivalent: subscribe `ProcessLifecycleOwner.get().lifecycle` in `GeoApplication`, on `ON_STOP` call `GeoViewModel.updateWidget()` via a `Hilt`-injected `WidgetUpdater` singleton (or move the logic out of the ViewModel entirely into a `WidgetUpdater` service so it's not tied to ViewModel scope).

### 7.3 Stable-ID merge in `loadARHistoryPoints`
Today `_arHistoryPoints.value = items.mapNotNull { … }` — a transient empty fetch wipes everything. Port iOS hysteresis: merge with previous list, keep points until distance > `1.5 * maxDistance`.

### 7.4 Widget colors / icon parity
- Widget `pressure` label is missing the empty-key row trick — fix the `DataRow(label = "", value = ...)` for the second pressure line so it aligns properly.
- The mountain background image used by the widget (`R.drawable.widget_background`) should be regenerated from iOS Assets if there's a newer version.

### 7.5 Tests
- Port `GeometryTests.swift` shape (currently absent on iOS but the helpers are heavily used) — already present on Android as `GeoCalculationsTest.kt`. Extend with the three new helpers (`horizonDistance`, `project`, `apparentAltitudeAngle`).
- Add `SharedSnapshotStoreTest.kt` (ring buffer dedup, capacity cap, encode/decode round-trip).
- Add `PeakFinderMergeTest.kt` for the merge-by-ID / TTL / hysteresis logic.
- Skip Watch UI tests — iOS counterparts are empty stubs.

---

## Proposed execution order

I'd ship this in 5 phases. Each phase is independently testable / shippable.

| Phase | Scope | Effort |
|---|---|---|
| **A. Foundation** | AppLog, PermissionsMonitor/Banner, drop background-location permission, lifecycle-driven widget push, BarometerRefreshWorker scheduled at startup | ~1 day |
| **B. Snapshot unification + Widget config** | Rename WidgetDataStore → SharedSnapshotStore, add ring buffer, restoreFromSharedStorage on launch, Glance widget configuration activity, conditional sections | ~1 day |
| **C. AR depth pass 1** | ArSceneController + GPS→world→screen projection, replace bearing-window Box with proper projection, merge-by-ID + hysteresis in PeakFinder, active-when-visible gating, NatureScreen polish (Continue/Open Settings, badges) | ~2 days |
| **D. AR depth pass 2** | TerrainElevationService, SkylineCalculator, HorizonOverlay; ArOcclusionManager with depth API + planes + outdoor heuristic | ~2 days |
| **E. Wear module** | New `:wear` Gradle module, Watch app screen + graph, two Tile services, WearConnectivityService on phone, WearMessageBridge for outbound GPS snapshots, two Wear unit tests | ~3 days |
| **L. Localization sweep** | Pull all strings into `strings.xml`, add ru-RU + en-rGB, translate copy | ~half day |

Localization can run in parallel; tests and AppLog are folded into each phase as new code lands.

---

## Open questions before I start

1. **Wear module — proceed?** It is the biggest single chunk. If you want to defer, I can ship phases A–D first and you can decide on E later.
2. **Cloud sync for history — skip or Drive AppData?** Default is skip.
3. **Apple Maps parity for peaks — skip (recommend) or wire Google Places (cost)?**
4. **Russian translation — should I auto-translate strings.xml or leave the ru-RU file as English placeholders for you to translate?**

Once you confirm scope I'll start with phase A and check in after each phase before moving to the next.
