package io.github.divanguz_alt.zenit

import io.github.divanguz_alt.zenit.orbits.Satrec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies the SGP4/SDP4 port against David Vallado's published test vectors
 * (SGP4-VER.TLE input, tcppver.out reference output, WGS-72, opsmode 'i').
 */
class Sgp4VerificationTest {

    private data class Case(val line1: String, val line2: String)
    private data class Expected(val satnum: String, val rows: List<DoubleArray>)

    private fun resource(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readLines()

    private fun cases(): List<Case> {
        val lines = resource("SGP4-VER.TLE").filter { it.isNotBlank() && !it.startsWith("#") }
        val out = mutableListOf<Case>()
        var i = 0
        while (i < lines.size - 1) {
            if (lines[i].startsWith("1 ") && lines[i + 1].startsWith("2 ")) {
                out += Case(lines[i], lines[i + 1])
                i += 2
            } else {
                i++
            }
        }
        return out
    }

    private fun expected(): List<Expected> {
        val out = mutableListOf<Expected>()
        var current: MutableList<DoubleArray>? = null
        var satnum = ""
        for (line in resource("tcppver.out")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.endsWith("xx")) {
                current?.let { out += Expected(satnum, it) }
                satnum = t.removeSuffix("xx").trim()
                current = mutableListOf()
            } else {
                val parts = t.split(Regex("\\s+"))
                current?.add(DoubleArray(7) { parts[it].toDouble() })
            }
        }
        current?.let { out += Expected(satnum, it) }
        return out
    }

    @Test
    fun matchesValladoVectorsWithinOneMetre() {
        val cases = cases()
        val expected = expected()
        assertEquals("case count", expected.size, cases.size)

        var compared = 0
        var maxPosErrKm = 0.0
        var maxVelErr = 0.0
        cases.zip(expected).forEach { (case, exp) ->
            val sat = Satrec.fromTle(case.line1, case.line2)
            assertEquals(exp.satnum.trimStart('0'), sat.satnum.trimStart('0'))
            for (row in exp.rows) {
                val sv = sat.propagate(row[0])
                // Vallado's error-code cases (e.g. 33334, error 3) list only the epoch state that the
                // reference driver prints regardless of the error; a null result is the correct outcome.
                if (sv == null && exp.rows.size == 1) return@forEach
                assertNotNull("sat ${exp.satnum} t=${row[0]} error=${sat.error}", sv)
                sv!!
                val dp = maxOf(abs(sv.position.x - row[1]), abs(sv.position.y - row[2]), abs(sv.position.z - row[3]))
                val dv = maxOf(abs(sv.velocity.x - row[4]), abs(sv.velocity.y - row[5]), abs(sv.velocity.z - row[6]))
                maxPosErrKm = maxOf(maxPosErrKm, dp)
                maxVelErr = maxOf(maxVelErr, dv)
                assertTrue("sat ${exp.satnum} t=${row[0]} position error ${dp * 1000} m", dp < 1e-3)
                assertTrue("sat ${exp.satnum} t=${row[0]} velocity error $dv km/s", dv < 1e-6)
                compared++
            }
        }
        println("SGP4 verification: $compared states, max position error ${maxPosErrKm * 1e6} mm, max velocity error ${maxVelErr * 1e9} µm/s")
        assertTrue(compared > 500)
    }

    @Test
    fun gpsDeepSpaceOrbitIsSdp4AndStaysAtMeoAltitude() {
        // GPS BIIR-2 (PRN 13): period ~718 min -> deep-space (SDP4). Near-circular, so no 12h resonance
        // terms (those apply only to eccentric Molniya-type orbits, e >= 0.5).
        val sat = Satrec.fromTle(
            "1 24876U 97035A   24001.50000000  .00000049  00000+0  00000+0 0  9990",
            "2 24876  55.5146 109.0610 0091839  54.6417 306.2302  2.00563011193574",
        )
        assertEquals('d', sat.method)
        assertEquals(0, sat.irez)
        for (minutes in listOf(0.0, 360.0, 720.0, 1440.0, 7 * 1440.0)) {
            val sv = sat.propagate(minutes)
            assertNotNull(sv)
            val altitude = sv!!.position.norm - 6378.135
            assertTrue("altitude $altitude km at t=$minutes", altitude in 19_800.0..20_800.0)
            assertTrue(abs(sv.velocity.norm - 3.87) < 0.1)
        }
    }

    @Test
    fun geostationaryOrbitIsOneDayResonant() {
        // The SGP4-VER set includes 28626 (geosynchronous); check resonance flag selection.
        val case = cases().first { it.line1.substring(2, 7).trim() == "28626" }
        val sat = Satrec.fromTle(case.line1, case.line2)
        assertEquals('d', sat.method)
        assertEquals(1, sat.irez)
    }
}
