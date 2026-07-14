package me.nettrash.geo.ui

import android.content.Context
import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.nettrash.geo.data.db.HistoryItem
import me.nettrash.geo.data.db.SummitLog
import me.nettrash.geo.data.db.Trip
import me.nettrash.geo.data.model.DataItem
import me.nettrash.geo.data.model.DataPoint
import me.nettrash.geo.data.model.MountainData
import me.nettrash.geo.data.model.MountainInfo
import me.nettrash.geo.data.model.MountainList
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.offline.OfflinePack
import me.nettrash.geo.offline.OfflinePackRepository
import java.util.Locale
import me.nettrash.geo.data.repository.HistoryRepository
import me.nettrash.geo.data.snapshot.SharedSnapshotStore
import me.nettrash.geo.location.LocationManager
import me.nettrash.geo.sensor.BarometerManager
import me.nettrash.geo.sensor.DeviceMotionManager
import me.nettrash.geo.sensor.PressureSample
import me.nettrash.geo.sensor.PressureTrend
import me.nettrash.geo.sensor.StormWarning
import me.nettrash.geo.util.AppLog
import me.nettrash.geo.util.GeoCalculations
import me.nettrash.geo.util.MountainLoader
import me.nettrash.geo.util.PeakFinder
import me.nettrash.geo.util.QnhRepository
import me.nettrash.geo.util.TripRecordingStore
import me.nettrash.geo.widget.WidgetUpdater
import javax.inject.Inject

