package io.github.divanguz_alt.zenit

import io.github.divanguz_alt.zenit.gnss.Constellation
import io.github.divanguz_alt.zenit.gnss.FixType
import io.github.divanguz_alt.zenit.gnss.GnssRepository
import io.github.divanguz_alt.zenit.gnss.Nmea
import io.github.divanguz_alt.zenit.gnss.SatelliteObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {
    @Test fun gga() {
        val g = Nmea.parseGga("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")!!
        assertEquals(1, g.quality)
        assertEquals(8, g.satellitesUsed)
        assertEquals(0.9, g.hdop!!, 1e-9)
        assertEquals(545.4, g.altitudeMslM!!, 1e-9)
        assertEquals(46.9, g.geoidSeparationM!!, 1e-9)
    }

    @Test fun ggaWithoutFixHasNoAltitude() {
        val body = "GNGGA,000000.00,,,,,0,00,99.99,,,,,,"
        val cs = body.fold(0) { a, c -> a xor c.code }
        val g = Nmea.parseGga("$" + body + "*" + "%02X".format(cs))!!
        assertEquals(0, g.quality)
        assertNull(g.altitudeMslM)
    }

    @Test fun badChecksumRejected() {
        assertNull(Nmea.parseGga("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48"))
    }

    @Test fun gsa() {
        val g = Nmea.parseGsa("\$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39")!!
        assertEquals(FixType.FIX_3D, g.fixType)
        assertEquals(2.5, g.dop.pdop!!, 1e-9)
        assertEquals(1.3, g.dop.hdop!!, 1e-9)
        assertEquals(2.1, g.dop.vdop!!, 1e-9)
    }

    @Test fun gsaNmea41WithSystemId() {
        val body = "GNGSA,A,2,05,07,13,,,,,,,,,,1.80,1.00,1.50,1"
        val cs = body.fold(0) { a, c -> a xor c.code }
        val g = Nmea.parseGsa("$" + body + "*" + "%02X".format(cs))!!
        assertEquals(FixType.FIX_2D, g.fixType)
        assertEquals(1.0, g.dop.hdop!!, 1e-9)
    }

    @Test fun labelsAndBandMerge() {
        fun obs(c: Constellation, svid: Int, cn0: Float, used: Boolean, f: Float) =
            SatelliteObservation(c, svid, 10f, 20f, cn0, used, f, true, true)
        assertEquals("G05", obs(Constellation.GPS, 5, 30f, true, 1575.42e6f).label)
        assertEquals("S123", obs(Constellation.SBAS, 123, 30f, true, 1575.42e6f).label)
        assertEquals("J02", obs(Constellation.QZSS, 194, 30f, true, 1575.42e6f).label)
        assertEquals("L5", obs(Constellation.GPS, 5, 30f, true, 1176.45e6f).band)
        val merged = GnssRepository.mergeBands(
            listOf(obs(Constellation.GPS, 5, 30f, false, 1575.42e6f), obs(Constellation.GPS, 5, 25f, true, 1176.45e6f), obs(Constellation.GALILEO, 5, 40f, true, 1575.42e6f)),
        )
        assertEquals(2, merged.size)
        assertTrue(merged.first { it.constellation == Constellation.GPS }.usedInFix)
    }
}
