package io.github.divanguz_alt.zenit.gnss

enum class FixType { NONE, FIX_2D, FIX_3D }

data class Dop(val pdop: Double?, val hdop: Double?, val vdop: Double?)

data class GgaData(val quality: Int, val satellitesUsed: Int?, val hdop: Double?, val altitudeMslM: Double?, val geoidSeparationM: Double?)

data class GsaData(val fixType: FixType, val dop: Dop)

/** Minimal NMEA 0183 parser for the sentences Zenit needs (GGA, GSA), with checksum validation. */
object Nmea {

    /** Returns the comma-separated fields (without '$' and checksum) or null when the checksum is wrong. */
    fun fields(sentence: String): List<String>? {
        val s = sentence.trim()
        if (!s.startsWith("$")) return null
        val star = s.lastIndexOf('*')
        val body = if (star >= 0) s.substring(1, star) else s.substring(1)
        if (star >= 0) {
            val expected = s.substring(star + 1).take(2).toIntOrNull(16) ?: return null
            var sum = 0
            for (c in body) sum = sum xor c.code
            if (sum != expected) return null
        }
        return body.split(',')
    }

    private fun type(f: List<String>): String = f[0].takeLast(3)

    fun parseGga(sentence: String): GgaData? {
        val f = fields(sentence) ?: return null
        if (f.size < 12 || type(f) != "GGA") return null
        val quality = f[6].toIntOrNull() ?: 0
        return GgaData(
            quality = quality,
            satellitesUsed = f[7].toIntOrNull(),
            hdop = f[8].toDoubleOrNull(),
            altitudeMslM = if (quality > 0) f[9].toDoubleOrNull() else null,
            geoidSeparationM = f[11].toDoubleOrNull(),
        )
    }

    fun parseGsa(sentence: String): GsaData? {
        val f = fields(sentence) ?: return null
        if (f.size < 18 || type(f) != "GSA") return null
        val fix = when (f[2].toIntOrNull()) {
            2 -> FixType.FIX_2D
            3 -> FixType.FIX_3D
            else -> FixType.NONE
        }
        return GsaData(fix, Dop(f[15].toDoubleOrNull(), f[16].toDoubleOrNull(), f[17].toDoubleOrNull()))
    }
}
