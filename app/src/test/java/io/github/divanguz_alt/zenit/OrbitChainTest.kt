package io.github.divanguz_alt.zenit

import io.github.divanguz_alt.zenit.gnss.Constellation
import io.github.divanguz_alt.zenit.orbits.Geo
import io.github.divanguz_alt.zenit.orbits.Geodetic
import io.github.divanguz_alt.zenit.orbits.MeasuredDirection
import io.github.divanguz_alt.zenit.orbits.OmmRecord
import io.github.divanguz_alt.zenit.orbits.OrbitalObject
import io.github.divanguz_alt.zenit.orbits.PredictedDirection
import io.github.divanguz_alt.zenit.orbits.SatelliteMatcher
import io.github.divanguz_alt.zenit.orbits.gstime
import io.github.divanguz_alt.zenit.orbits.jday
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End-to-end check of the orbit chain (CelesTrak OMM → SGP4 → TEME → ECEF → topocentric az/el)
 * against an independent implementation (python-sgp4, same GMST model) on real GPS elements.
 */
class OrbitChainTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val observer = Geodetic(45.81365, 15.95858, 0.194)
    private val jd = jday(2026, 10, 21, 14, 7, 0.0)

    private fun predictions(): Map<Int, Pair<OrbitalObject, Pair<Double, Double>>> {
        val records = json.decodeFromString<List<OmmRecord>>(res("gps-ops-sample.json"))
        val gmst = gstime(jd)
        return records.mapNotNull { r ->
            val obj = OrbitalObject.from(r, Constellation.GPS) ?: return@mapNotNull null
            val sv = obj.satrec.propagateJd(jd) ?: return@mapNotNull null
            val la = Geo.lookAngles(observer, Geo.temeToEcef(sv.position, gmst))
            r.noradId to (obj to (la.azimuthDeg to la.elevationDeg))
        }.toMap()
    }

    @Test fun lookAnglesMatchIndependentImplementation() {
        val predicted = predictions()
        val expected = res("gps-ops-expected-azel.csv").lines().filter { it.isNotBlank() }.map { line ->
            val (id, az, el) = line.split(',')
            Triple(id.toInt(), az.toDouble(), el.toDouble())
        }
        assertTrue(expected.size >= 25)
        for ((id, az, el) in expected) {
            val (_, p) = predicted.getValue(id)
            val dAz = abs(((p.first - az + 540) % 360) - 180)
            assertTrue("NORAD $id az ${p.first} vs $az", dAz < 0.01 || abs(el) > 89.9)
            assertEquals("NORAD $id el", el, p.second, 0.01)
        }
    }

    @Test fun receiverReportsMatchWithinOneDegree() {
        // Receiver az/el as GnssStatus would report them (rounded to whole degrees, like NMEA GSV).
        val predicted = predictions()
        val visible = predicted.values.filter { it.second.second > 5 }
        assertTrue("need ≥ 5 visible GPS satellites, got ${visible.size}", visible.size >= 5)
        val measured = visible.map { (obj, ae) ->
            MeasuredDirection("G${obj.prn}", Constellation.GPS, obj.prn!!, Math.round(ae.first).toDouble(), Math.round(ae.second).toDouble())
        }
        val list = predicted.values.toList()
        val matches = SatelliteMatcher.match(
            measured,
            list.mapIndexed { i, (obj, ae) -> PredictedDirection(i, Constellation.GPS, obj.prn, ae.first, ae.second) },
        )
        assertEquals(measured.size, matches.size)
        for (m in matches) {
            val obj = list[m.predictedId].first
            assertEquals(m.measuredKey, "G${obj.prn}")
            assertTrue(m.separationDeg < 1.0)
        }
    }
}
