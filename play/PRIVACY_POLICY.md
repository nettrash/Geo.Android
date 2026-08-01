# Privacy Policy

**Effective date:** 1 August 2026
**Applies to:** Geo — the Android app published by nettrash on Google Play (`me.nettrash.geo`). This policy is versioned alongside the app's source code; the most recent commit on `main` is authoritative.

## TL;DR

Geo doesn't collect any personal information about you, doesn't create accounts, and doesn't use analytics, advertising, or trackers. The only data that ever leaves your device is what's necessary to render the things you ask the app to render: a few network requests to display the map, an approximate-location query to OpenStreetMap when the app looks up nearby mountain peaks, the same approximate coordinates to Open-Meteo for ground elevation and reference pressure, and a parameterless request to NOAA for the planetary K index. Nothing is sent to servers operated by us — we have no servers.

## What the app accesses, and what each access does

**Precise location** (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) — requested at runtime the first time you launch the app. Used exclusively to:

1. Show your current position on the in-app map.
2. Compute distances and bearings to known peaks for the Nature (camera) view and the Info tab's "Closest mountain" / "Highest mountain" cards.
3. Build the Overpass / elevation queries described below.

**Camera** (`CAMERA`) — used by the Nature view to overlay peak names on the camera feed. Frames are processed entirely on-device by ARCore, and nothing is transmitted. The camera feed itself is never recorded; the one thing that is ever written is the still image you take yourself with the shutter button — the current camera frame with the peak labels drawn on it. That image is saved as a PNG inside the app's own private cache folder, which no other app can read; captures older than an hour are deleted the next time you use the shutter, and the whole folder goes when you clear the app's storage or uninstall it. The image leaves the app only if you pick a destination for it from the system share sheet, and only to the app you picked. Geo has no permission to write to your photo gallery and does not add it there.

**High-rate sensors** (`HIGH_SAMPLING_RATE_SENSORS`) — used to read the device's barometer and orientation sensors at the rate ARCore needs to keep peak labels stable on the camera. Sensor values stay on the device.

**Notifications** (`POST_NOTIFICATIONS`, Android 13+) — used for two alerts, each on its own channel so you can silence one without losing the other. The **storm advisory** (`Weather alerts`) is a single "pressure falling fast" notification derived entirely from the barometer readings already stored on your device; nothing about it goes over the network. The **aurora alert** (`Aurora alerts`) is **off until you switch it on** in the Magnetic Conditions card's "What this means" dialog: while it is off no background work is scheduled at all, and once it is on it fetches the same parameterless NOAA request described below and is capped at one alert per 20 hours. Both alerts are composed on the device, and declining the permission simply means you don't get them.

**Internet** (`INTERNET`) — used for the network calls described in the next section.

## What goes over the network

