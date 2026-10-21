package io.github.divanguz_alt.zenit.ui

import io.github.divanguz_alt.zenit.settings.AltitudeUnit
import io.github.divanguz_alt.zenit.settings.CoordinateFormat
import io.github.divanguz_alt.zenit.settings.PressureUnit
import io.github.divanguz_alt.zenit.settings.SpeedUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Locale-independent number and unit formatting for the dashboard. */
object Format {
    const val DASH = "—"
    private val L = Locale.US

    private val COMPASS = arrayOf(
        "N", "N-NE", "NE", "E-NE", "E", "E-SE", "SE", "S-SE",
        "S", "S-SW", "SW", "W-SW", "W", "W-NW", "NW", "N-NW",
    )

    fun fixed(v: Double, decimals: Int): String = String.format(L, "%.${decimals}f", v)

    /** 45°48.8190′N */
    fun ddm(value: Double, isLatitude: Boolean): String {
        val a = abs(value)
        var d = floor(a).toInt()
        var m = (a - d) * 60.0
        if (String.format(L, "%.4f", m) == "60.0000") { d += 1; m = 0.0 }
        return "$d°" + String.format(L, "%07.4f", m) + "′" + hemisphere(value, isLatitude)
    }

    /** 45°48′49.14″N */
    fun dms(value: Double, isLatitude: Boolean): String {
        val a = abs(value)
        var d = floor(a).toInt()
        val totalMin = (a - d) * 60.0
        var m = floor(totalMin).toInt()
        var s = (totalMin - m) * 60.0
        if (String.format(L, "%.2f", s) == "60.00") { s = 0.0; m += 1 }
        if (m == 60) { m = 0; d += 1 }
        return "$d°" + m.toString().padStart(2, '0') + "′" + String.format(L, "%05.2f", s) + "″" + hemisphere(value, isLatitude)
    }

    /** 45.813650° */
    fun dd(value: Double): String = String.format(L, "%.6f°", value)

    fun coordinate(value: Double, isLatitude: Boolean, format: CoordinateFormat): String = when (format) {
        CoordinateFormat.DDM -> ddm(value, isLatitude)
        CoordinateFormat.DMS -> dms(value, isLatitude)
        CoordinateFormat.DD -> dd(value)
    }

    /** Secondary line under the main coordinate: decimal, or DDM when the main format is decimal. */
    fun coordinateSecondary(value: Double, isLatitude: Boolean, format: CoordinateFormat): String =
        if (format == CoordinateFormat.DD) ddm(value, isLatitude) else dd(value)

    private fun hemisphere(v: Double, lat: Boolean) = if (lat) (if (v >= 0) "N" else "S") else (if (v >= 0) "E" else "W")

    fun speed(mps: Double, unit: SpeedUnit): Double = when (unit) {
        SpeedUnit.KMH -> mps * 3.6
        SpeedUnit.MPH -> mps * 2.2369362920544
        SpeedUnit.KNOTS -> mps * 1.9438444924406
        SpeedUnit.MPS -> mps
    }

    fun altitude(m: Double, unit: AltitudeUnit): Double = when (unit) {
        AltitudeUnit.METERS -> m
        AltitudeUnit.FEET -> m / 0.3048
    }

    fun pressure(hpa: Double, unit: PressureUnit): Double = when (unit) {
        PressureUnit.HPA -> hpa
        PressureUnit.INHG -> hpa * 0.029529983071445
        PressureUnit.MMHG -> hpa * 0.75006157584566
    }

    fun pressureText(hpa: Double, unit: PressureUnit): String = when (unit) {
        PressureUnit.HPA -> fixed(pressure(hpa, unit), 1)
        PressureUnit.INHG -> fixed(pressure(hpa, unit), 2)
        PressureUnit.MMHG -> fixed(pressure(hpa, unit), 1)
    }

    /** 16-point compass name, e.g. 157.5° -> S-SE. */
    fun compassPoint(deg: Double): String {
        val i = (((deg % 360.0) + 360.0) % 360.0 / 22.5).roundToInt() % 16
        return COMPASS[i]
    }

    fun normalizeDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    fun thousands(n: Long): String = String.format(L, "%,d", n)

    /** "3 min ago", "5 h ago", "2 d ago" */
    fun age(millis: Long): String {
        val min = millis / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${(min / 60.0).roundToLong()} h ago"
            else -> "${min / 1440} d ago"
        }
    }

    /** Declination "5.2° E" / "1.0° W". */
    fun declination(deg: Double): String = fixed(abs(deg), 1) + "° " + if (deg >= 0) "E" else "W"

    /** "27 580" (thin grouping like the prototype). */
    fun spacedThousands(n: Long): String = String.format(L, "%,d", n).replace(',', ' ')
}
