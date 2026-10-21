package io.github.divanguz_alt.zenit.orbits

import io.github.divanguz_alt.zenit.gnss.Constellation

/** A satellite direction measured by the receiver. */
data class MeasuredDirection(val key: String, val constellation: Constellation, val svid: Int, val azDeg: Double, val elDeg: Double)

/** A satellite direction predicted by SGP4 for the observer. */
data class PredictedDirection(val id: Int, val constellation: Constellation, val prn: Int?, val azDeg: Double, val elDeg: Double)

data class SatelliteMatch(val measuredKey: String, val predictedId: Int, val separationDeg: Double, val byPrn: Boolean)

/**
 * Pairs receiver-reported satellites with SGP4 predictions.
 *
 * 1. Where the catalogue name carries the PRN (GPS, BeiDou, SBAS), pair by PRN, as long as the
 *    predicted direction is plausibly close (guards against PRN reassignments).
 * 2. Everything else (Galileo, GLONASS, leftovers) is paired geometrically: greedy on the smallest
 *    angular separation, accepting only pairs closer than [maxSeparationDeg].
 */
object SatelliteMatcher {
    const val DEFAULT_MAX_SEPARATION_DEG = 6.0
    private const val PRN_SANITY_DEG = 15.0

    fun match(
        measured: List<MeasuredDirection>,
        predicted: List<PredictedDirection>,
        maxSeparationDeg: Double = DEFAULT_MAX_SEPARATION_DEG,
    ): List<SatelliteMatch> {
        val result = mutableListOf<SatelliteMatch>()
        val usedMeasured = HashSet<String>()
        val usedPredicted = HashSet<Int>()

        // 1. PRN-assisted
        for (m in measured) {
            val p = predicted.firstOrNull { it.constellation == m.constellation && it.prn == m.svid && it.id !in usedPredicted }
                ?: continue
            val sep = Geo.skySeparation(m.azDeg, m.elDeg, p.azDeg, p.elDeg)
            if (sep <= PRN_SANITY_DEG) {
                result += SatelliteMatch(m.key, p.id, sep, byPrn = true)
                usedMeasured += m.key
                usedPredicted += p.id
            }
        }

        // 2. Geometric, per constellation
        data class Candidate(val m: MeasuredDirection, val p: PredictedDirection, val sep: Double)
        val candidates = ArrayList<Candidate>()
        for (m in measured) {
            if (m.key in usedMeasured) continue
            for (p in predicted) {
                if (p.id in usedPredicted || p.constellation != m.constellation) continue
                val sep = Geo.skySeparation(m.azDeg, m.elDeg, p.azDeg, p.elDeg)
                if (sep < maxSeparationDeg) candidates += Candidate(m, p, sep)
            }
        }
        candidates.sortBy { it.sep }
        for (c in candidates) {
            if (c.m.key in usedMeasured || c.p.id in usedPredicted) continue
            result += SatelliteMatch(c.m.key, c.p.id, c.sep, byPrn = false)
            usedMeasured += c.m.key
            usedPredicted += c.p.id
        }
        return result
    }
}
