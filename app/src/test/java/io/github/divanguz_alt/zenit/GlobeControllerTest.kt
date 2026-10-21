package io.github.divanguz_alt.zenit

import io.github.divanguz_alt.zenit.globe.GlobeController
import io.github.divanguz_alt.zenit.globe.GlobePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeControllerTest {
    private fun run(c: GlobeController, from: Long, to: Long, locked: Boolean, lat: Double? = 45.8, lon: Double? = 15.96) {
        var t = from
        while (t <= to) { c.step(t, 0.016, locked, lat, lon); t += 16 }
    }

    @Test fun searchSpinsThenLocksAlongShortestPath() {
        val c = GlobeController(reducedMotion = false)
        run(c, 0, 3000, locked = false)
        assertEquals(GlobePhase.SEARCH, c.phase)
        assertEquals(-40.0 + 9.0 * 3.0, c.viewLon, 1.0)
        run(c, 3016, 3016, locked = true)
        assertEquals(GlobePhase.LOCKING, c.phase)
        run(c, 3032, 3016 + 1800 + 16, locked = true)
        assertEquals("phase=${c.phase} lat=${c.viewLat} lon=${c.viewLon} zoom=${c.zoom}", GlobePhase.LOCKED, c.phase)
        assertEquals(45.8, c.viewLat, 0.01)
        assertEquals(15.96, c.viewLon, 0.01)
        assertEquals(1.08, c.zoom, 1e-6)
    }

    @Test fun losingFixReturnsToSearchWithoutJump() {
        val c = GlobeController(false)
        run(c, 0, 3000, locked = true)
        assertEquals(GlobePhase.LOCKED, c.phase)
        val lon = c.viewLon
        c.step(3016, 0.016, false, 45.8, 15.96)
        assertEquals(GlobePhase.SEARCH, c.phase)
        assertTrue(kotlin.math.abs(c.viewLon - lon) < 0.5)
    }

    @Test fun revealIsStaggered() {
        val c = GlobeController(false)
        run(c, 0, 4000, locked = true)
        val at = c.revealAt!!
        assertEquals(0f, c.revealAlpha(at, order = 0, firstSeen = 0), 1e-6f)
        assertEquals(1f, c.revealAlpha(at + 500, order = 0, firstSeen = 0), 1e-6f)
        assertEquals(0f, c.revealAlpha(at + 70, order = 1, firstSeen = 0), 1e-6f)
        assertEquals(0.5f, c.revealAlpha(at + 70 + 250, order = 1, firstSeen = 0), 1e-3f)
    }

    @Test fun reducedMotionLocksInstantly() {
        val c = GlobeController(reducedMotion = true)
        run(c, 0, 3000, locked = false)
        assertEquals(-40.0, c.viewLon, 1e-9)
        c.step(3016, 0.016, true, 45.8, 15.96)
        c.step(3032, 0.016, true, 45.8, 15.96)
        assertEquals(GlobePhase.LOCKED, c.phase)
    }

    @Test fun easing() {
        assertEquals(0.0, GlobeController.easeInOutCubic(0.0), 1e-12)
        assertEquals(0.5, GlobeController.easeInOutCubic(0.5), 1e-12)
        assertEquals(1.0, GlobeController.easeInOutCubic(1.0), 1e-12)
    }
}
