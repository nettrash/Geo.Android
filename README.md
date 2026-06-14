# Geo (Android)

![CI](https://github.com/nettrash/Geo.Android/actions/workflows/ci.yml/badge.svg)

Android + Wear OS app for tracking your geographical state in real time: GPS coordinates, satellite (GPS) altitude, barometric altitude, atmospheric pressure, and an augmented-reality view of nearby mountain peaks. It is the Android port of the iOS [Geo](https://github.com/nettrash/Geo) app and shares its data formats and calculations.

All sensor history stays on your device. There are no analytics, no accounts, no servers operated by us. The only outbound calls are to public elevation/peak/QNH services (OpenStreetMap Overpass, Open-Elevation, Open-Meteo), and your coordinates are quantised to a ~110 m grid before any request.

## Features

- **Info** — current coordinates, satellite altitude, barometer altitude, pressure and "% Everest". When the altitude is the weather-biased pressure estimate rather than the QNH-calibrated value, a **calibrating… / uncalibrated** cue says so.
- **Stat** — on-device history of pressure and altitude, plotted over time (anchored to a rolling 30-day window). Old points are auto-pruned and a **Clear history** action wipes it on demand.
- **Map** — your position on a Google Map with pins for nearby peaks, history points, the Seven Summits and the Snow Leopard peaks.
- **Nature** — AR view (ARCore) that overlays the names of nearby mountain peaks on the camera feed, draws a terrain-aware skyline, and lists "Nearby" peaks with distance, bearing and elevation. Peak data comes from the OpenStreetMap Overpass API and elevations from Open-Elevation; the elevation cache is persisted on-device, so the skyline reappears instantly on a return visit and works offline.
- **Wear OS companion** — barometer-driven altitude (calibrated against the phone's reference) with its own on-device history, plus a glanceable tile.
- **Home-screen widget** — current altitude and pressure at a glance, with a staleness cue and a "no data" state on first run.

The app ships in **English only**.

## Platforms

- Android 9+ (API 28) — phone, barometer recommended
- Wear OS 3+ (API 30) — watch, barometer recommended
- Built with Kotlin, Jetpack Compose, Hilt, Room, DataStore, WorkManager, FusedLocationProvider + the Android Sensor APIs, Google Maps (Maps Compose) and ARCore.

## Build

Uses the Gradle wrapper. The CI builds on **JDK 17**; locally, **JDK 21** (e.g. Android Studio's bundled JBR) also works — newer JDKs (25+) are rejected by the current Gradle/AGP.

```sh
# build (phone + wear)
./gradlew :Geo:assembleDebug :wear:assembleDebug
# lint and unit tests
./gradlew :Geo:lintDebug :Geo:testDebugUnitTest :wear:testDebugUnitTest
```

The Map tab needs a **Google Maps API key**. It is read from `local.properties` (git-ignored) via a manifest placeholder — add:

```properties
MAPS_API_KEY=your-android-maps-sdk-key
```

`versionCode` auto-increments on every `assemble`/`bundle`; pass `-PnoBump` to avoid mutating `version.properties` on CI/verify builds. Release signing reads `keystore.properties` + a `*-upload.jks` (kept out of git).

## Calculation

When a **QNH** (current sea-level pressure for your location) has been fetched from Open-Meteo and is fresh, Geo calibrates altitude with the platform `SensorManager.getAltitude(qnh, pressure)` — this compensates for the day's weather. The QNH is persisted, so calibration survives a cold start and works offline against a recent value. Otherwise it derives altitude from raw atmospheric pressure.

The starting point is the hydrostatic/barometric relation:

$$P = P_0\, e^{\,-\frac{M g h}{R T}}$$

| Symbol | Meaning | Value / Unit |
| --- | --- | --- |
| $P_0$ | Pressure at sea level | Pa |
| $P$ | Pressure at height $h$ | Pa |
| $h$ | Altitude above sea level | m |
| $M$ | Molar mass of dry air | $0.029\ \mathrm{kg/mol}$ |
| $g$ | Gravitational acceleration | $9.81\ \mathrm{m/s^2}$ |
| $R$ | Universal gas constant | $8.31446\ \mathrm{J/(mol\,K)}$ |
| $T_0$ | Sea-level temperature | $288.15\ \mathrm{K}\ (15\ \mathrm{°C})$ |
| $L$ | Tropospheric lapse rate | $0.0065\ \mathrm{K/m}$ |

For the pressure-derived fallback Geo uses the **international barometric (lapse-rate) formula**, which models a troposphere whose temperature falls linearly with height rather than assuming a constant temperature:

$$h = \frac{T_0}{L}\left(1 - \left(\frac{P}{P_0}\right)^{\frac{R L}{g M}}\right) = 44330\left(1 - \left(\frac{P}{P_0}\right)^{\frac{1}{5.255}}\right)$$

with $T_0/L = 44330$, the exponent $\frac{R L}{g M} = \frac{1}{5.255}\approx 0.1903$, and $P_0 = 101.325\ \mathrm{kPa}$ (standard sea-level pressure, replaced by the fetched QNH when available). This is materially more accurate at altitude than the older constant-temperature approximation.

Before the calculation, incoming pressure is **clamped to 300–1100 hPa** so a single bad sensor sample can't produce a NaN or wildly out-of-range value. The same helper is used on every surface — app, widget, Wear screen and tile — so they always report the same altitude. "% Everest" is simply $h / 8848.86\ \mathrm{m}$. The token format is byte-compatible with the iOS app (`recordDate` is epoch-milliseconds).

## Compass Points

Bearings (degrees clockwise from true north) are bucketed into eight points:

| Point | From (°) | To (°) |
| --- | --- | --- |
| North | 338 | 22 |
| North-East | 23 | 67 |
| East | 68 | 112 |
| South-East | 113 | 157 |
| South | 158 | 202 |
| South-West | 203 | 247 |
| West | 248 | 292 |
| North-West | 293 | 337 |

The North bucket wraps across 360°/0°. Each bucket spans 45°, centred on its cardinal or inter-cardinal direction.
