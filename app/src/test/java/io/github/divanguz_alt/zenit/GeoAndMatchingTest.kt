package io.github.divanguz_alt.zenit

import io.github.divanguz_alt.zenit.gnss.Constellation
import io.github.divanguz_alt.zenit.orbits.Geo
import io.github.divanguz_alt.zenit.orbits.Geodetic
import io.github.divanguz_alt.zenit.orbits.MeasuredDirection
import io.github.divanguz_alt.zenit.orbits.OmmRecord
import io.github.divanguz_alt.zenit.orbits.OrbitalObject
import io.github.divanguz_alt.zenit.orbits.PredictedDirection
import io.github.divanguz_alt.zenit.orbits.SatelliteMatcher
import io.github.divanguz_alt.zenit.orbits.Satrec
import io.github.divanguz_alt.zenit.orbits.gstime
import io.github.divanguz_alt.zenit.orbits.jday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoAndMatchingTest {
    @Test fun gmstAtJ2000() {
        // GMST at 2000-01-01 12:00 UT1 = 280.46061837°
        assertEquals(280.46061837, Math.toDegrees(gstime(2451545.0)), 1e-6)
    }

    @Test fun julianDate() {
        assertEquals(2451545.0, jday(2000, 1, 1, 12, 0, 0.0), 1e-9)
        assertEquals(2460310.5, OmmRecord.parseIsoToJd("2024-01-01T00:00:00.000000"), 1e-9)
    }

    @Test fun geodeticRoundTrip() {
        for (g in listOf(Geodetic(45.81365, 15.95858, 0.194), Geodetic(-33.9, 151.2, 0.05), Geodetic(89.9, -45.0, 2.0), Geodetic(0.0, 180.0, 20200.0))) {
            val back = Geo.ecefToGeodetic(Geo.geodeticToEcef(g))
            assertEquals(g.latDeg, back.latDeg, 1e-7)
            assertEquals(0.0, abs(Geo.normalizeLon(g.lonDeg - back.lonDeg)), 1e-7)
            assertEquals(g.heightKm, back.heightKm, 1e-6)
        }
    }

    @Test fun lookAnglesOverheadAndNorth() {
        val obs = Geodetic(45.0, 16.0, 0.0)
        val overhead = Geo.geodeticToEcef(Geodetic(45.0, 16.0, 20000.0))
        assertEquals(90.0, Geo.lookAngles(obs, overhead).elevationDeg, 1e-6)
        val north = Geo.geodeticToEcef(Geodetic(55.0, 16.0, 20000.0))
        val la = Geo.lookAngles(obs, north)
        assertTrue(la.azimuthDeg < 0.5 || la.azimuthDeg > 359.5)
        assertTrue(la.elevationDeg in 30.0..80.0)
    }

    @Test fun subPointInvertsLookAngles() {
        val obs = Geodetic(45.81365, 15.95858, 0.0)
        val target = Geodetic(30.0, 40.0, 20_200.0)
        val la = Geo.lookAngles(obs, Geo.geodeticToEcef(target))
        val (lat, lon) = Geo.subPointFromLookAngles(obs.latDeg, obs.lonDeg, la.azimuthDeg, la.elevationDeg, 20_200.0)
        // Spherical approximation: within half a degree of the ellipsoidal truth.
        assertEquals(target.latDeg, lat, 0.5)
        assertEquals(target.lonDeg, lon, 0.5)
    }

    @Test fun temeToGeodeticForGeostationary() {
        // Vallado case 28626 is geosynchronous: latitude stays near the equator, altitude ~35 786 km.
        val sat = Satrec.fromTle(
            "1 28626U 05008A   06176.46683397 -.00000205  00000-0  10000-3 0  2190",
            "2 28626   0.0019 286.9433 0000335  13.7918  55.6504  1.00270176  4891",
        )
        val sv = sat.propagate(0.0)!!
        val geo = Geo.ecefToGeodetic(Geo.temeToEcef(sv.position, gstime(sat.jdsatepoch)))
        assertEquals(0.0, geo.latDeg, 0.05)
        assertEquals(35_786.0, geo.heightKm, 30.0)
    }

    @Test fun prnExtraction() {
        assertEquals(13, OrbitalObject.prnFromName("GPS BIIR-2  (PRN 13)", Constellation.GPS))
        assertEquals(19, OrbitalObject.prnFromName("BEIDOU-3 M1 (C19)", Constellation.BEIDOU))
        assertEquals(null, OrbitalObject.prnFromName("GSAT0101 (GALILEO-PFM)", Constellation.GALILEO))
    }

    @Test fun matcherUsesPrnThenGeometry() {
        val measured = listOf(
            MeasuredDirection("G13", Constellation.GPS, 13, 120.0, 40.0),
            MeasuredDirection("E24", Constellation.GALILEO, 24, 200.0, 30.0),
            MeasuredDirection("E03", Constellation.GALILEO, 3, 202.0, 33.0),
            MeasuredDirection("R07", Constellation.GLONASS, 7, 10.0, 10.0),
        )
        val predicted = listOf(
            PredictedDirection(0, Constellation.GPS, 13, 121.0, 40.5),
            PredictedDirection(1, Constellation.GPS, 5, 120.2, 40.1), // closer, but wrong PRN
            PredictedDirection(2, Constellation.GALILEO, null, 201.5, 32.6),
            PredictedDirection(3, Constellation.GALILEO, null, 200.3, 30.2),
            PredictedDirection(4, Constellation.GLONASS, null, 30.0, 10.0), // 20° away: no match
        )
        val m = SatelliteMatcher.match(measured, predicted).associateBy { it.measuredKey }
        assertEquals(0, m["G13"]!!.predictedId)
        assertTrue(m["G13"]!!.byPrn)
        assertEquals(3, m["E24"]!!.predictedId)
        assertEquals(2, m["E03"]!!.predictedId)
        assertTrue("R07" !in m)
    }

    @Test fun skySeparation() {
        assertEquals(0.0, Geo.skySeparation(10.0, 20.0, 10.0, 20.0), 1e-6)
        assertEquals(90.0, Geo.skySeparation(0.0, 0.0, 0.0, 90.0), 1e-9)
        assertEquals(2.0, Geo.skySeparation(359.0, 0.0, 1.0, 0.0), 1e-9)
    }

    @Test fun subSolarPointAtEquinox() {
        // 2024-03-20 03:06 UTC (March equinox): declination ~0.
        val (lat, _) = Geo.subSolarPoint(jday(2024, 3, 20, 3, 6, 0.0))
        assertEquals(0.0, lat, 0.1)
        // June solstice: ~23.44° N
        val (latJune, lonJune) = Geo.subSolarPoint(jday(2024, 6, 20, 12, 0, 0.0))
        assertEquals(23.44, latJune, 0.1)
        assertEquals(0.0, lonJune, 2.5) // local noon near Greenwich (equation of time ~ -1.5 min)
    }
}
