package io.github.divanguz_alt.zenit.globe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.pow

enum class GlobePhase { SEARCH, LOCKING, LOCKED }

/**
 * Camera animation state machine, stepped once per frame. Values follow the prototype:
 * search spin 9°/s with tilt easing to 14°, lock = shortest-path turn in 1800 ms (easeInOutCubic)
 * with zoom 1.00 → 1.08, then the camera follows the user's location.
 */
class GlobeController(private val reducedMotion: Boolean) {
    var phase by mutableStateOf(GlobePhase.SEARCH)
        private set

    var viewLat = 12.0
        private set
    var viewLon = -40.0
        private set
    var zoom = 1.0
        private set

    /** Time (ms) after which satellites start their staggered reveal; null until a lock begins. */
    var revealAt: Long? = null
        private set

    private var anim: Anim? = null
    private var searchUntil = 0L

    private class Anim(val t0: Long, val dur: Long, val fromLat: Double, val fromLon: Double, val fromZoom: Double, val toLat: Double, val toLon: Double, val toZoom: Double)

    fun reacquire(now: Long) {
        anim = null
        revealAt = null
        phase = GlobePhase.SEARCH
        searchUntil = now + MIN_SEARCH_MS
    }

    fun step(now: Long, dtSec: Double, locked: Boolean, userLat: Double?, userLon: Double?) {
        val dt = dtSec.coerceIn(0.0, 0.05)

        // Lock acquired: turn to the user.
        if (phase == GlobePhase.SEARCH && locked && userLat != null && userLon != null && now >= searchUntil) {
            val toLon = viewLon + ((((userLon - viewLon) % 360.0) + 540.0) % 360.0 - 180.0)
            val dur = if (reducedMotion) 0L else LOCK_MS
            anim = Anim(now, dur, viewLat, viewLon, zoom, userLat, toLon, LOCK_ZOOM)
            revealAt = now + if (reducedMotion) 0L else LOCK_MS - 100
            phase = GlobePhase.LOCKING
        }
        // Fix lost: back to searching, continuing from wherever the camera is (no jump).
        if (phase != GlobePhase.SEARCH && !locked) {
            anim = null
            revealAt = null
            phase = GlobePhase.SEARCH
        }

        val a = anim
        when {
            a != null -> {
                val p = if (a.dur == 0L) 1.0 else ((now - a.t0).toDouble() / a.dur).coerceIn(0.0, 1.0)
                val e = easeInOutCubic(p)
                viewLat = a.fromLat + (a.toLat - a.fromLat) * e
                viewLon = a.fromLon + (a.toLon - a.fromLon) * e
                zoom = a.fromZoom + (a.toZoom - a.fromZoom) * e
                if (p >= 1.0) {
                    anim = null
                    viewLon = normalizeLon(viewLon)
                    if (phase == GlobePhase.LOCKING) phase = GlobePhase.LOCKED
                }
            }
            phase == GlobePhase.SEARCH -> {
                if (!reducedMotion) {
                    viewLon = normalizeLon(viewLon + dt * SPIN_DEG_PER_S)
                    viewLat += (SEARCH_TILT - viewLat) * dt * 0.8
                }
                zoom += (1.0 - zoom) * dt * 2
            }
            else -> if (userLat != null && userLon != null) {
                val dLon = (((userLon - viewLon) % 360.0) + 540.0) % 360.0 - 180.0
                viewLat += (userLat - viewLat) * dt * 2
                viewLon = normalizeLon(viewLon + dLon * dt * 2)
            }
        }
    }

    /** Satellite reveal: 0 → 1 over 500 ms, staggered 70 ms per satellite after the lock turn. */
    fun revealAlpha(now: Long, order: Int, firstSeen: Long): Float {
        val base = when (phase) {
            GlobePhase.LOCKING -> return 0f
            GlobePhase.LOCKED -> (revealAt ?: now) + order * STAGGER_MS
            GlobePhase.SEARCH -> firstSeen
        }
        if (reducedMotion) return if (now >= base) ((now - base) / FADE_MS.toFloat()).coerceIn(0f, 1f) else 0f
        return ((now - base) / FADE_MS.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        const val SPIN_DEG_PER_S = 9.0
        const val SEARCH_TILT = 14.0
        const val LOCK_MS = 1800L
        const val LOCK_ZOOM = 1.08
        const val STAGGER_MS = 70L
        const val FADE_MS = 500L
        const val MIN_SEARCH_MS = 2500L

        fun easeInOutCubic(p: Double): Double = if (p < 0.5) 4 * p * p * p else 1 - (-2 * p + 2).pow(3) / 2

        fun normalizeLon(lon: Double): Double {
            var l = (lon + 180.0) % 360.0
            if (l < 0) l += 360.0
            return l - 180.0
        }
    }
}
