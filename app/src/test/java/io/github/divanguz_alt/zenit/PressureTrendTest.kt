package io.github.divanguz_alt.zenit

import io.github.divanguz_alt.zenit.sensors.PressureTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressureTrendTest {
    @Test fun slopeOverWindow() {
        val t = PressureTrend()
        assertNull(t.slopePerHour())
        // Falling 1.2 hPa per hour, sampled every 10 s for 20 minutes.
        for (i in 0..120) t.add(i * 10_000L, (1000.0 - 1.2 * i * 10.0 / 3600.0).toFloat())
        assertEquals(-1.2f, t.slopePerHour()!!, 0.02f)
    }
}
