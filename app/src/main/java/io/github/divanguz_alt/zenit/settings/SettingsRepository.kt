package io.github.divanguz_alt.zenit.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class GlobeStyle { ILLUSTRATED, REALISTIC }
enum class SpeedUnit(val label: String) { KMH("km/h"), MPH("mph"), KNOTS("kn"), MPS("m/s") }
enum class AltitudeUnit(val label: String) { METERS("m"), FEET("ft") }
enum class PressureUnit(val label: String) { HPA("hPa"), INHG("inHg"), MMHG("mmHg") }
enum class CoordinateFormat(val label: String) { DDM("DDM"), DMS("DMS"), DD("DD") }
enum class HeadingReference(val label: String) { TRUE("True"), MAGNETIC("Magnetic") }

data class AppSettings(
    val globeStyle: GlobeStyle = GlobeStyle.ILLUSTRATED,
    val dayNightTerminator: Boolean = false,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val altitudeUnit: AltitudeUnit = AltitudeUnit.METERS,
    val pressureUnit: PressureUnit = PressureUnit.HPA,
    val coordinateFormat: CoordinateFormat = CoordinateFormat.DDM,
    val headingReference: HeadingReference = HeadingReference.TRUE,
    val useGpsBearingWhenMoving: Boolean = true,
    val signalWaves: Boolean = true,
    val showUnusedSatellites: Boolean = true,
    val showAllConstellationSatellites: Boolean = false,
    val trueScaleOrbits: Boolean = false,
    val keepScreenOn: Boolean = false,
    val issTimeLapse: Boolean = false,
)

