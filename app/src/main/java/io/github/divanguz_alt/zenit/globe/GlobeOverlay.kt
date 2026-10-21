package io.github.divanguz_alt.zenit.globe

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.content.res.ResourcesCompat
import io.github.divanguz_alt.zenit.R
import io.github.divanguz_alt.zenit.orbits.Geo
import io.github.divanguz_alt.zenit.orbits.OrbitalObject
import io.github.divanguz_alt.zenit.orbits.gstime
import io.github.divanguz_alt.zenit.orbits.jdFromEpochMillis
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class IssInfo(val altitudeKm: Double, val speedKmh: Double, val periodMin: Double)

/**
 * Draws everything above the GL globe with the same [GlobeFrame] projection: sky shell, search scan,
 * satellites, signal waves, the user's position and the ISS easter egg.
 */
class GlobeOverlayPainter(context: Context) {
    private val typeface: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.roboto_flex) }.getOrNull()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@GlobeOverlayPainter.typeface
        fontVariationSettings = "'wght' 600"
    }
    private val p = FloatArray(4)
    private val q = FloatArray(4)

    private val cyan = Color(0xFF7FD3FF)
    private val green = Color(0xFF5BE38A)

    /** Reveal bookkeeping: first time each satellite key appeared. */
    private val firstSeen = HashMap<String, Long>()

    // ISS ring cache (recomputed at most every 500 ms of simulated time).
    private var issRing: Array<FloatArray>? = null
    private var issRingAt = Long.MIN_VALUE

    fun DrawScope.draw(
        f: GlobeFrame,
        scene: GlobeScene,
        controller: GlobeController,
        nowMs: Long,
        timeSec: Double,
        reducedMotion: Boolean,
        issShownAt: Long?,
        outerRadius: Float,
    ): IssInfo? {
        val d = f.density
        val r = f.radiusPx
        val c = Offset(f.centerX, f.centerY)
        val phase = controller.phase
        val shellK = if (scene.trueScale) outerRadius.coerceAtMost(4.3f) else 1.42f

        // --- Sky shell (dashed, slowly rotating) ---
        val dashOffset = if (reducedMotion) 0f else (-timeSec * 8.0 * d).toFloat()
        val shellColor = if (phase == GlobePhase.SEARCH) {
            cyan.copy(alpha = (0.18f + 0.12f * sin(timeSec * 3).toFloat()).coerceIn(0f, 1f))
        } else {
            Color.White.copy(alpha = 0.08f)
        }
        drawCircle(
            shellColor, radius = r * shellK, center = c,
            style = Stroke(width = d, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * d, 6 * d), dashOffset)),
        )

        // --- Search scan: three cyan arcs sweeping the shell ---
        if (phase == GlobePhase.SEARCH) {
            val a0 = if (reducedMotion) -PI / 2 else timeSec * 1.4
            for (i in 0 until 3) {
                val start = a0 - i * 0.18
                drawArc(
                    cyan.copy(alpha = 0.55f - i * 0.17f),
                    startAngle = Math.toDegrees(start).toFloat(),
                    sweepAngle = Math.toDegrees(0.14).toFloat(),
                    useCenter = false,
                    topLeft = Offset(c.x - r * shellK, c.y - r * shellK),
                    size = Size(2 * r * shellK, 2 * r * shellK),
                    style = Stroke(width = 2 * d, cap = StrokeCap.Round),
                )
            }
        }

        // --- Faint catalogue satellites ---
        for (b in scene.background) {
            f.project(b.latDeg, b.lonDeg, b.radius, p)
            if (GlobeFrame.hidden(p)) continue
            drawCircle(Color(b.constellation.color).copy(alpha = 0.28f), radius = 1.3f * d, center = Offset(p[0], p[1]))
        }

        val userKnown = scene.userLat != null && scene.userLon != null
        if (userKnown) f.project(scene.userLat!!, scene.userLon!!, 1f, q)
        val ux = q[0]; val uy = q[1]; val uz = q[2]

        // --- Satellites ---
        val present = HashSet<String>()
        data class Placed(val s: GlobeSatellite, val x: Float, val y: Float, val hidden: Boolean, val a: Float)
        val placed = scene.satellites.map { s ->
            present += s.key
            val seen = firstSeen.getOrPut(s.key) { nowMs }
            f.project(s.latDeg, s.lonDeg, s.radius, p)
            Placed(s, p[0], p[1], GlobeFrame.hidden(p), controller.revealAlpha(nowMs, s.order, seen))
        }
        firstSeen.keys.retainAll(present)

        // Waves only once locked: three arcs travelling from each used satellite towards the user.
        if (scene.signalWaves && phase == GlobePhase.LOCKED && userKnown && uz > 0f) {
            for (o in placed) {
                if (!o.s.used || o.hidden || o.a <= 0f) continue
                val col = Color(o.s.constellation.color)
                val dx = ux - o.x; val dy = uy - o.y
                val len = hypot(dx, dy)
                val ang = atan2(dy, dx)
                drawLine(
                    Brush.linearGradient(listOf(col.copy(alpha = 0x55 / 255f * o.a), col.copy(alpha = 0f)), Offset(o.x, o.y), Offset(ux, uy)),
                    Offset(o.x, o.y), Offset(ux, uy), strokeWidth = d,
                )
                if (reducedMotion) continue
                val seed = (o.s.key.hashCode() and 0xffff) / 65535.0
                for (i in 0 until 3) {
                    val prog = ((timeSec * 0.55 + i / 3.0 + seed) % 1.0).toFloat()
                    val rad = 10 * d + prog * (len - 10 * d)
                    if (rad <= 0f) continue
                    drawArc(
                        col.copy(alpha = (o.a * (1 - prog) * 0.95f).coerceIn(0f, 1f)),
                        startAngle = Math.toDegrees(ang - 0.2).toFloat(),
                        sweepAngle = Math.toDegrees(0.4).toFloat(),
                        useCenter = false,
                        topLeft = Offset(o.x - rad, o.y - rad),
                        size = Size(2 * rad, 2 * rad),
                        style = Stroke(width = 1.6f * d, cap = StrokeCap.Round),
                    )
                }
            }
        }

        labelPaint.textSize = 10 * d
        for (o in placed) {
            if (o.hidden || o.a <= 0f) continue
            val col = Color(o.s.constellation.color)
            val sc = if (reducedMotion) 1f else 0.6f + 0.4f * o.a
            val center = Offset(o.x, o.y)
            val label = if (o.s.atEdge) "${o.s.label} GEO" else o.s.label
            if (o.s.used) {
                drawCircle(
                    Brush.radialGradient(listOf(col.copy(alpha = 0x66 / 255f * o.a), col.copy(alpha = 0f)), center, 14 * sc * d),
                    radius = 14 * sc * d, center = center,
                )
                val a = o.a
                drawRect(col.copy(alpha = a), Offset(o.x - 8 * sc * d, o.y - d), Size(4.5f * sc * d, 2 * d))
                drawRect(col.copy(alpha = a), Offset(o.x + 3.5f * sc * d, o.y - d), Size(4.5f * sc * d, 2 * d))
                drawCircle(col.copy(alpha = a), radius = 3 * sc * d, center = center)
                text(label, o.x + 9 * d, o.y - 6 * d, Color(0xFFE8EAED).copy(alpha = 0.92f * a))
            } else if (scene.showUnused) {
                drawCircle(Color(0xFF9AA0A6).copy(alpha = o.a * 0.55f), radius = 2.2f * d, center = center)
                text(label, o.x + 5 * d, o.y - 4 * d, Color(0xFF9AA0A6).copy(alpha = o.a * 0.4f))
            }
        }

        // --- ISS ---
        val issInfo = if (issShownAt != null && scene.iss != null) drawIss(f, scene.iss, scene.issTimeLapse, nowMs, issShownAt, reducedMotion) else null

        // --- User position: white dot, green core, two pulsing rings ---
        if (userKnown && uz > 0f && phase != GlobePhase.SEARCH) {
            for (i in 0 until 2) {
                val prog = if (reducedMotion) 0.5f else ((timeSec * 0.6 + i * 0.5) % 1.0).toFloat()
                drawCircle(
                    green.copy(alpha = (1 - prog) * 0.8f), radius = (4 + prog * 20) * d, center = Offset(ux, uy),
                    style = Stroke(width = 1.5f * d),
                )
            }
            drawCircle(Color.White, radius = 4.5f * d, center = Offset(ux, uy))
            drawCircle(green, radius = 2.6f * d, center = Offset(ux, uy))
        }
        return issInfo
    }

    private fun DrawScope.text(s: String, x: Float, y: Float, color: Color) {
        labelPaint.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
        )
        drawContext.canvas.nativeCanvas.drawText(s, x, y, labelPaint)
    }

    private fun DrawScope.drawIss(f: GlobeFrame, iss: OrbitalObject, timeLapse: Boolean, nowMs: Long, shownAt: Long, reducedMotion: Boolean): IssInfo? {
        val d = f.density
        val simMs = if (timeLapse) shownAt + (nowMs - shownAt) * TIME_LAPSE else nowMs
        val jd = jdFromEpochMillis(simMs)
        val gmst = gstime(jd)
        val sv = iss.satrec.propagateJd(jd) ?: return null
        val alpha = if (reducedMotion) 1f else ((nowMs - shownAt) / 700f).coerceIn(0f, 1f)
        val periodMin = iss.satrec.periodMinutes

        // Orbit ring in the inertial frame, drawn against the Earth's current orientation.
        if (issRing == null || kotlin.math.abs(simMs - issRingAt) > 500) {
            issRing = Array(RING_SAMPLES + 1) { i ->
                val t = jd + (i.toDouble() / RING_SAMPLES - 0.5) * periodMin / 1440.0
                val s = iss.satrec.propagateJd(t)
                if (s == null) floatArrayOf(0f, 0f, 0f, 0f) else unitEcef(s.position.x, s.position.y, s.position.z, gmst)
            }
            issRingAt = simMs
        }
        val ring = issRing!!
        val dash = PathEffect.dashPathEffect(floatArrayOf(2 * d, 5 * d), 0f)
        for (i in 0 until RING_SAMPLES) {
            val a = ring[i]; val b = ring[i + 1]
            f.projectVector(a[0].toDouble(), a[1].toDouble(), a[2].toDouble(), a[3], p)
            if (GlobeFrame.hidden(p)) continue
            f.projectVector(b[0].toDouble(), b[1].toDouble(), b[2].toDouble(), b[3], q)
            if (GlobeFrame.hidden(q)) continue
            drawLine(Color(0xFFE8EAED).copy(alpha = 0.35f * alpha), Offset(p[0], p[1]), Offset(q[0], q[1]), strokeWidth = d, pathEffect = dash)
        }

        // Glowing trail over the last ~10 minutes of flight.
        val trailStepDays = periodMin / 1440.0 * (0.03 / (2 * PI))
        var prev: FloatArray? = null
        for (i in 0..TRAIL) {
            val s = iss.satrec.propagateJd(jd - i * trailStepDays) ?: break
            val u = unitEcef(s.position.x, s.position.y, s.position.z, gmst)
            f.projectVector(u[0].toDouble(), u[1].toDouble(), u[2].toDouble(), u[3], p)
            val cur = p.copyOf()
            if (prev != null && !GlobeFrame.hidden(cur) && !GlobeFrame.hidden(prev)) {
                drawLine(
                    Color.White.copy(alpha = alpha * 0.7f * (1 - i / TRAIL.toFloat())),
                    Offset(prev[0], prev[1]), Offset(cur[0], cur[1]), strokeWidth = 2 * d, cap = StrokeCap.Round,
                )
            }
            prev = cur
        }

        val u = unitEcef(sv.position.x, sv.position.y, sv.position.z, gmst)
        f.projectVector(u[0].toDouble(), u[1].toDouble(), u[2].toDouble(), u[3], p)
        val ahead = iss.satrec.propagateJd(jd + 20.0 / 86400.0)
        if (!GlobeFrame.hidden(p)) {
            var heading = 0f
            if (ahead != null) {
                val v = unitEcef(ahead.position.x, ahead.position.y, ahead.position.z, gmst)
                f.projectVector(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), v[3], q)
                heading = atan2(q[1] - p[1], q[0] - p[0])
            }
            val center = Offset(p[0], p[1])
            translate(center.x, center.y) {
                rotateRad(heading, pivot = Offset.Zero) {
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f * alpha), Color.Transparent), Offset.Zero, 16 * d),
                        radius = 16 * d, center = Offset.Zero,
                    )
                    val panel = Color(0xFF8AB4F8).copy(alpha = alpha)
                    drawRect(panel, Offset(-6 * d, -8 * d), Size(3 * d, 16 * d))
                    drawRect(panel, Offset(3 * d, -8 * d), Size(3 * d, 16 * d))
                    val body = Color(0xFFF5F5F7).copy(alpha = alpha)
                    drawRect(body, Offset(-3 * d, -d), Size(6 * d, 2 * d))
                    drawRect(body, Offset(-1.5f * d, -3 * d), Size(3 * d, 6 * d))
                }
            }
            labelPaint.fontVariationSettings = "'wght' 700"
            text("ISS", p[0] + 11 * d, p[1] - 9 * d, Color(0xFFF5F5F7).copy(alpha = alpha))
            labelPaint.fontVariationSettings = "'wght' 600"
        }

        val ecef = Geo.temeToEcef(sv.position, gmst)
        val geo = Geo.ecefToGeodetic(ecef)
        // Ground-relative speed is close to the inertial speed; the conventional figure is inertial.
        return IssInfo(geo.heightKm, sv.velocity.norm * 3600.0, periodMin)
    }

    /** TEME position → Earth-fixed unit vector plus its length in mean Earth radii. */
    private fun unitEcef(x: Double, y: Double, z: Double, gmst: Double): FloatArray {
        val c = cos(gmst); val s = sin(gmst)
        val ex = x * c + y * s
        val ey = -x * s + y * c
        val n = sqrt(ex * ex + ey * ey + z * z)
        return floatArrayOf((ex / n).toFloat(), (ey / n).toFloat(), (z / n).toFloat(), (n / Geo.MEAN_EARTH_RADIUS_KM).toFloat())
    }

    companion object {
        /** Time-lapse: one ISS orbit in about a minute and a half. */
        const val TIME_LAPSE = 60L
        private const val RING_SAMPLES = 160
        private const val TRAIL = 24
    }
}
