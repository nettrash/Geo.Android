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
import kotlinx.coroutines.launch
import me.nettrash.geo.data.db.HistoryItem
import me.nettrash.geo.data.model.ARHistoryPoint
import me.nettrash.geo.data.model.DataItem
import me.nettrash.geo.data.model.DataPoint
import me.nettrash.geo.data.model.MountainData
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.data.repository.HistoryRepository
import me.nettrash.geo.data.snapshot.SharedSnapshotStore
import me.nettrash.geo.ar.ArOcclusionManager
import me.nettrash.geo.ar.SkylineCalculator
import me.nettrash.geo.location.LocationManager
import me.nettrash.geo.sensor.BarometerManager
import me.nettrash.geo.sensor.DeviceMotionManager
import me.nettrash.geo.util.AppLog
import me.nettrash.geo.util.GeoCalculations
import me.nettrash.geo.util.MountainLoader
import me.nettrash.geo.util.PeakFinder
import me.nettrash.geo.widget.WidgetUpdater
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class GeoViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    val barometerManager: BarometerManager,
    val locationManager: LocationManager,
    val motionManager: DeviceMotionManager,
    private val historyRepository: HistoryRepository,
    private val mountainLoader: MountainLoader,
    private val peakFinder: PeakFinder,
    private val widgetUpdater: WidgetUpdater,
    /** Exposed publicly so NatureScreen can render its `samples` and
     *  `isComputing` StateFlows directly — keeps the heavy terrain
     *  cache scoped to the application, not the ViewModel. */
    val skylineCalculator: SkylineCalculator,
    /** Exposed publicly so NatureScreen can feed targets in and
     *  read back the occluded-ID set. */
    val occlusionManager: ArOcclusionManager
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

    // Peaks for AR
    private val _peaks = MutableStateFlow<List<NearbyPeak>>(emptyList())
    val peaks: StateFlow<List<NearbyPeak>> = _peaks.asStateFlow()

    // AR History points
    private val _arHistoryPoints = MutableStateFlow<List<ARHistoryPoint>>(emptyList())
    val arHistoryPoints: StateFlow<List<ARHistoryPoint>> = _arHistoryPoints.asStateFlow()

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

        // Load initial history
        refreshHistory()
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

    private fun addTrackingPoint(location: Location) {
        val (data, min, max) = historyRepository.addTrackingPoint(
            trackingMutableData,
            barometerManager.height.value.toFloat(),
            location.altitude.toFloat()
        )
        _trackingDataSet.value = data
        _trackingMin.value = min
        _trackingMax.value = max
    }

    fun searchForPeaks() {
        val loc = locationManager.location.value ?: return
        viewModelScope.launch {
            val results = peakFinder.searchPeaks(loc, _mountainsData.value, _peaks.value)
            _peaks.value = results
        }
    }

    fun loadARHistoryPoints() {
        val userLoc = locationManager.location.value ?: return
        viewModelScope.launch {
            // Time-filter first, area-filter second. Mirrors the
            // iOS port's correction: we want "the user's 10 most
            // recent points, of which we render the nearby ones",
            // NOT "any of the last 200 points that happen to be
            // within range" — the latter put 6-month-old markers
            // back into the AR scene when the user wandered close
            // to an old position.
            val items = historyRepository.getRecentItems(10)
            val maxDistance = 1000.0

            _arHistoryPoints.value = items.mapNotNull { item ->
                val distance = GeoCalculations.distanceBetween(
                    userLoc.latitude, userLoc.longitude,
                    item.gpsLatitude, item.gpsLongitude
                )
                if (distance > maxDistance || distance < 1) return@mapNotNull null

                val bearing = GeoCalculations.bearing(
                    userLoc.latitude, userLoc.longitude,
                    item.gpsLatitude, item.gpsLongitude
                )

                ARHistoryPoint.create(
                    date = Date(item.recordDate),
                    latitude = item.gpsLatitude,
                    longitude = item.gpsLongitude,
                    gpsAltitude = item.gpsAltitude,
                    barometerAltitude = item.barometerAltitude,
                    pressure = item.barometerPressure,
                    speed = item.gpsVelocity,
                    distance = distance,
                    bearing = bearing
                )
            }
        }
    }

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