@HiltViewModel
class GeoViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    val barometerManager: BarometerManager,
    val locationManager: LocationManager,
    val motionManager: DeviceMotionManager,
    private val historyRepository: HistoryRepository,
    private val qnhRepository: QnhRepository,
    private val mountainLoader: MountainLoader,
    private val peakFinder: PeakFinder,
    private val widgetUpdater: WidgetUpdater,
    private val offlinePackRepository: OfflinePackRepository
) : ViewModel() {

    // Mountain data
    private val _mountainsData = MutableStateFlow<MountainData?>(null)
    val mountainsData: StateFlow<MountainData?> = _mountainsData.asStateFlow()

    // History datasets
    private val _pressureDataSet = MutableStateFlow<List<DataItem>>(emptyList())
    val pressureDataSet: StateFlow<List<DataItem>> = _pressureDataSet.asStateFlow()
    private val _pressureMin = MutableStateFlow(0f)
    val pressureMin: StateFlow<Float> = _pressureMin.asStateFlow()
    private val _pressureMax = MutableStateFlow(1000f)
    val pressureMax: StateFlow<Float> = _pressureMax.asStateFlow()

    private val _barometerAltDataSet = MutableStateFlow<List<DataItem>>(emptyList())
    val barometerAltDataSet: StateFlow<List<DataItem>> = _barometerAltDataSet.asStateFlow()
    private val _barometerAltMin = MutableStateFlow(0f)
    val barometerAltMin: StateFlow<Float> = _barometerAltMin.asStateFlow()
    private val _barometerAltMax = MutableStateFlow(10000f)
    val barometerAltMax: StateFlow<Float> = _barometerAltMax.asStateFlow()

    private val _gpsAltDataSet = MutableStateFlow<List<DataItem>>(emptyList())
    val gpsAltDataSet: StateFlow<List<DataItem>> = _gpsAltDataSet.asStateFlow()
    private val _gpsAltMin = MutableStateFlow(0f)
    val gpsAltMin: StateFlow<Float> = _gpsAltMin.asStateFlow()
    private val _gpsAltMax = MutableStateFlow(10000f)
    val gpsAltMax: StateFlow<Float> = _gpsAltMax.asStateFlow()

    private val _trackingDataSet = MutableStateFlow<List<DataPoint>>(emptyList())
    val trackingDataSet: StateFlow<List<DataPoint>> = _trackingDataSet.asStateFlow()
    private val _trackingMin = MutableStateFlow(0f)
    val trackingMin: StateFlow<Float> = _trackingMin.asStateFlow()
    private val _trackingMax = MutableStateFlow(10000f)
    val trackingMax: StateFlow<Float> = _trackingMax.asStateFlow()

    // History items for map
    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    /** Latest de-trended 3-hour pressure tendency (M5a), recomputed on
     *  each history refresh. Drives the Info barometer-card trend chip;
     *  the authoritative storm alerting runs off [me.nettrash.geo.worker
     *  .BarometerRefreshWorker]. */
    private val _pressureTrend = MutableStateFlow(PressureTrend.UNKNOWN)
    val pressureTrend: StateFlow<PressureTrend> = _pressureTrend.asStateFlow()

    // Trip Recorder (M5c)
    private val tripStore = TripRecordingStore(appContext)
    /** Epoch-ms the current recording started, or null when not recording. */
    private val _tripStartedAt = MutableStateFlow(tripStore.startedAtMs())
    val tripStartedAt: StateFlow<Long?> = _tripStartedAt.asStateFlow()
    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()

    // ── Summit log (auto-detect arrival at a known peak) ─────────
    private val _summitLogs = MutableStateFlow<List<SummitLog>>(emptyList())
    val summitLogs: StateFlow<List<SummitLog>> = _summitLogs.asStateFlow()

    /** The nearby known peak currently offered for logging (within range, not
     *  dismissed, not already logged recently). Drives the proximity prompt. */
    private val _nearbyUnloggedPeak = MutableStateFlow<SummitCandidate?>(null)
    val nearbyUnloggedPeak: StateFlow<SummitCandidate?> = _nearbyUnloggedPeak.asStateFlow()

    /** Peak keys the user dismissed/logged this approach; cleared when they
     *  leave all peaks' range so a fresh re-approach can prompt again. */
    private val dismissedSummitKeys = mutableSetOf<String>()

    /** Horizontal radius (m) within which we offer to log a summit. Manual
     *  confirm only — barometric altitude bias means we never auto-log. */
    private val summitProximityRadiusM = 500.0

    data class SummitCandidate(
        val peak: MountainInfo,
        val peakSet: String,
        val peakIdentifier: String
    )

    // Peaks for AR
    private val _peaks = MutableStateFlow<List<NearbyPeak>>(emptyList())
    val peaks: StateFlow<List<NearbyPeak>> = _peaks.asStateFlow()

    private val trackingMutableData = mutableListOf<DataPoint>()

    init {
        initialize()
    }

    private fun initialize() {
        // Load mountains
        val data = mountainLoader.load()
        _mountainsData.value = data
        locationManager.mountainsData = data

        // Drain any background samples captured by the widget worker
        // or Wear bridge while the main app was suspended, BEFORE
        // wiring sensors / starting location. Mirrors iOS
        // `GeoAppDelegate.restoreFromSharedStorage()`.
        restoreFromSharedStorage()

        // Retention prune: drop history older than the ~1-year window.
        // Runs once at startup off the main thread. Mirrors iOS
        // `History` launch-time prune.
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.prune()
        }

        // Set up barometer callbacks
        barometerManager.onDataUpdated = {
            locationManager.onBarometerUpdated(
                barometerManager.pressure.value,
                barometerManager.height.value
            )
            updateWidget()
        }
        barometerManager.start()

        // Set up location callbacks
        locationManager.onRecordHistory = { loc ->
            recordHistory(loc)
        }
        locationManager.onTrackingUpdate = { loc ->
            addTrackingPoint(loc)
        }
        locationManager.onLocationUpdated = { _ ->
            updateWidget()
        }
        locationManager.startLocationUpdates()

        // Summit-log proximity detection: offer to log when the user is within
        // `summitProximityRadiusM` of a known peak. Re-evaluated only when the
        // nearest in-range peak actually changes (distinctUntilChanged), so the
        // de-dup DB check runs rarely, not on every GPS fix.
        combine(
            locationManager.closestMountain,
            locationManager.closestMountainDistance
        ) { peak, dist ->
            if (peak != null && dist != null && dist < summitProximityRadiusM &&
                peak.coordinates?.latitude != null && peak.coordinates?.longitude != null
            ) peak else null
        }
            .distinctUntilChanged { a, b -> summitKey(a) == summitKey(b) }
            .onEach { peak -> evaluateSummitCandidate(peak) }
            .launchIn(viewModelScope)

        // Load initial history
        refreshHistory()
    }

    private suspend fun evaluateSummitCandidate(peak: MountainInfo?) {
        if (peak == null) {
            _nearbyUnloggedPeak.value = null
            dismissedSummitKeys.clear()   // out of range → re-approach may prompt
            return
        }
        val key = summitKey(peak) ?: return
        if (key in dismissedSummitKeys || historyRepository.summitLoggedRecently(key, 18)) {
            _nearbyUnloggedPeak.value = null
        } else {
            _nearbyUnloggedPeak.value = SummitCandidate(peak, deriveSummitSet(peak), key)
        }
    }

    /** Stable de-dup key for a peak, from its name + rounded coordinates. */
    private fun summitKey(peak: MountainInfo?): String? {
        val c = peak?.coordinates ?: return null
        val lat = c.latitude ?: return null
        val lon = c.longitude ?: return null
        return "${peak.name ?: ""}@${String.format(Locale.US, "%.4f", lat)},${String.format(Locale.US, "%.4f", lon)}"
    }

    /** Which curated set the peak belongs to (Seven Summits / Snow Leopard take
     *  precedence over "highest"). */
    private fun deriveSummitSet(peak: MountainInfo): String {
        val data = _mountainsData.value ?: return "highest"
        fun has(list: MountainList?) =
            list?.mountains?.any { it.name == peak.name && it.coordinates == peak.coordinates } == true
        return when {
            has(data.sevenPeaks) -> "sevenPeaks"
            has(data.snowLeopardOfRussia) -> "snowLeopardOfRussia"
            else -> "highest"
        }
    }

    /**
     * Mirror of iOS `GeoAppDelegate.restoreFromSharedStorage()`.
     *
     *  1. Rehydrate the in-memory barometer from the most recent
     *     snapshot if the live sensor hasn't produced one yet. This
     *     keeps the Info / widget readings continuous across app
     *     restarts when only the worker / widget has captured data.
     *  2. Drain the rolling buffer of background snapshots into the
     *     Room history. Insert is keyed off `recordDate` so repeated
     *     calls are idempotent.
     *  3. Clear the buffer on success so we don't re-insert next time.
     */
    private fun restoreFromSharedStorage() {
        val buffered = SharedSnapshotStore.readBuffer(appContext)
        AppLog.app.debug("restoreFromSharedStorage: ${buffered.size} buffered samples")

        if (buffered.isNotEmpty()) {
            viewModelScope.launch {
                var inserted = 0
                for (token in buffered) {
                    if (token.barPreassure <= 0) continue
                    val existing = historyRepository.findByRecordDate(token.recordDate)
                    if (existing != null) continue
                    historyRepository.insert(
                        HistoryItem(
                            recordDate        = token.recordDate,
                            barometerAltitude = token.barAltitude,
                            barometerPressure = token.barPreassure,
                            gpsLatitude       = token.gpsLatitude,
                            gpsLongitude      = token.gpsLongitude,
                            gpsAltitude       = token.gpsAltitude,
                            gpsVelocity       = token.gpsSpeed
                        )
                    )
                    inserted++
                }
                if (inserted > 0) {
                    AppLog.app.info("Backfilled $inserted buffered samples")
                    refreshHistory()
                }
                SharedSnapshotStore.clearBuffer(appContext)
            }
        }
    }

    /**
     * Dirty flag mirroring iOS `History.isDirty`. recordHistory (and any
     * other insert path) flips this instead of eagerly rebuilding the
     * graphs; UI surfaces call [refreshIfNeeded] which no-ops when clean.
     * `@Volatile` because it is read/written from coroutines.
     */
    @Volatile
    private var historyDirty: Boolean = true

    /** Mark the history cache stale without rebuilding (cheap). */
    private fun markHistoryDirty() {
        historyDirty = true
    }

    /**
     * Refresh the history-derived StateFlows only when something has
     * changed since the last rebuild. Called from the Stat / Map / AR
     * surfaces. Mirrors iOS `History.refreshIfNeeded()`.
     */
    fun refreshIfNeeded() {
        if (!historyDirty) return
        refreshHistory()
    }

    fun refreshHistory() {
        // Clear the flag up-front so concurrent inserts that land during
        // the rebuild re-mark it dirty rather than being lost.
        historyDirty = false
        viewModelScope.launch {
            // Fetch the 30-day window ONCE and feed it into all three
            // builders (was: one query here + one inside each build*,
            // i.e. four identical SELECTs per refresh — see A9/A23).
            val items = historyRepository.getItemsSince()
            _historyItems.value = items

            val (pData, pMin, pMax) = historyRepository.buildPressureDataSet(items)
            _pressureDataSet.value = pData
            _pressureMin.value = pMin
            _pressureMax.value = pMax

            val (bData, bMin, bMax) = historyRepository.buildBarometerAltitudeDataSet(items)
            _barometerAltDataSet.value = bData
            _barometerAltMin.value = bMin
            _barometerAltMax.value = bMax

            val (gData, gMin, gMax) = historyRepository.buildGPSAltitudeDataSet(items)
            _gpsAltDataSet.value = gData
            _gpsAltMin.value = gMin
            _gpsAltMax.value = gMax

            // De-trended 3-hour pressure tendency (M5a). The shared fit
            // filters to its own 3 h window, so feeding the full 30-day
            // set is fine.
            val samples = items.map {
                PressureSample(it.recordDate, it.barometerPressure, it.gpsAltitude)
            }
            _pressureTrend.value = StormWarning.tendency(samples, System.currentTimeMillis())
        }
    }

    /**
     * Delete all recorded history, then rebuild the derived datasets so
     * the Stat / Map / AR surfaces empty out immediately. Backs the
     * "Clear history" action on the Stat screen (behind a confirmation).
     */
    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearAll()
            refreshHistory()
        }
    }

    // ── Trip Recorder (M5c) ──────────────────────────────────────

    val isRecordingTrip: Boolean get() = _tripStartedAt.value != null

    fun loadTrips() {
        viewModelScope.launch { _trips.value = historyRepository.getTrips() }
    }

    fun startTrip() {
        val now = System.currentTimeMillis()
        tripStore.setStartedAtMs(now)
        _tripStartedAt.value = now
    }

    /** Stop + save the in-progress recording under [name], then reload the list. */
    fun stopTrip(name: String) {
        val start = _tripStartedAt.value ?: return
        val end = System.currentTimeMillis()
        // Clear the recording state synchronously (before the async save) so the
        // start can't be re-consumed — closes the double-save and
        // discard-after-save windows. Mirrors iOS's synchronous stop().
        tripStore.clear()
        _tripStartedAt.value = null
        viewModelScope.launch {
            try {
                historyRepository.saveTrip(name, start, end)
                _trips.value = historyRepository.getTrips()
            } catch (t: Throwable) {
                // Save failed — restore the in-progress recording so the user
                // can retry rather than lose it.
                AppLog.app.warn("Trip save failed; restoring recording state", t)
                tripStore.setStartedAtMs(start)
                _tripStartedAt.value = start
            }
        }
    }

    /** Abandon the in-progress recording without saving. */
    fun cancelTrip() {
        tripStore.clear()
        _tripStartedAt.value = null
    }

    // ── Manual altitude calibration (M5b) ────────────────────────

    /** Current manual calibration (null = none), for the barometer-card badge. */
    val altitudeCalibration: StateFlow<QnhRepository.Calibration?> get() = qnhRepository.calibration

    /** Pin the altimeter to [knownAltitudeM] using the live raw pressure. */
    fun calibrateAltitude(knownAltitudeM: Double) {
        // Need a real station-pressure sample to back-solve the QNH; the 0.0
        // placeholder (no reading yet) would store a bogus calibration. The UI
        // also gates this, but guard the boundary too. Mirrors iOS `canCalibrate`.
        val livePressureKpa = barometerManager.pressure.value
        if (livePressureKpa <= 0.0) return
        qnhRepository.calibrate(knownAltitudeM, livePressureKpa)
    }

    fun clearAltitudeCalibration() {
        qnhRepository.clearCalibration()
    }

    fun isAltitudeCalibrated(nowMs: Long): Boolean = qnhRepository.isCalibrated(nowMs)

    fun deleteTrip(trip: Trip) {
        viewModelScope.launch {
            historyRepository.deleteTrip(trip)
            _trips.value = historyRepository.getTrips()
        }
    }

    // ── Summit log actions ───────────────────────────────────────

    fun loadSummitLogs() {
        viewModelScope.launch { _summitLogs.value = historyRepository.getSummitLogs() }
    }

    /** Log the current nearby peak with an optional [note], using the live
     *  barometric altitude as the measured value. No-op if nothing is in range. */
    fun logSummit(note: String) {
        val candidate = _nearbyUnloggedPeak.value ?: return
        val peak = candidate.peak
        val log = SummitLog(
            peakName = peak.name ?: "",
            peakIdentifier = candidate.peakIdentifier,
            peakSet = candidate.peakSet,
            peakAltitude = peak.height ?: 0,
            latitude = peak.coordinates?.latitude ?: 0.0,
            longitude = peak.coordinates?.longitude ?: 0.0,
            loggedDate = System.currentTimeMillis(),
            measuredAltitude = barometerManager.height.value,
            note = note.trim().ifEmpty { null }
        )
        dismissedSummitKeys.add(candidate.peakIdentifier)
        _nearbyUnloggedPeak.value = null
        viewModelScope.launch {
            historyRepository.saveSummitLog(log)
            _summitLogs.value = historyRepository.getSummitLogs()
        }
    }

    /** Dismiss the proximity prompt without logging (suppressed until the user
     *  walks out of range and re-approaches). */
    fun dismissNearbySummit() {
        _nearbyUnloggedPeak.value?.let { dismissedSummitKeys.add(it.peakIdentifier) }
        _nearbyUnloggedPeak.value = null
    }

    fun deleteSummitLog(log: SummitLog) {
        viewModelScope.launch {
            historyRepository.deleteSummitLog(log)
            _summitLogs.value = historyRepository.getSummitLogs()
        }
    }

    fun updateSummitNote(log: SummitLog, note: String) {
        viewModelScope.launch {
            historyRepository.updateSummitLog(log.copy(note = note.trim().ifEmpty { null }))
            _summitLogs.value = historyRepository.getSummitLogs()
        }
    }

    suspend fun tripElevationProfile(trip: Trip): List<Double> =
        historyRepository.tripElevationProfile(trip.startDate, trip.endDate)

    private fun recordHistory(location: Location) {
        viewModelScope.launch {
            val item = HistoryItem(
                recordDate = System.currentTimeMillis(),
                barometerAltitude = barometerManager.height.value,
                barometerPressure = barometerManager.pressure.value,
                gpsLatitude = location.latitude,
                gpsLongitude = location.longitude,
                gpsAltitude = location.altitude,
                gpsVelocity = maxOf(location.speed.toDouble(), 0.0)
            )
            historyRepository.insert(item)
            // Mark dirty instead of rebuilding all three graph datasets
            // on every step insert; the Stat/Map/AR surfaces pull a
            // refresh via refreshIfNeeded(). Mirrors iOS markDirty().
            markHistoryDirty()
        }
    }

    /**
     * Record one tracking sample. Each series is included only when it actually
     * has data right now — a `null` becomes a NaN gap so that line breaks
     * instead of collapsing to sea level:
     *
     *  - Barometer: only on devices that have the sensor (`available`).
     *  - GPS: [location] is null when the fix is unusable (no fix / stale /
     *    no altitude) — `LocationManager.usableGpsFix()` decides.
     *
     * So with no GPS the barometer line keeps tracking on its own, and on a
     * device with no barometer only the GPS line is drawn.
     */
    private fun addTrackingPoint(location: Location?) {
        val barometerHeight: Float? =
            if (barometerManager.available) barometerManager.height.value.toFloat() else null
        val gpsAltitude: Float? = location?.altitude?.toFloat()

        val (data, min, max) = historyRepository.addTrackingPoint(
            trackingMutableData,
            barometerHeight,
            gpsAltitude
        )
        _trackingDataSet.value = data
        _trackingMin.value = min
        _trackingMax.value = max
    }

    fun searchForPeaks() {
        val loc = locationManager.location.value ?: return
        viewModelScope.launch {
            val results = peakFinder.searchPeaks(
                loc, _mountainsData.value, _peaks.value,
                offlinePackRepository.combinedPeaks.value
            )
            _peaks.value = results
        }
    }

    // ─── Offline expedition pack ──────────────────────────────────────
    // Pre-cached area (OSM peaks + terrain DEM) so AR/skyline survive a
    // no-signal summit. The repository seeds the live peak/elevation caches
    // at launch; these just surface its state + actions to the Info screen.
    val offlinePacks: StateFlow<List<OfflinePack>> = offlinePackRepository.packs
    val offlinePackDownloading: StateFlow<Boolean> = offlinePackRepository.isDownloading
    val offlinePackProgress: StateFlow<Float> = offlinePackRepository.progress
    val offlinePackStatus: StateFlow<String> = offlinePackRepository.statusText

    /** Download a pack for the current location at [radiusKm]. No-ops with no fix. */
    fun downloadOfflinePack(name: String, radiusKm: Double) {
        val loc = locationManager.location.value ?: return
        // Off the Main dispatcher: createPack builds the ~3600-point skyline grid
        // and merges results on the caller thread (the network calls re-dispatch
        // to IO themselves), so keep that CPU work off the UI thread.
        viewModelScope.launch(Dispatchers.Default) {
            offlinePackRepository.createPack(name, loc.latitude, loc.longitude, radiusKm)
        }
    }

    fun deleteOfflinePack(pack: OfflinePack) = offlinePackRepository.delete(pack)

    /** Download a pack centred on an explicit map point (used by the Map tab's
     *  "choose area" flow) rather than the current GPS location. */
    fun downloadOfflinePackAt(name: String, centerLat: Double, centerLon: Double, radiusKm: Double) {
        viewModelScope.launch(Dispatchers.Default) {
            offlinePackRepository.createPack(name, centerLat, centerLon, radiusKm)
        }
    }

    fun renameOfflinePack(pack: OfflinePack, newName: String) = offlinePackRepository.rename(pack, newName)

    private fun updateWidget() {
        // Throttled push — see WidgetUpdater.pushThrottled for the
        // 30 s budget rationale (mirrors iOS reloadWidgetIfNeeded).
        widgetUpdater.pushThrottled()
    }

    override fun onCleared() {
        super.onCleared()
        barometerManager.stop()
        locationManager.stopLocationUpdates()
        motionManager.stop()
    }
}
