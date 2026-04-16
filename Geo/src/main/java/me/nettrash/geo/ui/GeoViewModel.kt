package me.nettrash.geo.ui

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.nettrash.geo.data.db.HistoryItem
import me.nettrash.geo.data.model.ARHistoryPoint
import me.nettrash.geo.data.model.DataItem
import me.nettrash.geo.data.model.DataPoint
import me.nettrash.geo.data.model.MountainData
import me.nettrash.geo.data.model.MountainInfo
import me.nettrash.geo.data.model.NearbyPeak
import me.nettrash.geo.data.repository.HistoryRepository
import me.nettrash.geo.location.LocationManager
import me.nettrash.geo.sensor.BarometerManager
import me.nettrash.geo.sensor.DeviceMotionManager
import me.nettrash.geo.util.GeoCalculations
import me.nettrash.geo.util.MountainLoader
import me.nettrash.geo.util.PeakFinder
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class GeoViewModel @Inject constructor(
    val barometerManager: BarometerManager,
    val locationManager: LocationManager,
    val motionManager: DeviceMotionManager,
    private val historyRepository: HistoryRepository,
    private val mountainLoader: MountainLoader,
    private val peakFinder: PeakFinder
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

        // Set up barometer callbacks
        barometerManager.onDataUpdated = {
            locationManager.onBarometerUpdated(
                barometerManager.pressure.value,
                barometerManager.height.value
            )
        }
        barometerManager.start()

        // Set up location callbacks
        locationManager.onRecordHistory = { loc ->
            recordHistory(loc)
        }
        locationManager.onTrackingUpdate = { loc ->
            addTrackingPoint(loc)
        }
        locationManager.startLocationUpdates()

        // Load initial history
        refreshHistory()
    }

    fun refreshHistory() {
        viewModelScope.launch {
            val items = historyRepository.getItemsSince()
            _historyItems.value = items

            val (pData, pMin, pMax) = historyRepository.buildPressureDataSet()
            _pressureDataSet.value = pData
            _pressureMin.value = pMin
            _pressureMax.value = pMax

            val (bData, bMin, bMax) = historyRepository.buildBarometerAltitudeDataSet()
            _barometerAltDataSet.value = bData
            _barometerAltMin.value = bMin
            _barometerAltMax.value = bMax

            val (gData, gMin, gMax) = historyRepository.buildGPSAltitudeDataSet()
            _gpsAltDataSet.value = gData
            _gpsAltMin.value = gMin
            _gpsAltMax.value = gMax
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
            refreshHistory()
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
            val items = historyRepository.getRecentItems(200)
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

    override fun onCleared() {
        super.onCleared()
        barometerManager.stop()
        locationManager.stopLocationUpdates()
        motionManager.stop()
    }
}
