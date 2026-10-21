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
    val steps: String = Format.DASH,
    val stepsAvailable: Boolean = true,
    val stepsNeedPermission: Boolean = false,
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
) : AndroidViewModel(app) {

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _locationAccess = MutableStateFlow(if (gnss.hasPermission()) LocationAccess.GRANTED else LocationAccess.UNKNOWN)
    val locationAccess: StateFlow<LocationAccess> = _locationAccess.asStateFlow()
    private val stepsPermission = MutableStateFlow(false)
    private val restart = MutableStateFlow(0)

    private val _savedFix = MutableStateFlow<SavedFix?>(null)

    val gnssState: StateFlow<GnssSnapshot> = combine(_locationAccess, restart) { access, _ -> access }
        .flatMapLatest { access ->
            if (access == LocationAccess.GRANTED) gnss.updates() else flowOf(GnssSnapshot(providerEnabled = gnss.isGpsEnabled()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GnssSnapshot())

    val sensorState: StateFlow<SensorSnapshot> = stepsPermission
        .flatMapLatest { sensors.updates(includeSteps = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorSnapshot(sensors.availability))

    val catalog: StateFlow<OrbitCatalog> = orbits.catalog

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    private val stepsToday = MutableStateFlow<Long?>(null)

    /** Heading in degrees (true or magnetic as configured), at sensor rate (≤ 10 Hz). */
    val heading: StateFlow<Float?> = combine(sensorState, gnssState, settings) { s, g, cfg -> resolveHeading(s, g.location, cfg) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dashboard: StateFlow<DashboardState> = combine(
        gnssState, sensorState, settings, ticker, stepsToday,
    ) { g, s, cfg, now, steps -> buildDashboard(g, s, cfg, now, steps) }
        .sample(500)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    private val _debug = MutableStateFlow(DebugInfo())
    val debug: StateFlow<DebugInfo> = _debug.asStateFlow()

    val scene: StateFlow<GlobeScene> = combine(gnssState, catalog, settings, ticker) { g, c, cfg, now -> buildScene(g, c, cfg, now) }
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
        viewModelScope.launch {
            sensorState.map { it.stepsSinceBoot }.collect { sinceBoot ->
                if (sinceBoot != null) {
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    stepsToday.value = settingsRepo.stepsToday(sinceBoot, today)
                }
            }
        }
        silentUpdateCheck()
    }

    // --- Permissions & actions ----------------------------------------------------------------

    fun onLocationPermission(granted: Boolean) {
        _locationAccess.value = if (granted) LocationAccess.GRANTED else LocationAccess.DENIED
    }

    fun onStepsPermission(granted: Boolean) {
        stepsPermission.value = granted
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
            _update.value = when (val r = updates.check(force = false)) {
                UpdateCheck.UpToDate -> UpdateUiState.UpToDate.also { _updateBadge.value = false }
                is UpdateCheck.Available -> UpdateUiState.Available(r.info)
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

    // --- Derivations ----------------------------------------------------------------------------

    /** Location used to place satellites: the live fix, else the last known / saved one. */
    private fun referenceLocation(g: GnssSnapshot): Geodetic? {
        g.location?.let { return Geodetic(it.latitude, it.longitude, (it.altitudeEllipsoidM ?: 0.0) / 1000.0) }
        _savedFix.value?.let { return Geodetic(it.latitude, it.longitude, (it.altitudeMslM ?: 0.0) / 1000.0) }
        return gnss.lastKnownLocation()?.let { Geodetic(it.latitude, it.longitude, it.altitude / 1000.0) }
    }

    private fun isLocked(g: GnssSnapshot): Boolean {
        val fix = g.location ?: return false
        val fresh = SystemClock.elapsedRealtime() - fix.elapsedRealtimeMillis < LOCK_TIMEOUT_MS
        return fresh && (fix.accuracyM ?: Float.MAX_VALUE) < LOCK_ACCURACY_M
    }

    private fun declination(lat: Double, lon: Double, altM: Double, time: Long): Float =
        GeomagneticField(lat.toFloat(), lon.toFloat(), altM.toFloat(), time).declination

    private fun resolveHeading(s: SensorSnapshot, fix: LocationFix?, cfg: AppSettings): Float? {
        val ref = fix ?: _savedFix.value?.let { LocationFix(it.latitude, it.longitude, null, it.altitudeMslM, null, null, null, null, it.timeMillis, 0) }
        val decl = ref?.let { declination(it.latitude, it.longitude, it.altitudeMslM ?: 0.0, System.currentTimeMillis()) } ?: 0f
        val moving = (fix?.speedMps ?: 0f) * 3.6f > 2f
        val gpsBearing = fix?.bearingDeg
        val trueHeading: Float = if (cfg.useGpsBearingWhenMoving && moving && gpsBearing != null) {
            gpsBearing
        } else {
            val mag = s.headingMagneticDeg ?: return null
            mag + decl
        }
        val h = if (cfg.headingReference == HeadingReference.TRUE) trueHeading else trueHeading - decl
        return ((h % 360f) + 360f) % 360f
    }

    private fun buildDashboard(g: GnssSnapshot, s: SensorSnapshot, cfg: AppSettings, now: Long, steps: Long?): DashboardState {
        val fix = g.location
        val saved = _savedFix.value
        val lat = fix?.latitude ?: saved?.latitude
        val lon = fix?.longitude ?: saved?.longitude
        val fixTime = fix?.timeMillis ?: saved?.timeMillis
        val locked = isLocked(g)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val heading = resolveHeading(s, fix, cfg)
        val decl = if (lat != null && lon != null) declination(lat, lon, fix?.altitudeMslM ?: 0.0, now) else null
        val sats = g.satellites.filter { it.cn0DbHz > 0f || it.usedInFix }
        val used = g.satellites.count { it.usedInFix }
        val altitudeM = fix?.altitudeMslM ?: g.nmeaAltitudeMslM ?: fix?.altitudeEllipsoidM

        val bars = sats.sortedWith(compareBy({ it.constellation.order }, { it.svid }))
            .map { SignalBar(it.label, it.constellation, it.cn0DbHz, it.usedInFix) }
        val legend = sats.groupBy { it.constellation }.toSortedMap(compareBy { it.order })
            .map { (c, list) -> LegendEntry(c, list.count { it.usedInFix }, list.size) }

        val av = s.available
        return DashboardState(
            latitude = lat?.let { Format.coordinate(it, true, cfg.coordinateFormat) } ?: Format.DASH,
            latitudeSecondary = lat?.let { Format.coordinateSecondary(it, true, cfg.coordinateFormat) } ?: "",
            longitude = lon?.let { Format.coordinate(it, false, cfg.coordinateFormat) } ?: Format.DASH,
            longitudeSecondary = lon?.let { Format.coordinateSecondary(it, false, cfg.coordinateFormat) } ?: "",
            lastFix = fixTime?.let { timeFmt.format(Date(it)) } ?: Format.DASH,
            fixIsStale = !locked,
            speed = fix?.speedMps?.let { Format.fixed(Format.speed(it.toDouble(), cfg.speedUnit), 1) } ?: Format.DASH,
            speedUnit = cfg.speedUnit.label,
            altitude = altitudeM?.let { Format.fixed(Format.altitude(it, cfg.altitudeUnit), 1) } ?: Format.DASH,
            altitudeUnit = cfg.altitudeUnit.label,
            accel = s.accelG?.let { Format.fixed(it.toDouble(), 2) } ?: Format.DASH,
            accelAvailable = av.linearAcceleration,
            pitch = s.pitchDeg?.let { "${kotlin.math.abs(it.roundToInt())}°" } ?: Format.DASH,
            pitchDir = s.pitchDeg?.let { if (it >= 0) "up" else "down" } ?: "",
            roll = s.rollDeg?.let { "${kotlin.math.abs(it.roundToInt())}°" } ?: Format.DASH,
            rollDir = s.rollDeg?.let { if (it < 0) "left" else "right" } ?: "",
            attitudeAvailable = av.rotation,
            rotation = s.rotationDps?.let { it.roundToInt().toString() } ?: Format.DASH,
            rotationDir = when (s.rotationDirection) {
                1 -> "°/s L"
                -1 -> "°/s R"
                else -> "°/s"
            },
            rotationAvailable = av.gyroscope,
            pressure = s.pressureHpa?.let { Format.pressureText(it.toDouble(), cfg.pressureUnit) } ?: Format.DASH,
            pressureUnit = cfg.pressureUnit.label,
            pressureArrow = s.pressureTrendHpaPerHour?.let {
                when {
                    it > 0.1f -> "↑"
                    it < -0.1f -> "↓"
                    else -> "→"
                }
            } ?: "",
            pressureAvailable = av.pressure,
            magField = when {
                s.magTotalUt == null -> Format.DASH
                s.magHorizontalUt == null -> s.magTotalUt.roundToInt().toString()
                else -> "${s.magHorizontalUt.roundToInt()}/${s.magTotalUt.roundToInt()}"
            },
            declination = decl?.let { Format.declination(it.toDouble()) } ?: Format.DASH,
            magAvailable = av.magnetic,
            steps = steps?.let { Format.thousands(it) } ?: Format.DASH,
            stepsAvailable = av.stepCounter,
            stepsNeedPermission = av.stepCounter && !stepsPermission.value,
            headingText = heading?.let { "${it.roundToInt() % 360}°" } ?: Format.DASH,
            orientation = heading?.let { Format.compassPoint(it.toDouble()) } ?: Format.DASH,
            error = if (locked) fix?.accuracyM?.let { Format.fixed(it.toDouble(), 1) } ?: Format.DASH else Format.DASH,
            usedCount = if (fix != null) used else 0,
            visibleCount = g.satellites.size,
            fixType = when {
                g.fixType != FixType.NONE -> g.fixType
                locked && fix?.altitudeEllipsoidM != null -> FixType.FIX_3D
                locked -> FixType.FIX_2D
                else -> FixType.NONE
            },
            accuracyText = fix?.accuracyM?.let { "±" + Format.fixed(it.toDouble(), 1) + " m" } ?: "",
            bars = bars,
            legend = legend,
            hasFix = locked,
            providerEnabled = g.providerEnabled,
        )
    }

    private fun buildScene(g: GnssSnapshot, catalog: OrbitCatalog, cfg: AppSettings, now: Long): GlobeScene {
        val ref = referenceLocation(g)
        val jd = jdFromEpochMillis(now)
        val gmst = gstime(jd)
        val (sunLat, sunLon) = Geo.subSolarPoint(jd)

        data class Pred(val obj: OrbitalObject, val geo: Geodetic, val az: Double, val el: Double)
        val predictions = ArrayList<Pred>(catalog.satellites.size)
        for (obj in catalog.satellites) {
            val sv = obj.satrec.propagateJd(jd) ?: continue
            val ecef = Geo.temeToEcef(sv.position, gmst)
            val geo = Geo.ecefToGeodetic(ecef)
            val look = ref?.let { Geo.lookAngles(it, ecef) }
            predictions += Pred(obj, geo, look?.azimuthDeg ?: 0.0, look?.elevationDeg ?: -90.0)
        }

        val measured = g.satellites.filter { it.elevationDeg > 0f || it.azimuthDeg > 0f }
        val matches = if (ref != null && predictions.isNotEmpty()) {
            SatelliteMatcher.match(
                measured.map { MeasuredDirection(it.key, it.constellation, it.svid, it.azimuthDeg.toDouble(), it.elevationDeg.toDouble()) },
                predictions.mapIndexedNotNull { i, p ->
                    val c = p.obj.constellation ?: return@mapIndexedNotNull null
                    if (p.el < -5.0) null else PredictedDirection(i, c, p.obj.prn, p.az, p.el)
                },
            )
        } else {
            emptyList()
        }
        val matchByKey = matches.associateBy { it.measuredKey }

        fun radiusFor(c: Constellation, altKm: Double, geosync: Boolean): Pair<Float, Boolean> = when {
            cfg.trueScaleOrbits -> (1.0 + altKm / Geo.MEAN_EARTH_RADIUS_KM).toFloat() to false
            geosync -> Constellation.EDGE_RADIUS to true
            else -> c.displayRadius to false
        }

        val ranked = measured.sortedByDescending { it.cn0DbHz }.withIndex().associate { it.value.key to it.index }
        val placed = measured.mapNotNull { obs ->
            val m = matchByKey[obs.key]
            if (m != null) {
                val p = predictions[m.predictedId]
                val (r, edge) = radiusFor(obs.constellation, p.geo.heightKm, p.obj.isGeosynchronous)
                GlobeSatellite(obs.key, obs.label, obs.constellation, obs.usedInFix, obs.cn0DbHz, p.geo.latDeg, p.geo.lonDeg, r, edge, true, ranked[obs.key] ?: 0)
            } else if (ref != null) {
                val geosync = obs.constellation in GEO_CONSTELLATIONS
                val alt = obs.constellation.nominalAltitudeKm
                val (lat, lon) = Geo.subPointFromLookAngles(ref.latDeg, ref.lonDeg, obs.azimuthDeg.toDouble(), obs.elevationDeg.toDouble(), alt)
                val (r, edge) = radiusFor(obs.constellation, alt, geosync)
                GlobeSatellite(obs.key, obs.label, obs.constellation, obs.usedInFix, obs.cn0DbHz, lat, lon, r, edge, false, ranked[obs.key] ?: 0)
            } else {
                null
            }
        }

        val matchedIds = matches.map { it.predictedId }.toSet()
        val background = if (cfg.showAllConstellationSatellites) {
            predictions.withIndex().filter { it.index !in matchedIds }.map { (_, p) ->
                val c = p.obj.constellation ?: Constellation.OTHER
                val (r, _) = radiusFor(c, p.geo.heightKm, p.obj.isGeosynchronous)
                BackgroundSatellite(p.geo.latDeg, p.geo.lonDeg, r, c)
            }
        } else {
            emptyList()
        }

        publishDebug(matches.map { m ->
            val obs = measured.first { it.key == m.measuredKey }
            val p = predictions[m.predictedId]
            MatchDebug(obs.label, obs.azimuthDeg.toDouble(), obs.elevationDeg.toDouble(), p.az, p.el, m.separationDeg, p.obj.name, m.byPrn)
        }, catalog, g, now)

        return GlobeScene(
            userLat = g.location?.latitude ?: ref?.latDeg,
            userLon = g.location?.longitude ?: ref?.lonDeg,
            locked = isLocked(g),
            satellites = placed,
            background = background,
            style = cfg.globeStyle,
            dayNight = cfg.dayNightTerminator,
            signalWaves = cfg.signalWaves,
            showUnused = cfg.showUnusedSatellites,
            trueScale = cfg.trueScaleOrbits,
            issTimeLapse = cfg.issTimeLapse,
            iss = catalog.iss,
            orbitsAvailable = !catalog.isEmpty,
            subSolarLat = sunLat,
            subSolarLon = sunLon,
        )
    }

    private fun publishDebug(matches: List<MatchDebug>, catalog: OrbitCatalog, g: GnssSnapshot, now: Long) {
        val gps = matches.filter { it.label.startsWith("G") }
        _debug.value = DebugInfo(
            matches = matches.sortedBy { it.label },
            catalogSize = catalog.satellites.size,
            orbitAge = catalog.updatedAtMillis?.let { Format.age(now - it) } ?: "never",
            gpsMatched = gps.size,
            gpsWithinOneDegree = gps.count { kotlin.math.abs(it.measuredAz - it.predictedAz).let { d -> minOf(d, 360 - d) } <= 1.0 && kotlin.math.abs(it.measuredEl - it.predictedEl) <= 1.0 },
            dop = g.dop?.let { "P ${it.pdop ?: "-"} · H ${it.hdop ?: "-"} · V ${it.vdop ?: "-"}" } ?: Format.DASH,
            nmeaAltitude = g.nmeaAltitudeMslM?.let { Format.fixed(it, 1) + " m" } ?: Format.DASH,
        )
    }

    val versionName: String = BuildConfig.VERSION_NAME

    companion object {
        const val LOCK_ACCURACY_M = 50f
        const val LOCK_TIMEOUT_MS = 10_000L
        private val GEO_CONSTELLATIONS = setOf(Constellation.SBAS, Constellation.QZSS, Constellation.NAVIC)
    }
}
