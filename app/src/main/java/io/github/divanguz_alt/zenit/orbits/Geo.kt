package io.github.divanguz_alt.zenit.orbits

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geodetic position: latitude/longitude in degrees, height above the WGS-84 ellipsoid in km. */
data class Geodetic(val latDeg: Double, val lonDeg: Double, val heightKm: Double)

/** Topocentric look angles from an observer: azimuth/elevation in degrees, range in km. */
data class LookAngles(val azimuthDeg: Double, val elevationDeg: Double, val rangeKm: Double)

object Geo {
    private const val A = 6378.137
    private const val F = 1.0 / 298.257223563
    private const val E2 = 2 * F - F * F
    const val MEAN_EARTH_RADIUS_KM = 6371.0

    /** TEME (approximated as true-of-date) to Earth-fixed, rotating by GMST. */
    fun temeToEcef(r: Vec3, gmst: Double): Vec3 {
        val c = cos(gmst)
        val s = sin(gmst)
        return Vec3(r.x * c + r.y * s, -r.x * s + r.y * c, r.z)
    }

    fun ecefToGeodetic(r: Vec3): Geodetic {
        val p = sqrt(r.x * r.x + r.y * r.y)
        val lon = atan2(r.y, r.x)
        var lat = atan2(r.z, p * (1 - E2))
        var n = A
        repeat(6) {
            val s = sin(lat)
            n = A / sqrt(1 - E2 * s * s)
            lat = atan2(r.z + n * E2 * s, p)
        }
        val h = if (abs(lat) < PI / 2 - 1e-6) p / cos(lat) - n else abs(r.z) - n * (1 - E2)
        return Geodetic(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }

    fun geodeticToEcef(g: Geodetic): Vec3 {
        val lat = Math.toRadians(g.latDeg)
        val lon = Math.toRadians(g.lonDeg)
        val s = sin(lat)
        val n = A / sqrt(1 - E2 * s * s)
        return Vec3(
            (n + g.heightKm) * cos(lat) * cos(lon),
            (n + g.heightKm) * cos(lat) * sin(lon),
            (n * (1 - E2) + g.heightKm) * s,
        )
    }

    /** Azimuth (clockwise from true north), elevation and range of an ECEF target seen from [observer]. */
    fun lookAngles(observer: Geodetic, target: Vec3): LookAngles {
        val o = geodeticToEcef(observer)
        val rx = target.x - o.x
        val ry = target.y - o.y
        val rz = target.z - o.z
        val lat = Math.toRadians(observer.latDeg)
        val lon = Math.toRadians(observer.lonDeg)
        val sLat = sin(lat); val cLat = cos(lat); val sLon = sin(lon); val cLon = cos(lon)
        val east = -sLon * rx + cLon * ry
        val north = -sLat * cLon * rx - sLat * sLon * ry + cLat * rz
        val up = cLat * cLon * rx + cLat * sLon * ry + sLat * rz
        val range = sqrt(rx * rx + ry * ry + rz * rz)
        var az = Math.toDegrees(atan2(east, north))
        if (az < 0) az += 360.0
        return LookAngles(az, Math.toDegrees(asin(up / range)), range)
    }

    /**
     * Sub-satellite point for a satellite seen at azimuth/elevation from an observer, assuming a
     * spherical Earth and a circular orbit at [orbitAltitudeKm]. Same construction as the prototype's
     * placeSats(): Earth-central angle from the elevation, then a great-circle step along the azimuth.
     */
    fun subPointFromLookAngles(latDeg: Double, lonDeg: Double, azDeg: Double, elDeg: Double, orbitAltitudeKm: Double): Pair<Double, Double> {
        val re = MEAN_EARTH_RADIUS_KM
        val el = Math.toRadians(elDeg)
        val psi = PI / 2 - el - asin(re / (re + orbitAltitudeKm) * cos(el))
        val p1 = Math.toRadians(latDeg)
        val l1 = Math.toRadians(lonDeg)
        val th = Math.toRadians(azDeg)
        val p2 = asin(sin(p1) * cos(psi) + cos(p1) * sin(psi) * cos(th))
        val l2 = l1 + atan2(sin(th) * sin(psi) * cos(p1), cos(psi) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to normalizeLon(Math.toDegrees(l2))
    }

    fun normalizeLon(lon: Double): Double {
        var l = (lon + 180.0) % 360.0
        if (l < 0) l += 360.0
        return l - 180.0
    }

    /** Angular separation between two sky directions (degrees). */
    fun skySeparation(az1: Double, el1: Double, az2: Double, el2: Double): Double {
        val a1 = Math.toRadians(az1); val e1 = Math.toRadians(el1)
        val a2 = Math.toRadians(az2); val e2 = Math.toRadians(el2)
        val c = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a1 - a2)
        return Math.toDegrees(kotlin.math.acos(c.coerceIn(-1.0, 1.0)))
    }

    /**
     * Sub-solar point (degrees) for a Julian date, using the NOAA low-precision solar algorithm
     * (good to ~0.01 degrees, plenty for a day/night terminator).
     */
    fun subSolarPoint(jd: Double): Pair<Double, Double> {
        val n = jd - 2451545.0
        val l = Math.toRadians((280.460 + 0.9856474 * n) % 360.0)
        val g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0)
        val lambda = l + Math.toRadians(1.915) * sin(g) + Math.toRadians(0.020) * sin(2 * g)
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val dec = asin(sin(eps) * sin(lambda))
        val gmst = gstime(jd)
        val lon = normalizeLon(Math.toDegrees(ra - gmst))
        return Math.toDegrees(dec) to lon
    }
}
