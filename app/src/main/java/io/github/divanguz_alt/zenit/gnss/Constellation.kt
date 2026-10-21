package io.github.divanguz_alt.zenit.gnss

import android.location.GnssStatus

/**
 * GNSS constellations with their display properties.
 * [displayRadius] is the stylised orbit radius in Earth radii used on the globe.
 */
enum class Constellation(
    val displayName: String,
    val prefix: String,
    val color: Long,
    val nominalAltitudeKm: Double,
    val displayRadius: Float,
    val order: Int,
) {
    GPS("GPS", "G", 0xFF5BE38A, 20_200.0, 1.42f, 0),
    GALILEO("Galileo", "E", 0xFF63B7FF, 23_222.0, 1.52f, 1),
    GLONASS("GLONASS", "R", 0xFFF2C45A, 19_100.0, 1.34f, 2),
    BEIDOU("BeiDou", "C", 0xFFC49BFF, 21_500.0, 1.47f, 3),
    QZSS("QZSS", "J", 0xFFFF8FB1, 35_786.0, 1.62f, 4),
    SBAS("SBAS", "S", 0xFF9AA0A6, 35_786.0, 1.62f, 5),
    NAVIC("NavIC", "I", 0xFF9AA0A6, 35_786.0, 1.62f, 6),
    OTHER("Other", "?", 0xFF9AA0A6, 20_000.0, 1.42f, 7);

    companion object {
        /** Radius (Earth radii) at which geosynchronous / IGSO satellites sit at the edge of the view. */
        const val EDGE_RADIUS = 1.62f

        fun fromAndroid(type: Int): Constellation = when (type) {
            GnssStatus.CONSTELLATION_GPS -> GPS
            GnssStatus.CONSTELLATION_GALILEO -> GALILEO
            GnssStatus.CONSTELLATION_GLONASS -> GLONASS
            GnssStatus.CONSTELLATION_BEIDOU -> BEIDOU
            GnssStatus.CONSTELLATION_QZSS -> QZSS
            GnssStatus.CONSTELLATION_SBAS -> SBAS
            GnssStatus.CONSTELLATION_IRNSS -> NAVIC
            else -> OTHER
        }
    }
}

/** One satellite as reported by [GnssStatus]. */
data class SatelliteObservation(
    val constellation: Constellation,
    val svid: Int,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val carrierFrequencyHz: Float?,
    val hasAlmanac: Boolean,
    val hasEphemeris: Boolean,
) {
    val key: String get() = "${constellation.prefix}$svid"

    /** Display label: G12, E24, R07, C19, J02, S1xx. */
    val label: String
        get() = when (constellation) {
            Constellation.SBAS -> "S$svid"
            Constellation.QZSS -> "J" + (if (svid >= 193) svid - 192 else svid).toString().padStart(2, '0')
            else -> constellation.prefix + svid.toString().padStart(2, '0')
        }

    /** Signal band derived from the carrier frequency, e.g. L1 / L5 / E5a / B1I. */
    val band: String?
        get() {
            val f = carrierFrequencyHz ?: return null
            val mhz = f / 1e6f
            return when {
                mhz in 1559f..1610f -> if (constellation == Constellation.BEIDOU && mhz < 1565f) "B1I" else "L1"
                mhz in 1170f..1180f -> if (constellation == Constellation.GALILEO) "E5a" else "L5"
                mhz in 1200f..1210f -> if (constellation == Constellation.GALILEO) "E5b" else "B2"
                mhz in 1215f..1240f -> "L2"
                mhz in 1260f..1300f -> if (constellation == Constellation.GALILEO) "E6" else "B3"
                else -> null
            }
        }
}
