package io.github.divanguz_alt.zenit.globe

import io.github.divanguz_alt.zenit.settings.GlobeStyle
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One frame's camera: orthographic view of a unit sphere rotated so ([viewLatDeg], [viewLonDeg])
 * faces the viewer, drawn as a disc of [radiusPx] centred at ([centerX], [centerY]) in view pixels
 * (top-left origin). The GL renderer and the Compose overlay both draw from the same frame.
 */
data class GlobeFrame(
    val viewLatDeg: Double,
    val viewLonDeg: Double,
    val centerX: Float,
    val centerY: Float,
    val radiusPx: Float,
    val style: GlobeStyle,
    val dayNight: Boolean,
    val subSolarLat: Double,
    val subSolarLon: Double,
    val density: Float,
) {
    private val s0 = sin(Math.toRadians(viewLatDeg))
    private val c0 = cos(Math.toRadians(viewLatDeg))

    /**
     * Projects a point at geographic ([latDeg], [lonDeg]) and distance [k] Earth radii.
     * Writes screen x, screen y and depth z (z > 0 faces the viewer) into [out].
     */
    fun project(latDeg: Double, lonDeg: Double, k: Float, out: FloatArray) {
        val p = Math.toRadians(latDeg)
        val l = Math.toRadians(lonDeg - viewLonDeg)
        val cp = cos(p); val sp = sin(p); val cl = cos(l)
        val x = cp * sin(l)
        val y = c0 * sp - s0 * cp * cl
        val z = s0 * sp + c0 * cp * cl
        out[0] = centerX + radiusPx * k * x.toFloat()
        out[1] = centerY - radiusPx * k * y.toFloat()
        out[2] = z.toFloat()
        out[3] = hypot(x, y).toFloat() * k
    }

    /** Same as [project] for an Earth-fixed unit vector scaled by [k]. */
    fun projectVector(ex: Double, ey: Double, ez: Double, k: Float, out: FloatArray) {
        // Rotate by -viewLon about Z, then by viewLat about the east axis.
        val l0 = Math.toRadians(viewLonDeg)
        val xr = ex * cos(l0) + ey * sin(l0)
        val yr = -ex * sin(l0) + ey * cos(l0)
        val x = yr
        val y = c0 * ez - s0 * xr
        val z = s0 * ez + c0 * xr
        out[0] = centerX + radiusPx * k * x.toFloat()
        out[1] = centerY - radiusPx * k * y.toFloat()
        out[2] = z.toFloat()
        out[3] = hypot(x, y).toFloat() * k
    }

    companion object {
        /** True when a projected point at distance k is hidden behind the Earth. */
        fun hidden(out: FloatArray): Boolean = out[2] < 0f && out[3] < 1.02f
    }
}

/** Thread-safe hand-off of the current frame from the UI thread to the GL thread. */
class GlobeCamera {
    private val ref = AtomicReference<GlobeFrame?>(null)
    var frame: GlobeFrame?
        get() = ref.get()
        set(value) = ref.set(value)
}
