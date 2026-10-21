package io.github.divanguz_alt.zenit.globe

import io.github.divanguz_alt.zenit.gnss.Constellation
import io.github.divanguz_alt.zenit.orbits.OrbitalObject
import io.github.divanguz_alt.zenit.settings.GlobeStyle

/** A satellite placed on the globe. Direction is real; [radius] (Earth radii) is display scale. */
data class GlobeSatellite(
    val key: String,
    val label: String,
    val constellation: Constellation,
    val used: Boolean,
    val cn0: Float,
    val latDeg: Double,
    val lonDeg: Double,
    val radius: Float,
    /** Geosynchronous / IGSO satellites pinned to the edge of the view. */
    val atEdge: Boolean,
    /** Position comes from SGP4 (true) or from receiver az/el at nominal altitude (false). */
    val fromOrbit: Boolean,
    /** Appearance order (strongest first) for the staggered reveal. */
    val order: Int,
)

/** Faint catalogue satellite ("Show all constellation satellites"). */
data class BackgroundSatellite(val latDeg: Double, val lonDeg: Double, val radius: Float, val constellation: Constellation)

data class GlobeScene(
    val userLat: Double? = null,
    val userLon: Double? = null,
    /** First fix with accuracy < 50 m, still fresh (< 10 s old). */
    val locked: Boolean = false,
    val satellites: List<GlobeSatellite> = emptyList(),
    val background: List<BackgroundSatellite> = emptyList(),
    val style: GlobeStyle = GlobeStyle.ILLUSTRATED,
    val dayNight: Boolean = false,
    val signalWaves: Boolean = true,
    val showUnused: Boolean = true,
    val trueScale: Boolean = false,
    val issTimeLapse: Boolean = false,
    val iss: OrbitalObject? = null,
    val orbitsAvailable: Boolean = false,
    val subSolarLat: Double = 0.0,
    val subSolarLon: Double = 0.0,
)
