# Changelog — Geo.Android (Android + Wear)

All notable changes to Geo.Android are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/). Entries are tagged with the
Play `versionCode`; note that `versionCode` auto-increments on every
assemble/bundle, so the value actually uploaded to Play may be higher than the
one recorded here.

## [Unreleased]

## [1.2] — 2026-07-15

A Nature-view rebuild focused on one thing — naming the peaks you can see —
plus a Stats-tab tracking fix.

### Changed
- **The Nature (AR) view is now built around identifying the peaks you can
  see, and nothing else.** The modelled terrain "skyline" silhouette from 1.1
  was removed: on camera it rarely lined up with the real ridge, which made
  the view feel unfinished. In its place the camera shows a clean geometric
  horizon with N / NE / E / SE / S / SW / W / NW compass markers, and every
  nearby summit gets its own label — clearer, and far more reliable at
  pointing you at the right mountain. The extra AR machinery that served the
  skyline (occlusion, scanning, depth) is gone too, which is easier on the
  battery.
- **New peak labels.** Each peak is marked by a thin line rising from its
  exact summit to a small tilted card with the mountain's name and altitude,
  so the label points at the peak without covering it.

### Added
- **Only the peaks you can actually see.** Summits hidden below your horizon
  (behind the curve of the Earth from where you're standing) are no longer
  labelled, so the view isn't cluttered with peaks you couldn't possibly see.
- **Minimum-altitude filter.** A slider on the edge of the Nature view raises
  the floor — hide the small hills and keep only the big mountains. Your
  setting is remembered for next time.
- **Capture & share.** A shutter button takes a photo of the camera view with
  the peak labels drawn on, ready to share or save.
- **Peaks work offline.** Download an area (**Info tab → "Manage offline
  areas"**) and its named peaks appear in the camera with no signal, out to
  ~80 km — so distant summits still get named on a no-signal ridge. Packs are
  now peaks-only, so they're smaller and quicker to download; packs saved by
  an earlier version keep working.
- **Drag to align the view.** The phone compass is often several degrees off,
  which slides the whole overlay sideways. Drag horizontally across the
  Nature view to nudge the horizon, compass markers and peak labels onto the
  real mountains — everything moves together. A small chip shows the current
  adjustment ("Alignment +4°"); tap it to reset. Lasts for the session.

### Fixed
- **The altitude graph keeps tracking when GPS drops.** On the Stats tab, the
  barometer trace on the live tracking graph now keeps going when GPS is
  unavailable (indoors, or before the first fix) instead of freezing; the GPS
  trace simply pauses until the signal returns, and the graph fills in from
  the right on a fresh start.

## [1.1] — 2026-06-18

A correctness, accuracy and reliability release with a major Nature/AR upgrade:
a critical first-run fix, a hard-crash fix, calibrated altitude that agrees
across phone/widget/Watch, and a Nature (AR) view that gains offline expedition
packs, peak names welded to the terrain skyline, a summit log and
tap-to-identify — plus dozens of verified bug fixes and improvements.

### Added
- **Offline expedition pack** — pre-cache an area before you lose signal so the
  Nature (AR) view keeps working on a no-signal summit, where the peaks and terrain
  skyline silently went empty before. From the **Info tab → "Manage offline
  areas"**, download the area around your current location at a chosen radius
  (5 / 10 / 50 / 100 km): Geo fetches the area's named OpenStreetMap peaks and the
  terrain DEM for the skyline panorama and stores them on-device. Offline, those
  peaks reappear (AR markers + welded ridge labels) and the skyline resolves from
  the cached terrain — no live calls. Saved packs list their peak/cell counts and
  date and can be deleted. The bounding-box prefetch reuses the app's existing
  request throttling + retry so it stays polite to the free Overpass /
  Open-Meteo APIs, and everything stays on device (DEM cells are *pinned* so
  they outlive the normal cache eviction). **Offline data & AR only** — map tiles
  aren't cached (Google Maps licensing). On the **Map tab**, saved areas are shown
  as circles and you can **download a new area by framing it on the map** (a
  "Download a region" mode with a crosshair + radius + live preview). Packs can be
  **named when downloaded and renamed afterwards**, and tapping a region on the map
  lets you rename or delete it.
- **Peak labels welded to the terrain skyline** — in the Nature (AR) view, named
  peaks that form the horizon silhouette now float their name + elevation ("Mont
  Blanc 4808 m") right on the green ridge line, turning the abstract skyline into
  an **identified panorama**. Each peak is matched to the silhouette by apparent
  elevation angle — so peaks hidden behind nearer, higher terrain are skipped —
  and its label floats just clear of the ridge, joined to the exact silhouette
  point by a thin **leader line** (with a dot marking the spot) so the name reads
  cleanly off the line; nearer peaks win when labels would overlap. Tapping a
  floating label opens the peak's detail sheet — the tap target tracks the lifted
  pill, not the ridge underneath it. Reuses the skyline + peak data already
  computed — no new network.
- **Summit log — auto-detect arrival at a known peak** — walk within ~500 m of a
  Seven Summit / Snow Leopard / other known peak and Geo offers to log the ascent
  (date, the peak's elevation, your measured barometric altitude, an optional note)
  — proximity-triggered with a manual confirm, never auto. A "Summits" trophy case
  on the Stat tab lists your logged ascents; each opens a detail with an editable
  note, Directions, Delete, and a **shareable summit card**. 100 % on-device (new
  Room `summit_logs` table, non-destructive `MIGRATION_3_4`, migration-tested);
  only the peak's public location is stored or shared, never yours.
- **Tap-to-identify AR markers + freeze-frame share** — the Nature (AR) view is now
  explorable: tap any peak to open a detail sheet (name, altitude, distance,
  bearing, coordinates, plus a Directions button), via a screen-space
  nearest-marker hit-test against the same live projection that places the markers
  (offset to the marker's measured centre). A shutter button captures a **frozen,
  annotated panorama** — the live ARCore camera frame (`PixelCopy`) with the marker +
  skyline Compose overlay (`GraphicsLayer`) composited on top and a small "Geo"
  footer — and shares it via a new `FileProvider`. 100 % on-device; nothing is uploaded.
- **Known-elevation manual calibration** — pin the altimeter to a trailhead or
  summit marker ("I am at X m") from the Info barometer card for instant,
  weather-proof, offline accuracy. Inverts the barometric formula to back-solve the
  QNH and feeds it into `SensorManager.getAltitude` (persisted in DataStore, so the
  home-screen widget and the background worker agree with the app). The pin decays
  over ~6 h toward the live network QNH as weather drifts, so a stale calibration
  can't silently re-bias the altitude. A green "calibrated" badge shows while it's
  active. 100 % on-device.
- **Trip Recorder** — one tap on the Stat tab wraps the always-on sample stream
  into a named outing. Each trip shows total ascent/descent (with sub-3 m noise
  smoothed so the number doesn't inflate), max/min altitude, distance, moving time,
  and an elevation profile. 100 % on-device (new Room `trips` table, non-destructive
  schema migration v2→v3). A recording survives an app restart.
- **Peak bearing & compass arrow** — the closest- and highest-mountain cards now
  show the true bearing to the peak ("117° SE") with an arrow that rotates to your
  live heading, so it always points at the summit — a low-power, AR-free
  "point me toward it" finder. The magnetic compass azimuth is corrected to true
  north via the local magnetic declination. Includes a "calibrate compass" hint
  when the magnetometer drifts; the compass runs only while the Info tab is open.
- **Sun panel** — today's solar windows for your exact position **and altitude**:
  dawn, sunrise, golden hour (AM/PM), solar noon, sunset, dusk and day length, with
  a live "X h to sunset" countdown (rolling on to tomorrow's sunrise after dark).
  100 % on-device (NOAA solar algorithm); the altitude horizon-dip makes the sun
  rise earlier and set later from a summit. Polar day/night handled. For
  alpine-start planning, turning around before dark, and golden-hour photography.
- **Storm warning** — the classic mountaineering/sailing barometer use: a single
  advisory "pressure falling fast" notification when the barometer drops sharply
  (the leading above-tree-line storm indicator). The 3-hour tendency is a
  least-squares fit over raw station pressure, **de-trended by GPS altitude** so a
  climb — which also drops pressure — never false-fires; it alerts once per onset
  (3-hour cooldown). A live 3-hour trend chip also appears on the Info barometer
  card. Adds the `POST_NOTIFICATIONS` permission (Android 13+) and a `weather_alerts`
  notification channel, requested at first launch; a denial degrades silently;
  background timing is OS-throttled, so the alert is best-effort.
- Persistent, LRU-bounded on-device elevation cache (instant offline skyline).
- History retention: automatic ~1-year prune plus a **Clear history** action.
- One jittered retry/backoff + request spacing on the public APIs (respecting
  `Retry-After`).
- AR scene-warm-up gate with a "Scanning…" indicator (near markers no longer
  flash in before the scene can occlude them), and 5 m location hysteresis to
  stop AR marker jitter.
- Map markers and date formatter are now hoisted (`remember`d) instead of
  re-allocated per recomposition.
- In-app **data-source attribution** (OpenStreetMap, Open-Meteo, Google) on the
  Info tab.

### Changed
- **History points are no longer shown in the AR (Nature) scene** — they cluttered
  the camera view, so the AR overlay now shows only peaks and the skyline. Your
  recorded history is unchanged and still appears on the Map and Stat tabs. (Also
  drops their AR markers, tap targets, occlusion work and the on-screen counter.)
- **Terrain elevation now comes from Open-Meteo** instead of the public
  Open-Elevation endpoint, which was frequently down/timing out and left the AR
  skyline (and its welded peak labels) blank. The cold skyline fetch now also runs
  its batches concurrently, cutting the first load from ~7–15 s to ~1–2 s.
- Altitude uses the international lapse-rate formula via a shared `Atmosphere`
  helper; one precise Everest constant (8848.86 m); live pressure clamped to
  300–1100 hPa.
- Stat day-buckets anchored to today; altitude-graph padding unified.
- The app is **English-only**.

### Fixed
- **AR terrain skyline now loads reliably** — the elevation data behind the green
  terrain skyline (and the peak names welded to it) came from a public API
  (Open-Elevation) that was frequently down / timing out, which left the skyline
  blank and every peak shown as a plain flat marker instead of a welded ridge
  pill. Switched to the **Open-Meteo** elevation API (already used for
  weather/QNH) — fast, reliable, and no key.
- **Offline skyline now works anywhere in a downloaded area** — the offline
  expedition pack used to cache terrain only along a fan from the area's exact
  centre, so the AR skyline (and the peaks welded to it) went blank once you
  moved away from that centre point with no signal. Packs now cache a regular
  terrain grid over the whole area, so the skyline resolves offline from any
  point inside it (capped per pack to keep the download and storage bounded).
- **Welded peak labels de-collide at the correct spacing** — they were packed
  ~2.7× too tightly on Android; the spacing now matches iOS so a crowded ridge
  thins its overlapping name-pills the same way on both platforms.
- **Stale "ghost" markers on re-entering the Nature tab** — markers occluded in a
  previous AR session stayed hidden for ~a second after returning to the tab,
  because the occlusion state survived on the shared singleton. It's now reset
  when a new AR session starts (matching iOS's fresh-per-view behaviour).
- **Offline-pack downloads can no longer be stalled by a server** — a hostile or
  misconfigured `Retry-After` header is now capped at 5 s (matching iOS) instead
  of being honoured for minutes/hours on a network thread.
- **AR tracking smoothness + hot-path cleanup** — the faster compass sampling the
  AR view asks for is no longer ignored when the Info tab started the sensor
  first; per-frame vertical-plane work is throttled off the 60 fps render path;
  the depth-occlusion projection now uses one consistent frame snapshot; and the
  skyline bearing lookups now binary-search the sorted samples (O(log n)).
- **More reliable storm warning** — barometric samples captured without a GPS fix
  used to record a fake 0 m altitude, which threw off the pressure-tendency
  de-trend (it corrects each sample for its altitude) and could trip a false
  alert or mask a real one. Such samples now carry the last known altitude
  forward; a genuine sea-level reading is unaffected.
- **AR warm-up now matches iOS** — before the camera locked tracking, the Nature
  view briefly placed peaks with an approximate heading-only fallback that could
  jump once real tracking kicked in. It now shows just the camera + crosshair
  until tracking is ready, exactly like iOS.
- **Nature (AR) overlay pointed the wrong way** — the skyline, peak markers and
  cardinal labels were placed against ARCore's world frame, which (without the
  Geospatial API) is aligned to **gravity only**, not true north — its yaw is
  wherever the phone faced at session start. So the whole overlay was rotated off
  reality, while iOS is correct because ARKit's `gravityAndHeading` frame is
  magnetometer-aligned. Geo now measures the offset between the device's true
  compass heading (rotation-vector azimuth + magnetic declination) and the ARCore
  pose heading, and rotates every projected point by it in one place
  (`projectToScreen`); the offset converges quickly then HOLDS, so the overlay
  stays welded to true north and the real horizon without drifting as you pan.
  Long-press the AR top bar to see the live ARCore/compass/corrected headings
  under **heading (true-north align)**.
- **Critical:** on first launch, location/altitude/history did not start until
  the app was killed and reopened — GPS now re-subscribes when the location
  permission is granted.
- **Crash:** the "Directions" buttons hard-crashed on devices without Google
  Maps installed — now use a generic `geo:` intent wrapped in `runCatching`.
- Background widget/history altitude ignored the fetched QNH and showed a
  weather-biased value; QNH is now persisted (DataStore) and the worker derives
  a calibrated altitude that matches the foreground app.
- Wear: the tile ignored the phone calibration on live samples; Wear barometer
  and calibration state are now persisted and restored across relaunches.
- Wear → phone barometer backfill now works (the inbound listener was dead code),
  so watch-captured history reaches the phone.
- Home-screen widget: no-data state instead of authoritative `0 m / 0 kPa / 0,0`;
  staleness cue for old data; reconfigure now preserves your toggle choices.
- History DB no longer rebuilt with 4 full scans on every insert (dirty-flag +
  single fetch); added a `recordDate` index (Room migration 1→2, non-destructive)
  and a daily-rollover record.
- Peak identity uses the full 128-bit coordinates (no 64-bit collisions);
  Overpass coordinate quantisation uses round-half consistently.
- Sensors pause when the app/Watch is backgrounded; `WearInboundListener`
  throttle is now atomic; assorted AR/skyline fixes.

### Removed
- Russian translation (`values-ru/`) and the empty `values-en-rGB/` — the app is
  English-only.
- Unused `WAKE_LOCK` permission from the Wear manifest.
- `ACCESS_BACKGROUND_LOCATION` — the app does no background location polling, so
  the permission (and its privacy-policy entry) was dropped.

### Security
- The Google Maps API key is no longer committed in `AndroidManifest.xml`; it is
  injected from (git-ignored) `local.properties` via `manifestPlaceholders`.
  **Action required:** rotate the previously-committed key in Google Cloud
  Console and restrict the new key to the app package + signing SHA-1 + Maps SDK.

[1.1]: https://github.com/nettrash/Geo.Android
