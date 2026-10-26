package io.github.divanguz_alt.zenit.ui

import android.app.Application
import android.hardware.GeomagneticField
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.divanguz_alt.zenit.BuildConfig
import io.github.divanguz_alt.zenit.globe.BackgroundSatellite
import io.github.divanguz_alt.zenit.globe.GlobeSatellite
import io.github.divanguz_alt.zenit.globe.GlobeScene
import io.github.divanguz_alt.zenit.gnss.Constellation
import io.github.divanguz_alt.zenit.gnss.FixType
import io.github.divanguz_alt.zenit.gnss.GnssRepository
import io.github.divanguz_alt.zenit.gnss.GnssSnapshot
import io.github.divanguz_alt.zenit.gnss.LocationFix
import io.github.divanguz_alt.zenit.orbits.Geo
import io.github.divanguz_alt.zenit.orbits.Geodetic
import io.github.divanguz_alt.zenit.orbits.MeasuredDirection
import io.github.divanguz_alt.zenit.orbits.OrbitCatalog
import io.github.divanguz_alt.zenit.orbits.OrbitRefreshWorker
import io.github.divanguz_alt.zenit.orbits.OrbitRepository
import io.github.divanguz_alt.zenit.orbits.OrbitalObject
import io.github.divanguz_alt.zenit.orbits.PredictedDirection
import io.github.divanguz_alt.zenit.orbits.RefreshResult
import io.github.divanguz_alt.zenit.orbits.SatelliteMatcher
import io.github.divanguz_alt.zenit.orbits.gstime
import io.github.divanguz_alt.zenit.orbits.jdFromEpochMillis
import io.github.divanguz_alt.zenit.sensors.SensorRepository
import io.github.divanguz_alt.zenit.sensors.SensorSnapshot
import io.github.divanguz_alt.zenit.settings.AppSettings
import io.github.divanguz_alt.zenit.settings.HeadingReference
import io.github.divanguz_alt.zenit.settings.SavedFix
import io.github.divanguz_alt.zenit.settings.SettingsRepository
import io.github.divanguz_alt.zenit.update.UpdateCheck
import io.github.divanguz_alt.zenit.update.UpdateInfo
import io.github.divanguz_alt.zenit.update.UpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

enum class LocationAccess { GRANTED, DENIED, UNKNOWN }

data class SignalBar(val label: String, val constellation: Constellation, val cn0: Float, val used: Boolean)
data class LegendEntry(val constellation: Constellation, val used: Int, val total: Int)

/** Everything the dashboard renders, already formatted. Recomputed at most twice a second. */
data class DashboardState(
    val latitude: String = Format.DASH,
    val latitudeSecondary: String = "",
    val longitude: String = Format.DASH,
    val longitudeSecondary: String = "",
    val lastFix: String = Format.DASH,
    val fixIsStale: Boolean = false,
    val speed: String = Format.DASH,
    val speedUnit: String = "km/h",
    val altitude: String = Format.DASH,
    val altitudeUnit: String = "m",
    val accel: String = Format.DASH,
    val accelAvailable: Boolean = true,
    val pitch: String = Format.DASH,
    val pitchDir: String = "",
    val roll: String = Format.DASH,
    val rollDir: String = "",
    val attitudeAvailable: Boolean = true,
    val rotation: String = Format.DASH,
    val rotationDir: String = "°/s",
    val rotationAvailable: Boolean = true,
    val pressure: String = Format.DASH,
    val pressureUnit: String = "hPa",
    val pressureArrow: String = "",
    val pressureAvailable: Boolean = true,
    val magField: String = Format.DASH,
    val declination: String = Format.DASH,
    val magAvailable: Boolean = true,
    val headingText: String = Format.DASH,
    val orientation: String = Format.DASH,
    val error: String = Format.DASH,
    val usedCount: Int = 0,
    val visibleCount: Int = 0,
    val fixType: FixType = FixType.NONE,
    val accuracyText: String = "",
    val bars: List<SignalBar> = emptyList(),
    val legend: List<LegendEntry> = emptyList(),
    val hasFix: Boolean = false,
    val providerEnabled: Boolean = true,
)

data class MatchDebug(
    val label: String,
    val measuredAz: Double,
    val measuredEl: Double,
    val predictedAz: Double,
    val predictedEl: Double,
    val separation: Double,
    val objectName: String,
    val byPrn: Boolean,
)

