package io.github.divanguz_alt.zenit

import io.github.divanguz_alt.zenit.car.CarStatus
import io.github.divanguz_alt.zenit.globe.GlobePhase
import io.github.divanguz_alt.zenit.globe.IssInfo
import io.github.divanguz_alt.zenit.gnss.FixType
import io.github.divanguz_alt.zenit.ui.DashboardState
import org.junit.Assert.assertEquals
import org.junit.Test

class CarStatusTest {
    private val locked = DashboardState(
        usedCount = 11, visibleCount = 27, fixType = FixType.FIX_3D, accuracyText = "±4.8 m",
        speed = "52.0", speedUnit = "km/h", altitude = "194.2", altitudeUnit = "m", hasFix = true,
    )

    @Test fun lockedCard() {
        assertEquals(
            CarStatus("3D fix · 11 of 27 satellites", "±4.8 m · 52.0 km/h · 194.2 m"),
            CarStatus.from(locked, GlobePhase.LOCKED, issShown = false, iss = null),
        )
    }

    @Test fun searchingAndLocking() {
        assertEquals("Searching for satellites", CarStatus.from(DashboardState(visibleCount = 4), GlobePhase.SEARCH, false, null).title)
        assertEquals("4 in view", CarStatus.from(DashboardState(visibleCount = 4), GlobePhase.SEARCH, false, null).text)
        assertEquals("Locking position…", CarStatus.from(locked, GlobePhase.LOCKING, false, null).text)
    }

    @Test fun issAndLocationOff() {
        assertEquals(
            CarStatus("International Space Station", "418 km · 27 580 km/h"),
            CarStatus.from(locked, GlobePhase.LOCKED, issShown = true, iss = IssInfo(418.3, 27580.2, 92.9)),
        )
        assertEquals("Location is off", CarStatus.from(DashboardState(providerEnabled = false), GlobePhase.SEARCH, false, null).title)
    }

    @Test fun fixWithoutUsedFlags() {
        val d = locked.copy(usedCount = 0, speed = "—", altitude = "—")
        assertEquals(CarStatus("3D fix · 27 in view", "±4.8 m"), CarStatus.from(d, GlobePhase.LOCKED, false, null))
    }
}
