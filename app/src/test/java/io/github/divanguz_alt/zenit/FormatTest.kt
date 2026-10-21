package io.github.divanguz_alt.zenit

import io.github.divanguz_alt.zenit.settings.AltitudeUnit
import io.github.divanguz_alt.zenit.settings.CoordinateFormat
import io.github.divanguz_alt.zenit.settings.PressureUnit
import io.github.divanguz_alt.zenit.settings.SpeedUnit
import io.github.divanguz_alt.zenit.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun ddmMatchesPrototype() {
        assertEquals("45°48.8190′N", Format.ddm(45.81365, true))
        assertEquals("15°57.5148′E", Format.ddm(15.95858, false))
        assertEquals("33°52.0000′S", Format.ddm(-33.866666667, true))
        assertEquals("0°00.0000′W", Format.ddm(-0.0000001, false))
    }

    @Test fun ddmRollsOverSixtyMinutes() {
        assertEquals("46°00.0000′N", Format.ddm(45.999999999, true))
    }

    @Test fun dms() {
        assertEquals("45°48′49.14″N", Format.dms(45.81365, true))
        assertEquals("118°14′37.00″W", Format.dms(-118.243611111, false))
        assertEquals("46°00′00.00″N", Format.dms(45.9999999999, true))
    }

    @Test fun dd() {
        assertEquals("45.813650°", Format.dd(45.81365))
        assertEquals("-15.958580°", Format.coordinate(-15.95858, false, CoordinateFormat.DD))
        assertEquals("45°48.8190′N", Format.coordinateSecondary(45.81365, true, CoordinateFormat.DD))
        assertEquals("45.813650°", Format.coordinateSecondary(45.81365, true, CoordinateFormat.DDM))
    }

    @Test fun speedUnits() {
        assertEquals(36.0, Format.speed(10.0, SpeedUnit.KMH), 1e-9)
        assertEquals(22.369, Format.speed(10.0, SpeedUnit.MPH), 1e-3)
        assertEquals(19.438, Format.speed(10.0, SpeedUnit.KNOTS), 1e-3)
        assertEquals(10.0, Format.speed(10.0, SpeedUnit.MPS), 1e-9)
    }

    @Test fun altitudeAndPressureUnits() {
        assertEquals(3280.84, Format.altitude(1000.0, AltitudeUnit.FEET), 0.01)
        assertEquals(29.92, Format.pressure(1013.25, PressureUnit.INHG), 0.01)
        assertEquals(760.0, Format.pressure(1013.25, PressureUnit.MMHG), 0.01)
        assertEquals("995.2", Format.pressureText(995.2, PressureUnit.HPA))
        assertEquals("29.92", Format.pressureText(1013.25, PressureUnit.INHG))
    }

    @Test fun compassPoints() {
        assertEquals("N", Format.compassPoint(0.0))
        assertEquals("N", Format.compassPoint(359.0))
        assertEquals("S-SE", Format.compassPoint(148.0))
        assertEquals("E", Format.compassPoint(90.0))
        assertEquals("W-NW", Format.compassPoint(-67.5))
    }

    @Test fun misc() {
        assertEquals("40,397", Format.thousands(40397))
        assertEquals("27 600", Format.spacedThousands(27600))
        assertEquals("5.0° E", Format.declination(5.0))
        assertEquals("1.3° W", Format.declination(-1.25))
        assertEquals("3 h ago", Format.age(3 * 3_600_000L))
    }
}