data class DebugInfo(
    val matches: List<MatchDebug> = emptyList(),
    val catalogSize: Int = 0,
    val orbitAge: String = Format.DASH,
    val gpsWithinOneDegree: Int = 0,
    val gpsMatched: Int = 0,
    val dop: String = Format.DASH,
    val nmeaAltitude: String = Format.DASH,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateUiState
    data class NeedsInstallPermission(val info: UpdateInfo) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    app: Application,
    private val gnss: GnssRepository,
    private val sensors: SensorRepository,
    private val orbits: OrbitRepository,
    private val settingsRepo: SettingsRepository,
    private val updates: UpdateRepository,
    private val derive: Derivations,
) : AndroidViewModel(app) {

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _locationAccess = MutableStateFlow(if (gnss.hasPermission()) LocationAccess.GRANTED else LocationAccess.UNKNOWN)
    val locationAccess: StateFlow<LocationAccess> = _locationAccess.asStateFlow()
    private val restart = MutableStateFlow(0)

    private val _savedFix = MutableStateFlow<SavedFix?>(null)

    val gnssState: StateFlow<GnssSnapshot> = combine(_locationAccess, restart) { access, _ -> access }
        .flatMapLatest { access ->
            if (access == LocationAccess.GRANTED) gnss.updates() else flowOf(GnssSnapshot(providerEnabled = gnss.isGpsEnabled()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GnssSnapshot())

    val sensorState: StateFlow<SensorSnapshot> = sensors.updates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorSnapshot(sensors.availability))

    val catalog: StateFlow<OrbitCatalog> = orbits.catalog

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    /** Heading in degrees (true or magnetic as configured), at sensor rate (≤ 10 Hz). */
    val heading: StateFlow<Float?> = combine(sensorState, gnssState, settings) { s, g, cfg -> derive.resolveHeading(s, g.location, cfg, _savedFix.value) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dashboard: StateFlow<DashboardState> = combine(
        gnssState, sensorState, settings, ticker,
    ) { g, s, cfg, now -> derive.buildDashboard(g, s, cfg, now, _savedFix.value) }
        .sample(500)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    private val _debug = MutableStateFlow(DebugInfo())
    val debug: StateFlow<DebugInfo> = _debug.asStateFlow()

    val scene: StateFlow<GlobeScene> = combine(gnssState, catalog, settings, ticker) { g, c, cfg, now ->
        derive.buildScene(g, c, cfg, now, _savedFix.value).also { _debug.value = it.debug }.scene
    }
        .sample(250)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobeScene())

    private val _update = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()
    private val _updateBadge = MutableStateFlow(false)
    val updateBadge: StateFlow<Boolean> = _updateBadge.asStateFlow()
    private var downloadJob: Job? = null

    private val _refreshing = MutableStateFlow(false)
    val refreshingOrbits: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _orbitMessage = MutableStateFlow<String?>(null)
    val orbitMessage: StateFlow<String?> = _orbitMessage.asStateFlow()

    init {
        viewModelScope.launch { _savedFix.value = settingsRepo.savedFix() }
        OrbitRefreshWorker.schedule(app)
        viewModelScope.launch {
            // Wait for the disk cache to load before deciding whether a first download is needed.
            orbits.catalog.first { it.loaded }
            if (orbits.isStale()) OrbitRefreshWorker.runSoon(app)
        }
        // Persist the latest fix every 15 s so process death restores it.
        viewModelScope.launch {
            gnssState.map { it.location }.sample(15_000).collect { fix ->
                if (fix != null) {
                    val saved = SavedFix(fix.latitude, fix.longitude, fix.altitudeMslM, fix.timeMillis)
                    _savedFix.value = saved
                    settingsRepo.saveFix(saved)
                }
            }
        }
        silentUpdateCheck()
    }

    // --- Permissions & actions ----------------------------------------------------------------

    fun onLocationPermission(granted: Boolean) {
        _locationAccess.value = if (granted) LocationAccess.GRANTED else LocationAccess.DENIED
    }

    /** Re-register GNSS listeners; the globe returns to the search animation. */
    fun reacquire() = restart.update { it + 1 }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    suspend fun shouldShowLocationRationale(): Boolean = !settingsRepo.locationRationaleShown()
    fun markLocationRationaleShown() = viewModelScope.launch { settingsRepo.setLocationRationaleShown() }

    fun refreshOrbits() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            _orbitMessage.value = when (val r = orbits.refresh(force = true)) {
                is RefreshResult.Updated -> "Updated ${r.groups} groups"
                RefreshResult.UpToDate -> "Already up to date (CelesTrak allows one download per 2 h)"
                is RefreshResult.Failed -> "Refresh failed: ${r.message}"
            }
            _refreshing.value = false
        }
    }

    // --- Updates --------------------------------------------------------------------------------

    private fun silentUpdateCheck() {
        viewModelScope.launch {
            if (System.currentTimeMillis() - updates.lastCheckMillis() < 24 * 3_600_000L) {
                // Still within a day: reuse the cached answer for the badge.
                (updates.check(force = false) as? UpdateCheck.Available)?.let { _updateBadge.value = true }
                return@launch
            }
            if (updates.check(force = true) is UpdateCheck.Available) _updateBadge.value = true
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _update.value = UpdateUiState.Checking
            // A tap always asks GitHub; the one-hour cache is only for the silent launch check.
            _update.value = when (val r = updates.check(force = true)) {
                UpdateCheck.UpToDate -> UpdateUiState.UpToDate.also { _updateBadge.value = false }
                is UpdateCheck.Available -> UpdateUiState.Available(r.info).also { _updateBadge.value = true }
                is UpdateCheck.Failed -> UpdateUiState.Failed(r.message)
            }
        }
    }

    fun downloadUpdate(info: UpdateInfo) {
        if (!updates.canInstall()) {
            _update.value = UpdateUiState.NeedsInstallPermission(info)
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _update.value = UpdateUiState.Downloading(info, 0f)
            try {
                val apk = updates.download(info) { p -> _update.value = UpdateUiState.Downloading(info, p) }
                updates.install(apk)
                _update.value = UpdateUiState.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.value = UpdateUiState.Failed(e.message ?: "Update failed")
            }
        }
    }

    fun dismissUpdate() {
        downloadJob?.cancel()
        _update.value = UpdateUiState.Idle
    }

    val versionName: String = BuildConfig.VERSION_NAME

}
