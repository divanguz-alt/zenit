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
import javax.inject.Singleton
import kotlin.math.roundToInt

data class SceneResult(val scene: GlobeScene, val debug: DebugInfo)

/**
 * Turns raw GNSS, sensor and orbit data into what the screens draw. Shared by the phone UI
 * ([MainViewModel]) and the Android Auto screen, so both show exactly the same thing.
 */
@Singleton
class Derivations @Inject constructor(private val gnss: GnssRepository) {

    /** Location used to place satellites: the live fix, else the last known / saved one. */
    fun referenceLocation(g: GnssSnapshot, saved: SavedFix?): Geodetic? {
        g.location?.let { return Geodetic(it.latitude, it.longitude, (it.altitudeEllipsoidM ?: 0.0) / 1000.0) }
        saved?.let { return Geodetic(it.latitude, it.longitude, (it.altitudeMslM ?: 0.0) / 1000.0) }
        return gnss.lastKnownLocation()?.let { Geodetic(it.latitude, it.longitude, it.altitude / 1000.0) }
    }

    fun isLocked(g: GnssSnapshot): Boolean {
        val fix = g.location ?: return false
        val fresh = SystemClock.elapsedRealtime() - fix.elapsedRealtimeMillis < LOCK_TIMEOUT_MS
        return fresh && (fix.accuracyM ?: Float.MAX_VALUE) < LOCK_ACCURACY_M
    }

    private fun declination(lat: Double, lon: Double, altM: Double, time: Long): Float =
        GeomagneticField(lat.toFloat(), lon.toFloat(), altM.toFloat(), time).declination

    fun resolveHeading(s: SensorSnapshot, fix: LocationFix?, cfg: AppSettings, saved: SavedFix?): Float? {
        val ref = fix ?: saved?.let { LocationFix(it.latitude, it.longitude, null, it.altitudeMslM, null, null, null, null, it.timeMillis, 0) }
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

    fun buildDashboard(g: GnssSnapshot, s: SensorSnapshot, cfg: AppSettings, now: Long, saved: SavedFix?): DashboardState {
        val fix = g.location
        val lat = fix?.latitude ?: saved?.latitude
        val lon = fix?.longitude ?: saved?.longitude
        val fixTime = fix?.timeMillis ?: saved?.timeMillis
        val locked = isLocked(g)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val heading = resolveHeading(s, fix, cfg, saved)
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

    fun buildScene(g: GnssSnapshot, catalog: OrbitCatalog, cfg: AppSettings, now: Long, saved: SavedFix?): SceneResult {
        val ref = referenceLocation(g, saved)
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

        val debug = debugInfo(matches.map { m ->
            val obs = measured.first { it.key == m.measuredKey }
            val p = predictions[m.predictedId]
            MatchDebug(obs.label, obs.azimuthDeg.toDouble(), obs.elevationDeg.toDouble(), p.az, p.el, m.separationDeg, p.obj.name, m.byPrn)
        }, catalog, g, now)

        val scene = GlobeScene(
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
        return SceneResult(scene, debug)
    }

    private fun debugInfo(matches: List<MatchDebug>, catalog: OrbitCatalog, g: GnssSnapshot, now: Long): DebugInfo {
        val gps = matches.filter { it.label.startsWith("G") }
        return DebugInfo(
            matches = matches.sortedBy { it.label },
            catalogSize = catalog.satellites.size,
            orbitAge = catalog.updatedAtMillis?.let { Format.age(now - it) } ?: "never",
            gpsMatched = gps.size,
            gpsWithinOneDegree = gps.count { kotlin.math.abs(it.measuredAz - it.predictedAz).let { d -> minOf(d, 360 - d) } <= 1.0 && kotlin.math.abs(it.measuredEl - it.predictedEl) <= 1.0 },
            dop = g.dop?.let { "P ${it.pdop ?: "-"} · H ${it.hdop ?: "-"} · V ${it.vdop ?: "-"}" } ?: Format.DASH,
            nmeaAltitude = g.nmeaAltitudeMslM?.let { Format.fixed(it, 1) + " m" } ?: Format.DASH,
        )
    }

    companion object {
        const val LOCK_ACCURACY_M = 50f
        const val LOCK_TIMEOUT_MS = 10_000L
        private val GEO_CONSTELLATIONS = setOf(Constellation.SBAS, Constellation.QZSS, Constellation.NAVIC)
    }
}
