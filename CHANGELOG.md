# Changelog — Geo.Android (Android + Wear)

All notable changes to Geo.Android are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/). Entries are tagged with the
Play `versionCode`; note that `versionCode` auto-increments on every
assemble/bundle, so the value actually uploaded to Play may be higher than the
one recorded here.

## [1.1] — 2026-06-14

A correctness, accuracy and reliability release: a critical first-run fix, a
hard-crash fix, calibrated altitude that agrees across phone/widget/Watch, plus
39 verified bug fixes and 24 improvements.

### Fixed
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

### Added
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

### Changed
- Altitude uses the international lapse-rate formula via a shared `Atmosphere`
  helper; one precise Everest constant (8848.86 m); live pressure clamped to
  300–1100 hPa.
- Stat day-buckets anchored to today; altitude-graph padding unified.
- The app is **English-only**.

### Removed
- Russian translation (`values-ru/`) and the empty `values-en-rGB/` — the app is
  English-only.
- Unused `WAKE_LOCK` permission from the Wear manifest.

### Security
- The Google Maps API key is no longer committed in `AndroidManifest.xml`; it is
  injected from (git-ignored) `local.properties` via `manifestPlaceholders`.
  **Action required:** rotate the previously-committed key in Google Cloud
  Console and restrict the new key to the app package + signing SHA-1 + Maps SDK.

[1.1]: https://github.com/nettrash/Geo.Android