**OpenStreetMap Overpass API** (`https://overpass-api.de/api/interpreter`). When you open the Nature tab, the app sends an HTTPS request containing your approximate latitude/longitude (rounded to the same ~110 m grid as the elevation lookups below) and a search radius (5 km) to fetch the list of nodes tagged `natural=peak` near you. The radius is larger only when *you* ask for it: downloading an offline area uses the radius you pick yourself (5 / 10 / 50 / 100 km). The request body contains *only* those coordinates and the radius — no device identifiers, no Advertising ID, no account information. The Overpass API is a free, public service hosted by OpenStreetMap volunteers and governed by the [OpenStreetMap Foundation Privacy Policy](https://osmfoundation.org/wiki/Privacy_Policy).

**Google Maps Android SDK.** The Map tab renders map tiles and your-location pin via the Maps SDK for Android. Google is the data controller for those requests; the data flow and Google's use of it are governed by the [Google Maps / Google Earth Additional Terms of Service](https://maps.google.com/help/terms_maps/) and the [Google Privacy Policy](https://policies.google.com/privacy). The Maps SDK is configured with a publishable Maps API key restricted to this app's package + signing certificate.

**Open-Meteo API** (`https://api.open-meteo.com`). The Nature/AR view looks up the ground elevation of nearby peaks and of your own vantage point so it can tell which summits rise above your horizon and place their labels at the right height, and the barometer card fetches the local reference pressure (QNH) to calibrate altitude. Each request sends only approximate latitude/longitude (rounded to a ~110 m grid) — no device identifiers, no Advertising ID, no account information. Open-Meteo is a free, open API; its handling is governed by the [Open-Meteo Terms](https://open-meteo.com/en/terms).

**NOAA Space Weather Prediction Center** (`https://services.swpc.noaa.gov`). The Magnetic Conditions card fetches the planetary K index — a single global number describing geomagnetic activity — so it can tell you how much a geomagnetic storm may be affecting your compass and GPS, and whether the aurora could be visible from your latitude. **This request carries no location, no identifiers, and no parameters of any kind: it is the same fixed URL for every user of the app, and nothing about you is sent.** The response is public-domain data published by the U.S. National Oceanic and Atmospheric Administration. Everything the card says about *your* position — your magnetic latitude, which way to look, when it gets dark — is computed on your device from that one global number and never leaves it. Geo is not affiliated with, or endorsed by, NOAA.

That's the entire list. There are no other servers contacted. There is no telemetry, no crash reporter, no advertising network, no attribution provider, no remote analytics.

## Data stored on your device

**Pressure history** — the device's barometer is sampled periodically and the readings are stored in a local Room database (`/data/data/me.nettrash.geo/databases/geo_database`) so the Stat tab and the home-screen widget can show altitude trends. The app never uploads it and never syncs it through any service, and it is removed when you uninstall the app or clear its storage in *Settings → Apps → Geo → Storage*. The one copy the app doesn't make itself is the device's own backup — see *System Auto Backup* below.

**Trips and the summit log** — the outings you record on the Stat tab and the ascents you confirm in the summit log live in tables of that same local database, on exactly the same terms.

**Offline peak packs** — an area you download for offline use is stored as plain JSON files under the app's private `files/offline_packs` folder. A pack holds only public OpenStreetMap peak data — name, coordinate, altitude — plus the centre and radius *you* chose; it records nothing about where you have been.

**System Auto Backup.** Geo leaves Android's standard Auto Backup switched on (`android:allowBackup="true"`), so your device's own backup service may include the app's local data in the backup it makes to *your* Google account and restore it when you set up a new device. That copy is yours: it goes to your account, we are never sent it and cannot read it, and it is governed by [Android's Auto Backup behaviour](https://developer.android.com/guide/topics/data/autobackup) and Google's privacy policy rather than by us. If you would rather it didn't happen, turn backup off for the device — or for Geo specifically — in your Android settings.

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
| Open-Meteo API | Approximate coordinates (~110 m grid) to look up peak / vantage-point ground elevation + reference pressure | Open-Meteo's |
| NOAA Space Weather Prediction Center | **Nothing** — a fixed URL with no parameters, byte-identical for every user, returning space-weather indices and forecasts | Public domain (U.S. Government work); NOAA's |
| Google ARCore | Camera frames + IMU data, **on-device only** | Google's |

Specifically NOT used: Google Advertising ID, Google Analytics, Firebase, Crashlytics, AdMob, AppLovin, any social-media SDK, any attribution / install-tracking SDK.

Geo declares **Data not collected** and **Data not shared** on its Google Play Data Safety form. The Overpass / Maps requests don't qualify as "collection" in Play's taxonomy because the requests are made by the app on your behalf to render features you asked for; the destinations don't operate as data brokers and the data isn't combined with other data sources to build a user profile. The NOAA space-weather request is a fixed URL that carries no data about you whatsoever, so it does not even reach the "made by the app on your behalf to render a feature you asked for" argument that the Overpass and Open-Meteo requests rely on.

## Children's privacy

Geo is rated for general audiences (Everyone / PEGI 3) and is not directed at children under 13. We do not knowingly collect personal information from children, because we do not collect personal information from anyone.

## International data transfers

The Overpass API and Google Maps endpoints are global services; requests may transit servers in any country. The data sent (coordinates + radius for Overpass, map-viewport requests for Maps) does not contain personal data under GDPR Article 4(1) when used in this app — it's not combined with any identifier we hold.

## Your rights

Because we hold no data about you:

- There is no record to access under GDPR Article 15 / CCPA "right to know".
- There is no record to delete under GDPR Article 17 / CCPA "right to delete" (the local database is yours alone — clearing the app's storage or uninstalling removes it from the device, and any copy in your own account backup is yours to remove in your Android backup settings).
- There is no record to correct under GDPR Article 16.
- There is nothing being sold or shared under CCPA / CPRA, so no opt-out is required.

For data flowing through the third-party services listed above, the respective providers' privacy operators are the right point of contact.

## Changes to this policy

If a future version of Geo changes any of the above — adds analytics, integrates a third-party SDK, adds a new network endpoint, or starts using a permission for a new purpose — this document will be updated *in the same release* and the *Effective date* will be bumped. Full history: <https://github.com/nettrash/Geo.Android/commits/master/play/PRIVACY_POLICY.md>.

## Contact

Privacy questions: **nettrash@nettrash.me**.
