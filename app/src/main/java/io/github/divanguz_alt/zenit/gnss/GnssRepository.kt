package io.github.divanguz_alt.zenit.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeEllipsoidM: Double?,
    val altitudeMslM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val accuracyM: Float?,
    val verticalAccuracyM: Float?,
    val timeMillis: Long,
    val elapsedRealtimeMillis: Long,
)

data class GnssSnapshot(
    val providerEnabled: Boolean = true,
    val location: LocationFix? = null,
    val satellites: List<SatelliteObservation> = emptyList(),
    val fixType: FixType = FixType.NONE,
    val dop: Dop? = null,
    /** Raw MSL altitude from the most recent GGA, used when AltitudeConverter is unavailable. */
    val nmeaAltitudeMslM: Double? = null,
)

/**
 * Location (GPS provider, 1 Hz), satellite status and NMEA, merged into [GnssSnapshot]s.
 * Callbacks run on a dedicated thread; emission is conflated so slow collectors only see the latest.
 */
@Singleton
class GnssRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): Location? = if (!hasPermission()) null else
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    fun updates(): Flow<GnssSnapshot> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val thread = HandlerThread("zenit-gnss").apply { start() }
        val handler = Handler(thread.looper)
        val altitudeConverter = if (Build.VERSION.SDK_INT >= 34) AltitudeConverter() else null
        var state = GnssSnapshot(providerEnabled = isGpsEnabled())
        fun publish(s: GnssSnapshot) {
            state = s
            trySend(s)
        }

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                var msl: Double? = null
                if (altitudeConverter != null && location.hasAltitude() && Build.VERSION.SDK_INT >= 34) {
                    msl = try {
                        // Disk-backed geoid lookup; we're on the GNSS handler thread, not main.
                        altitudeConverter.addMslAltitudeToLocation(context, location)
                        if (location.hasMslAltitude()) location.mslAltitudeMeters else null
                    } catch (e: Exception) {
                        Log.w(TAG, "AltitudeConverter failed", e)
                        null
                    }
                }
                if (msl == null) msl = state.nmeaAltitudeMslM
                publish(state.copy(location = location.toFix(msl), providerEnabled = true))
            }

            override fun onProviderEnabled(provider: String) = publish(state.copy(providerEnabled = true))
            override fun onProviderDisabled(provider: String) =
                publish(state.copy(providerEnabled = false, satellites = emptyList(), fixType = FixType.NONE))

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }

        val statusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val sats = (0 until status.satelliteCount).map { i ->
                    SatelliteObservation(
                        constellation = Constellation.fromAndroid(status.getConstellationType(i)),
                        svid = status.getSvid(i),
                        azimuthDeg = status.getAzimuthDegrees(i),
                        elevationDeg = status.getElevationDegrees(i),
                        cn0DbHz = status.getCn0DbHz(i),
                        usedInFix = status.usedInFix(i),
                        carrierFrequencyHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) else null,
                        hasAlmanac = status.hasAlmanacData(i),
                        hasEphemeris = status.hasEphemerisData(i),
                    )
                }
                publish(state.copy(satellites = mergeBands(sats)))
            }

            override fun onStopped() = publish(state.copy(satellites = emptyList()))
        }

        val nmeaListener = OnNmeaMessageListener { message, _ ->
            when {
                message.length > 6 && message.regionMatches(3, "GGA", 0, 3) -> Nmea.parseGga(message)?.let { gga ->
                    if (gga.altitudeMslM != null) state = state.copy(nmeaAltitudeMslM = gga.altitudeMslM)
                }
                message.length > 6 && message.regionMatches(3, "GSA", 0, 3) -> Nmea.parseGsa(message)?.let { gsa ->
                    if (gsa.fixType != state.fixType || gsa.dop != state.dop) {
                        publish(state.copy(fixType = gsa.fixType, dop = gsa.dop))
                    }
                }
            }
        }

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, thread.looper)
            lm.registerGnssStatusCallback(statusCallback, handler)
            lm.addNmeaListener(nmeaListener, handler)
        } catch (e: Exception) {
            Log.e(TAG, "GNSS registration failed", e)
        }
        trySend(state)

        awaitClose {
            lm.removeUpdates(locationListener)
            lm.unregisterGnssStatusCallback(statusCallback)
            lm.removeNmeaListener(nmeaListener)
            thread.quitSafely()
        }
    }.conflate()

    private fun Location.toFix(msl: Double?) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        altitudeEllipsoidM = if (hasAltitude()) altitude else null,
        altitudeMslM = msl,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
        verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
        timeMillis = time,
        elapsedRealtimeMillis = elapsedRealtimeNanos / 1_000_000L,
    )

    companion object {
        private const val TAG = "GnssRepository"

        /**
         * Dual-frequency receivers report one entry per signal (L1 + L5). Keep one entry per satellite:
         * used-in-fix wins, then the stronger signal.
         */
        fun mergeBands(sats: List<SatelliteObservation>): List<SatelliteObservation> =
            sats.groupBy { it.key }.values.map { group ->
                group.maxWith(compareBy<SatelliteObservation>({ it.usedInFix }, { it.cn0DbHz }))
                    .copy(usedInFix = group.any { it.usedInFix })
            }

        fun nowElapsedMillis(): Long = SystemClock.elapsedRealtime()
    }
}
