package io.github.divanguz_alt.zenit.car

import io.github.divanguz_alt.zenit.globe.GlobePhase
import io.github.divanguz_alt.zenit.globe.IssInfo
import io.github.divanguz_alt.zenit.gnss.FixType
import io.github.divanguz_alt.zenit.ui.DashboardState
import io.github.divanguz_alt.zenit.ui.Format
import kotlin.math.roundToLong

/** The two lines on the car's navigation card. Kept short: drivers glance, they don't read. */
data class CarStatus(val title: String, val text: String) {
    companion object {
        fun from(d: DashboardState, phase: GlobePhase, issShown: Boolean, iss: IssInfo?): CarStatus {
            if (issShown) {
                return if (iss != null) {
                    CarStatus(
                        "International Space Station",
                        "${iss.altitudeKm.roundToLong()} km · ${Format.spacedThousands(iss.speedKmh.roundToLong())} km/h",
                    )
                } else {
                    CarStatus("International Space Station", "Orbit data not downloaded yet")
                }
            }
            if (!d.providerEnabled) return CarStatus("Location is off", "Turn on location on your phone")
            return when (phase) {
                GlobePhase.SEARCH -> CarStatus(
                    "Searching for satellites",
                    if (d.visibleCount > 0) "${d.visibleCount} in view" else "Keep the phone's view of the sky clear",
                )
                GlobePhase.LOCKING -> CarStatus("Satellites found", "Locking position…")
                GlobePhase.LOCKED -> {
                    val fix = if (d.fixType == FixType.FIX_2D) "2D fix" else "3D fix"
                    val title = if (d.usedCount > 0) "$fix · ${d.usedCount} of ${d.visibleCount} satellites" else "$fix · ${d.visibleCount} in view"
                    val parts = listOfNotNull(
                        d.accuracyText.takeIf { it.isNotEmpty() },
                        d.speed.takeIf { it != Format.DASH }?.let { "$it ${d.speedUnit}" },
                        d.altitude.takeIf { it != Format.DASH }?.let { "$it ${d.altitudeUnit}" },
                    )
                    CarStatus(title, parts.joinToString(" · ").ifEmpty { Format.DASH })
                }
            }
        }
    }
}
