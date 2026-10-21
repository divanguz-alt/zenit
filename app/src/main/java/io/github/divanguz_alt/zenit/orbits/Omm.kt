package io.github.divanguz_alt.zenit.orbits

import io.github.divanguz_alt.zenit.gnss.Constellation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** CCSDS Orbit Mean-Elements Message record as served by CelesTrak (FORMAT=json). */
@Serializable
data class OmmRecord(
    @SerialName("OBJECT_NAME") val objectName: String,
    @SerialName("OBJECT_ID") val objectId: String = "",
    @SerialName("EPOCH") val epoch: String,
    @SerialName("MEAN_MOTION") val meanMotion: Double,
    @SerialName("ECCENTRICITY") val eccentricity: Double,
    @SerialName("INCLINATION") val inclination: Double,
    @SerialName("RA_OF_ASC_NODE") val raan: Double,
    @SerialName("ARG_OF_PERICENTER") val argPericenter: Double,
    @SerialName("MEAN_ANOMALY") val meanAnomaly: Double,
    @SerialName("NORAD_CAT_ID") val noradId: Int,
    @SerialName("BSTAR") val bstar: Double = 0.0,
    @SerialName("MEAN_MOTION_DOT") val meanMotionDot: Double = 0.0,
    @SerialName("MEAN_MOTION_DDOT") val meanMotionDdot: Double = 0.0,
) {
    /** Epoch as a Julian date (UTC). Parsed by hand to keep microsecond precision. */
    val epochJd: Double
        get() = parseIsoToJd(epoch)

    fun toSatrec(): Satrec = Satrec.fromElements(
        satnum = noradId.toString(),
        epochJd = epochJd,
        meanMotionRevPerDay = meanMotion,
        eccentricity = eccentricity,
        inclinationDeg = inclination,
        raanDeg = raan,
        argPerigeeDeg = argPericenter,
        meanAnomalyDeg = meanAnomaly,
        bstar = bstar,
    )

    companion object {
        private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2}(?:\.\d+)?)Z?""")

        fun parseIsoToJd(iso: String): Double {
            val m = ISO.matchEntire(iso.trim()) ?: error("Bad OMM epoch: $iso")
            val (y, mo, d, h, mi, s) = m.destructured
            return jday(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toDouble())
        }
    }
}

/** A tracked object with an initialised propagator. */
class OrbitalObject(
    val name: String,
    val noradId: Int,
    val constellation: Constellation?,
    /** PRN / slot number when the catalogue name carries it (GPS "(PRN 13)", BeiDou "(C19)"). */
    val prn: Int?,
    val satrec: Satrec,
) {
    /** True for geosynchronous / inclined-geosynchronous orbits (period > 20 h). */
    val isGeosynchronous: Boolean get() = satrec.periodMinutes > 1200.0

    companion object {
        private val PRN = Regex("""PRN\s*0*(\d+)""", RegexOption.IGNORE_CASE)
        private val BEIDOU = Regex("""\(C0*(\d+)\)""")

        fun prnFromName(name: String, constellation: Constellation?): Int? = when (constellation) {
            Constellation.BEIDOU -> BEIDOU.find(name)?.groupValues?.get(1)?.toInt()
            Constellation.GPS, Constellation.SBAS, Constellation.QZSS -> PRN.find(name)?.groupValues?.get(1)?.toInt()
            else -> null
        }

        fun from(record: OmmRecord, constellation: Constellation?): OrbitalObject? = runCatching {
            OrbitalObject(
                name = record.objectName.trim(),
                noradId = record.noradId,
                constellation = constellation,
                prn = prnFromName(record.objectName, constellation),
                satrec = record.toSatrec(),
            )
        }.getOrNull()
    }
}
