# Privacy Policy

**Effective date:** 17 June 2026
**Applies to:** Geo — the Android app published by nettrash on Google Play (`me.nettrash.geo`). This policy is versioned alongside the app's source code; the most recent commit on `main` is authoritative.

## TL;DR

Geo doesn't collect any personal information about you, doesn't create accounts, and doesn't use analytics, advertising, or trackers. The only data that ever leaves your device is what's necessary to render the things you ask the app to render: a few network requests to display the map, and an approximate-location query to OpenStreetMap when the app looks up nearby mountain peaks. Nothing is sent to servers operated by us — we have no servers.

## What the app accesses, and what each access does

**Precise location** (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) — granted at runtime when you first open the Map or Nature tab. Used exclusively to:

1. Show your current position on the in-app map.
2. Compute distances and bearings to known peaks for the AR view and the "Nearby" list.
3. Build the Overpass / elevation queries described below.

**Camera** (`CAMERA`) — used by the AR view to overlay peak names on the camera feed. Frames are processed entirely on-device by ARCore; nothing is recorded or transmitted.

**High-rate sensors** (`HIGH_SAMPLING_RATE_SENSORS`) — used to read the device's barometer and orientation sensors at the rate ARCore needs to keep peak labels stable on the camera. Sensor values stay on the device.

**Internet** (`INTERNET`) — used for the network calls described in the next section.

## What goes over the network

**OpenStreetMap Overpass API** (`https://overpass-api.de/api/interpreter`). When you open the Nature tab, the app sends an HTTPS request containing your approximate latitude/longitude and a search radius (typically 25–50 km) to fetch the list of nodes tagged `natural=peak` near you. The request body contains *only* those coordinates and the radius — no device identifiers, no Advertising ID, no account information. The Overpass API is a free, public service hosted by OpenStreetMap volunteers and governed by the [OpenStreetMap Foundation Privacy Policy](https://osmfoundation.org/wiki/Privacy_Policy).

**Google Maps Android SDK.** The Map tab renders map tiles and your-location pin via the Maps SDK for Android. Google is the data controller for those requests; the data flow and Google's use of it are governed by the [Google Maps / Google Earth Additional Terms of Service](https://maps.google.com/help/terms_maps/) and the [Google Privacy Policy](https://policies.google.com/privacy). The Maps SDK is configured with a publishable Maps API key restricted to this app's package + signing certificate.

**Open-Meteo API** (`https://api.open-meteo.com`). The Nature/AR view fetches terrain elevation to draw the horizon skyline, and the barometer card fetches the local reference pressure (QNH) to calibrate altitude. Each request sends only approximate latitude/longitude (rounded to a ~110 m grid) — no device identifiers, no Advertising ID, no account information. Open-Meteo is a free, open API; its handling is governed by the [Open-Meteo Terms](https://open-meteo.com/en/terms).

That's the entire list. There are no other servers contacted. There is no telemetry, no crash reporter, no advertising network, no attribution provider, no remote analytics.

## Data stored on your device

**Pressure history** — the device's barometer is sampled periodically and the readings are stored in a local Room database (`/data/data/me.nettrash.geo/databases/geo_database`) so the Stat tab and the home-screen widget can show altitude trends. This data never leaves the device, is not synced anywhere, and is removed when you uninstall the app or clear its storage in *Settings → Apps → Geo → Storage*.

**System Auto Backup** is governed by `backup_rules.xml` in the app and is configured to back up nothing app-specific, so the local database is not mirrored to your Google Drive.

## Permissions Geo does NOT request

Listing them explicitly because the negatives matter as much as the positives:

- `READ_CONTACTS`, `WRITE_CONTACTS`
- `READ_CALENDAR`, `WRITE_CALENDAR`
- `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`
- `READ_PHONE_STATE`
- `RECORD_AUDIO`
- `BLUETOOTH_*`
- Any permission that would let the app read your messages, your call log, or your installed-app list.

## Third-party services

| Service | What it sees | Whose policy applies |
|---|---|---|
| Google Maps SDK for Android | Coarse position + viewport requests for tile rendering | Google's |
| OpenStreetMap Overpass API | Approximate coordinates + search radius | OpenStreetMap Foundation's |
| Open-Meteo API | Approximate coordinates (~110 m grid) for terrain elevation + reference pressure | Open-Meteo's |
| Google ARCore | Camera frames + IMU data, **on-device only** | Google's |

Specifically NOT used: Google Advertising ID, Google Analytics, Firebase, Crashlytics, AdMob, AppLovin, any social-media SDK, any attribution / install-tracking SDK.

Geo declares **Data not collected** and **Data not shared** on its Google Play Data Safety form. The Overpass / Maps requests don't qualify as "collection" in Play's taxonomy because the requests are made by the app on your behalf to render features you asked for; the destinations don't operate as data brokers and the data isn't combined with other data sources to build a user profile.

## Children's privacy

Geo is rated for general audiences (Everyone / PEGI 3) and is not directed at children under 13. We do not knowingly collect personal information from children, because we do not collect personal information from anyone.

## International data transfers

The Overpass API and Google Maps endpoints are global services; requests may transit servers in any country. The data sent (coordinates + radius for Overpass, map-viewport requests for Maps) does not contain personal data under GDPR Article 4(1) when used in this app — it's not combined with any identifier we hold.

## Your rights

Because we hold no data about you:

- There is no record to access under GDPR Article 15 / CCPA "right to know".
- There is no record to delete under GDPR Article 17 / CCPA "right to delete" (the local database is yours alone — clearing the app's storage or uninstalling removes it entirely).
- There is no record to correct under GDPR Article 16.
- There is nothing being sold or shared under CCPA / CPRA, so no opt-out is required.

For data flowing through the third-party services listed above, the respective providers' privacy operators are the right point of contact.

## Changes to this policy

If a future version of Geo changes any of the above — adds analytics, integrates a third-party SDK, adds a new network endpoint, or starts using a permission for a new purpose — this document will be updated *in the same release* and the *Effective date* will be bumped. Full history: <https://github.com/nettrash/Geo.Android/commits/main/play/PRIVACY_POLICY.md>.

## Contact

Privacy questions: **nettrash@nettrash.me**.