/** Last fix, restored after process death so the screen isn't empty while GNSS re-acquires. */
data class SavedFix(val latitude: Double, val longitude: Double, val altitudeMslM: Double?, val timeMillis: Long)

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    private object K {
        val globe = stringPreferencesKey("globe_style")
        val dayNight = booleanPreferencesKey("day_night")
        val speed = stringPreferencesKey("unit_speed")
        val altitude = stringPreferencesKey("unit_altitude")
        val pressure = stringPreferencesKey("unit_pressure")
        val coords = stringPreferencesKey("unit_coords")
        val heading = stringPreferencesKey("heading_ref")
        val gpsBearing = booleanPreferencesKey("gps_bearing")
        val waves = booleanPreferencesKey("signal_waves")
        val unused = booleanPreferencesKey("show_unused")
        val all = booleanPreferencesKey("show_all")
        val trueScale = booleanPreferencesKey("true_scale")
        val keepOn = booleanPreferencesKey("keep_screen_on")
        val timeLapse = booleanPreferencesKey("iss_time_lapse")

        val fixLat = doublePreferencesKey("fix_lat")
        val fixLon = doublePreferencesKey("fix_lon")
        val fixAlt = doublePreferencesKey("fix_alt")
        val fixTime = longPreferencesKey("fix_time")

        val stepsDay = stringPreferencesKey("steps_day")
        val stepsOffset = longPreferencesKey("steps_offset")

        val locationRationaleShown = booleanPreferencesKey("location_rationale_shown")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val updateCacheJson = stringPreferencesKey("update_cache_json")
        val dismissedUpdate = stringPreferencesKey("dismissed_update")
        val lastBaro = floatPreferencesKey("last_baro")
    }

    private val prefs: Flow<Preferences> = store.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }

    val settings: Flow<AppSettings> = prefs.map { p ->
        val d = AppSettings()
        AppSettings(
            globeStyle = p[K.globe].toEnum(d.globeStyle),
            dayNightTerminator = p[K.dayNight] ?: d.dayNightTerminator,
            speedUnit = p[K.speed].toEnum(d.speedUnit),
            altitudeUnit = p[K.altitude].toEnum(d.altitudeUnit),
            pressureUnit = p[K.pressure].toEnum(d.pressureUnit),
            coordinateFormat = p[K.coords].toEnum(d.coordinateFormat),
            headingReference = p[K.heading].toEnum(d.headingReference),
            useGpsBearingWhenMoving = p[K.gpsBearing] ?: d.useGpsBearingWhenMoving,
            signalWaves = p[K.waves] ?: d.signalWaves,
            showUnusedSatellites = p[K.unused] ?: d.showUnusedSatellites,
            showAllConstellationSatellites = p[K.all] ?: d.showAllConstellationSatellites,
            trueScaleOrbits = p[K.trueScale] ?: d.trueScaleOrbits,
            keepScreenOn = p[K.keepOn] ?: d.keepScreenOn,
            issTimeLapse = p[K.timeLapse] ?: d.issTimeLapse,
        )
    }.distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = settings.first()
        val n = transform(current)
        store.edit { p ->
            p[K.globe] = n.globeStyle.name
            p[K.dayNight] = n.dayNightTerminator
            p[K.speed] = n.speedUnit.name
            p[K.altitude] = n.altitudeUnit.name
            p[K.pressure] = n.pressureUnit.name
            p[K.coords] = n.coordinateFormat.name
            p[K.heading] = n.headingReference.name
            p[K.gpsBearing] = n.useGpsBearingWhenMoving
            p[K.waves] = n.signalWaves
            p[K.unused] = n.showUnusedSatellites
            p[K.all] = n.showAllConstellationSatellites
            p[K.trueScale] = n.trueScaleOrbits
            p[K.keepOn] = n.keepScreenOn
            p[K.timeLapse] = n.issTimeLapse
        }
    }

    // --- Last fix -----------------------------------------------------------------------------

    suspend fun savedFix(): SavedFix? {
        val p = prefs.first()
        val lat = p[K.fixLat] ?: return null
        val lon = p[K.fixLon] ?: return null
        return SavedFix(lat, lon, p[K.fixAlt], p[K.fixTime] ?: 0L)
    }

    suspend fun saveFix(fix: SavedFix) {
        store.edit { p ->
            p[K.fixLat] = fix.latitude
            p[K.fixLon] = fix.longitude
            fix.altitudeMslM?.let { p[K.fixAlt] = it } ?: p.remove(K.fixAlt)
            p[K.fixTime] = fix.timeMillis
        }
    }

    // --- Steps: daily offset over the since-boot counter ---------------------------------------

    /** Returns today's step count, rebasing the stored offset on a new day or after a reboot. */
    suspend fun stepsToday(sinceBoot: Long, today: String): Long {
        var result = 0L
        store.edit { p ->
            val day = p[K.stepsDay]
            val offset = p[K.stepsOffset]
            when {
                day != today || offset == null -> {
                    p[K.stepsDay] = today
                    p[K.stepsOffset] = sinceBoot
                    result = 0L
                }
                sinceBoot < offset -> {
                    // Device rebooted today: the counter restarted from zero.
                    p[K.stepsOffset] = 0L
                    result = sinceBoot
                }
                else -> result = sinceBoot - offset
            }
        }
        return result
    }

    // --- Misc flags ----------------------------------------------------------------------------

    suspend fun locationRationaleShown(): Boolean = prefs.first()[K.locationRationaleShown] ?: false
    suspend fun setLocationRationaleShown() = store.edit { it[K.locationRationaleShown] = true }

    suspend fun updateCache(): Pair<Long, String?> {
        val p = prefs.first()
        return (p[K.lastUpdateCheck] ?: 0L) to p[K.updateCacheJson]
    }

    suspend fun saveUpdateCache(time: Long, json: String?) = store.edit { p ->
        p[K.lastUpdateCheck] = time
        if (json != null) p[K.updateCacheJson] = json else p.remove(K.updateCacheJson)
    }

    suspend fun dismissedUpdate(): String? = prefs.first()[K.dismissedUpdate]
    suspend fun setDismissedUpdate(tag: String) = store.edit { it[K.dismissedUpdate] = tag }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
