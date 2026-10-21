/*
 * SGP4/SDP4 orbit propagator.
 *
 * Kotlin port of satellite.js (https://github.com/shashwatak/satellite-js, MIT License,
 * Copyright (c) 2013 Shashwat Kandadai and UCSC), which is itself a port of David Vallado's
 * reference implementation ("Revisiting Spacetrack Report #3", AIAA 2006-6753).
 * WGS-72 constants, as required for consistency with published element sets.
 */
package io.github.divanguz_alt.zenit.orbits

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Wgs72 {
    const val MU = 398600.8
    const val EARTH_RADIUS = 6378.135
    val XKE = 60.0 / sqrt(EARTH_RADIUS * EARTH_RADIUS * EARTH_RADIUS / MU)
    val VKMPERSEC = EARTH_RADIUS * XKE / 60.0
    val TUMIN = 1.0 / XKE
    const val J2 = 0.001082616
    const val J3 = -0.00000253881
    const val J4 = -0.00000165597
    const val J3OJ2 = J3 / J2
}

private const val TWO_PI = 2.0 * PI
private const val X2O3 = 2.0 / 3.0
const val DEG2RAD = PI / 180.0

/** JS-style remainder (sign follows the dividend), matching the reference code. */
private fun Double.jmod(m: Double): Double = this % m

data class Vec3(val x: Double, val y: Double, val z: Double) {
    val norm: Double get() = sqrt(x * x + y * y + z * z)
}

data class StateVector(val position: Vec3, val velocity: Vec3)

/**
 * Satellite record. Construct with [Satrec.fromTle] or [Satrec.fromElements]; propagate with
 * [propagate] (minutes since epoch). Mutable internal state mirrors the reference implementation.
 */
class Satrec private constructor() {
    var satnum: String = ""
    var jdsatepoch: Double = 0.0
    var error: Int = 0
    var operationmode: Char = 'i'
    var init: Char = 'y'
    var method: Char = 'n'
    var isimp = 0

    // Mean elements (radians, rad/min)
    var bstar = 0.0; var ecco = 0.0; var argpo = 0.0; var inclo = 0.0; var mo = 0.0; var no = 0.0; var nodeo = 0.0
    var a = 0.0; var alta = 0.0; var altp = 0.0

    // Near-earth
    var aycof = 0.0; var con41 = 0.0; var cc1 = 0.0; var cc4 = 0.0; var cc5 = 0.0
    var d2 = 0.0; var d3 = 0.0; var d4 = 0.0; var delmo = 0.0; var eta = 0.0
    var argpdot = 0.0; var omgcof = 0.0; var sinmao = 0.0; var t = 0.0
    var t2cof = 0.0; var t3cof = 0.0; var t4cof = 0.0; var t5cof = 0.0
    var x1mth2 = 0.0; var x7thm1 = 0.0; var mdot = 0.0; var nodedot = 0.0
    var xlcof = 0.0; var xmcof = 0.0; var nodecf = 0.0

    // Deep space
    var irez = 0
    var d2201 = 0.0; var d2211 = 0.0; var d3210 = 0.0; var d3222 = 0.0; var d4410 = 0.0; var d4422 = 0.0
    var d5220 = 0.0; var d5232 = 0.0; var d5421 = 0.0; var d5433 = 0.0
    var dedt = 0.0; var del1 = 0.0; var del2 = 0.0; var del3 = 0.0; var didt = 0.0; var dmdt = 0.0
    var dnodt = 0.0; var domdt = 0.0
    var e3 = 0.0; var ee2 = 0.0; var peo = 0.0; var pgho = 0.0; var pho = 0.0; var pinco = 0.0; var plo = 0.0
    var se2 = 0.0; var se3 = 0.0; var sgh2 = 0.0; var sgh3 = 0.0; var sgh4 = 0.0; var sh2 = 0.0; var sh3 = 0.0
    var si2 = 0.0; var si3 = 0.0; var sl2 = 0.0; var sl3 = 0.0; var sl4 = 0.0
    var gsto = 0.0; var xfact = 0.0
    var xgh2 = 0.0; var xgh3 = 0.0; var xgh4 = 0.0; var xh2 = 0.0; var xh3 = 0.0; var xi2 = 0.0; var xi3 = 0.0
    var xl2 = 0.0; var xl3 = 0.0; var xl4 = 0.0; var xlamo = 0.0; var zmol = 0.0; var zmos = 0.0
    var atime = 0.0; var xli = 0.0; var xni = 0.0

    /** Orbital period in minutes. */
    val periodMinutes: Double get() = TWO_PI / no

    /** Propagate to [tsince] minutes from epoch. Returns TEME position (km) and velocity (km/s), or null on error. */
    fun propagate(tsince: Double): StateVector? = sgp4(this, tsince)

    /** Propagate to a Julian date (UTC). */
    fun propagateJd(jd: Double): StateVector? = propagate((jd - jdsatepoch) * 1440.0)

    companion object {
        /** Parse a two-line element set (opsmode 'i', WGS-72). */
        fun fromTle(line1: String, line2: String, opsmode: Char = 'i'): Satrec {
            val xpdotp = 1440.0 / TWO_PI
            val rec = Satrec()
            rec.satnum = line1.substring(2, 7).trim()
            val epochyr = line1.substring(18, 20).trim().toInt()
            val epochdays = line1.substring(20, 32).trim().toDouble()
            val bstarMant = line1.substring(53, 59).trim().replace(" ", "")
            val bstarExp = line1.substring(59, 61).trim()
            val bstar = parseImpliedDecimal(bstarMant, bstarExp)
            val inclo = line2.substring(8, 16).trim().toDouble()
            val nodeo = line2.substring(17, 25).trim().toDouble()
            val ecco = ("." + line2.substring(26, 33).trim().replace(' ', '0')).toDouble()
            val argpo = line2.substring(34, 42).trim().toDouble()
            val mo = line2.substring(43, 51).trim().toDouble()
            val no = line2.substring(52, 63).trim().toDouble() / xpdotp
            val year = if (epochyr < 57) epochyr + 2000 else epochyr + 1900
            val jd = jdayFromYearDoy(year, epochdays)
            rec.jdsatepoch = jd
            sgp4init(
                rec, opsmode, jd - 2433281.5, bstar, ecco, argpo * DEG2RAD, inclo * DEG2RAD,
                mo * DEG2RAD, no, nodeo * DEG2RAD,
            )
            return rec
        }

        /**
         * Build from mean elements, e.g. from a CCSDS OMM record. Angles in degrees,
         * mean motion in revolutions per day, epoch as Julian date (UTC).
         */
        fun fromElements(
            satnum: String,
            epochJd: Double,
            meanMotionRevPerDay: Double,
            eccentricity: Double,
            inclinationDeg: Double,
            raanDeg: Double,
            argPerigeeDeg: Double,
            meanAnomalyDeg: Double,
            bstar: Double,
            opsmode: Char = 'i',
        ): Satrec {
            val rec = Satrec()
            rec.satnum = satnum
            rec.jdsatepoch = epochJd
            sgp4init(
                rec, opsmode, epochJd - 2433281.5, bstar, eccentricity, argPerigeeDeg * DEG2RAD,
                inclinationDeg * DEG2RAD, meanAnomalyDeg * DEG2RAD, meanMotionRevPerDay * TWO_PI / 1440.0,
                raanDeg * DEG2RAD,
            )
            return rec
        }

        private fun parseImpliedDecimal(mantissa: String, exponent: String): Double {
            if (mantissa.isEmpty()) return 0.0
            val sign = if (mantissa.startsWith("-")) -1.0 else 1.0
            val digits = mantissa.trimStart('-', '+')
            val exp = exponent.ifEmpty { "0" }.replace("+", "").toInt()
            return sign * ("0.$digits").toDouble() * 10.0.pow(exp)
        }
    }
}

/** Julian date from calendar components (UTC). */
fun jday(year: Int, mon: Int, day: Int, hr: Int, minute: Int, sec: Double): Double =
    367.0 * year - floor(7 * (year + floor((mon + 9) / 12.0)) * 0.25) + floor(275 * mon / 9.0) +
        day + 1721013.5 + ((sec / 60.0 + minute) / 60.0 + hr) / 24.0

/** Julian date from a year and fractional day-of-year (1.0 = Jan 1, 00:00 UTC). */
fun jdayFromYearDoy(year: Int, doy: Double): Double = jday(year, 1, 0, 0, 0, 0.0) + doy

/** Julian date from Unix epoch milliseconds. */
fun jdFromEpochMillis(millis: Long): Double = millis / 86_400_000.0 + 2440587.5

/** Greenwich mean sidereal time (radians), IAU-82 model. */
fun gstime(jdut1: Double): Double {
    val tut1 = (jdut1 - 2451545.0) / 36525.0
    var temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 +
        (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841
    temp = (temp * DEG2RAD / 240.0).jmod(TWO_PI)
    if (temp < 0.0) temp += TWO_PI
    return temp
}

private fun sgp4init(
    satrec: Satrec,
    opsmode: Char,
    epoch: Double,
    xbstar: Double,
    xecco: Double,
    xargpo: Double,
    xinclo: Double,
    xmo: Double,
    xno: Double,
    xnodeo: Double,
) {
    val temp4 = 1.5e-12
    satrec.bstar = xbstar
    satrec.ecco = xecco
    satrec.argpo = xargpo
    satrec.inclo = xinclo
    satrec.mo = xmo
    satrec.no = xno
    satrec.nodeo = xnodeo
    satrec.operationmode = opsmode

    val ss = 78.0 / Wgs72.EARTH_RADIUS + 1.0
    val qzms2ttemp = (120.0 - 78.0) / Wgs72.EARTH_RADIUS
    val qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp

    satrec.init = 'y'
    satrec.t = 0.0

    // ---- initl ----
    val ecco = satrec.ecco
    val eccsq = ecco * ecco
    val omeosq = 1.0 - eccsq
    val rteosq = sqrt(omeosq)
    val cosio = cos(satrec.inclo)
    val cosio2 = cosio * cosio
    val ak = (Wgs72.XKE / satrec.no).pow(X2O3)
    val d1 = 0.75 * Wgs72.J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
    var delPrime = d1 / (ak * ak)
    val adel = ak * (1.0 - delPrime * delPrime - delPrime * (1.0 / 3.0 + 134.0 * delPrime * delPrime / 81.0))
    delPrime = d1 / (adel * adel)
    satrec.no = satrec.no / (1.0 + delPrime)
    val ao = (Wgs72.XKE / satrec.no).pow(X2O3)
    val sinio = sin(satrec.inclo)
    val po = ao * omeosq
    val con42 = 1.0 - 5.0 * cosio2
    satrec.con41 = -con42 - cosio2 - cosio2
    val posq = po * po
    val rp = ao * (1.0 - ecco)
    satrec.method = 'n'
    satrec.gsto = if (opsmode == 'a') {
        val ts70 = epoch - 7305.0
        val ds70 = floor(ts70 + 1.0e-8)
        val tfrac = ts70 - ds70
        val c1 = 1.72027916940703639e-2
        val thgr70 = 1.7321343856509374
        val fk5r = 5.07551419432269442e-15
        val c1p2p = c1 + TWO_PI
        var g = (thgr70 + c1 * ds70 + c1p2p * tfrac + ts70 * ts70 * fk5r).jmod(TWO_PI)
        if (g < 0.0) g += TWO_PI
        g
    } else {
        gstime(epoch + 2433281.5)
    }
    // ---- end initl ----

    satrec.a = (satrec.no * Wgs72.TUMIN).pow(-2.0 / 3.0)
    satrec.alta = satrec.a * (1.0 + satrec.ecco) - 1.0
    satrec.altp = satrec.a * (1.0 - satrec.ecco) - 1.0
    satrec.error = 0

    if (omeosq >= 0.0 || satrec.no >= 0.0) {
        satrec.isimp = 0
        if (rp < 220.0 / Wgs72.EARTH_RADIUS + 1.0) satrec.isimp = 1
        var sfour = ss
        var qzms24 = qzms2t
        val perige = (rp - 1.0) * Wgs72.EARTH_RADIUS

        if (perige < 156.0) {
            sfour = perige - 78.0
            if (perige < 98.0) sfour = 20.0
            val qzms24temp = (120.0 - sfour) / Wgs72.EARTH_RADIUS
            qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp
            sfour = sfour / Wgs72.EARTH_RADIUS + 1.0
        }
        val pinvsq = 1.0 / posq
        val tsi = 1.0 / (ao - sfour)
        satrec.eta = ao * satrec.ecco * tsi
        val etasq = satrec.eta * satrec.eta
        val eeta = satrec.ecco * satrec.eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4.0)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * satrec.no * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
            0.375 * Wgs72.J2 * tsi / psisq * satrec.con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        satrec.cc1 = satrec.bstar * cc2
        var cc3 = 0.0
        if (satrec.ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * Wgs72.J3OJ2 * satrec.no * sinio / satrec.ecco
        satrec.x1mth2 = 1.0 - cosio2
        satrec.cc4 = 2.0 * satrec.no * coef1 * ao * omeosq * (satrec.eta * (2.0 + 0.5 * etasq) +
            satrec.ecco * (0.5 + 2.0 * etasq) - Wgs72.J2 * tsi / (ao * psisq) *
            (-3.0 * satrec.con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                0.75 * satrec.x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * satrec.argpo)))
        satrec.cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * Wgs72.J2 * pinvsq * satrec.no
        val temp2 = 0.5 * temp1 * Wgs72.J2 * pinvsq
        val temp3 = -0.46875 * Wgs72.J4 * pinvsq * pinvsq * satrec.no
        satrec.mdot = satrec.no + 0.5 * temp1 * rteosq * satrec.con41 +
            0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        satrec.argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
            temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        satrec.nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        val xpidot = satrec.argpdot + satrec.nodedot
        satrec.omgcof = satrec.bstar * cc3 * cos(satrec.argpo)
        satrec.xmcof = 0.0
        if (satrec.ecco > 1.0e-4) satrec.xmcof = -X2O3 * coef * satrec.bstar / eeta
        satrec.nodecf = 3.5 * omeosq * xhdot1 * satrec.cc1
        satrec.t2cof = 1.5 * satrec.cc1
        satrec.xlcof = if (abs(cosio + 1.0) > 1.5e-12) {
            -0.25 * Wgs72.J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        } else {
            -0.25 * Wgs72.J3OJ2 * sinio * (3.0 + 5.0 * cosio) / temp4
        }
        satrec.aycof = -0.5 * Wgs72.J3OJ2 * sinio
        val delmotemp = 1.0 + satrec.eta * cos(satrec.mo)
        satrec.delmo = delmotemp * delmotemp * delmotemp
        satrec.sinmao = sin(satrec.mo)
        satrec.x7thm1 = 7.0 * cosio2 - 1.0

        // Deep space initialisation
        if (TWO_PI / satrec.no >= 225.0) {
            satrec.method = 'd'
            satrec.isimp = 1
            val tc = 0.0
            val inclm = satrec.inclo
            val ds = dscom(epoch, satrec.ecco, satrec.argpo, tc, satrec.inclo, satrec.nodeo, satrec.no)
            satrec.e3 = ds.e3; satrec.ee2 = ds.ee2
            satrec.peo = ds.peo; satrec.pgho = ds.pgho; satrec.pho = ds.pho; satrec.pinco = ds.pinco; satrec.plo = ds.plo
            satrec.se2 = ds.se2; satrec.se3 = ds.se3
            satrec.sgh2 = ds.sgh2; satrec.sgh3 = ds.sgh3; satrec.sgh4 = ds.sgh4
            satrec.sh2 = ds.sh2; satrec.sh3 = ds.sh3
            satrec.si2 = ds.si2; satrec.si3 = ds.si3
            satrec.sl2 = ds.sl2; satrec.sl3 = ds.sl3; satrec.sl4 = ds.sl4
            satrec.xgh2 = ds.xgh2; satrec.xgh3 = ds.xgh3; satrec.xgh4 = ds.xgh4
            satrec.xh2 = ds.xh2; satrec.xh3 = ds.xh3
            satrec.xi2 = ds.xi2; satrec.xi3 = ds.xi3
            satrec.xl2 = ds.xl2; satrec.xl3 = ds.xl3; satrec.xl4 = ds.xl4
            satrec.zmol = ds.zmol; satrec.zmos = ds.zmos

            val dp = dpper(satrec, 'y', satrec.ecco, satrec.inclo, satrec.nodeo, satrec.argpo, satrec.mo, satrec.operationmode)
            satrec.ecco = dp.ep
            satrec.inclo = dp.inclp
            satrec.nodeo = dp.nodep
            satrec.argpo = dp.argpp
            satrec.mo = dp.mp

            dsinit(satrec, ds, tc, xpidot, eccsq, inclm, ds.nm)
        }

        if (satrec.isimp != 1) {
            val cc1sq = satrec.cc1 * satrec.cc1
            satrec.d2 = 4.0 * ao * tsi * cc1sq
            val temp = satrec.d2 * tsi * satrec.cc1 / 3.0
            satrec.d3 = (17.0 * ao + sfour) * temp
            satrec.d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * satrec.cc1
            satrec.t3cof = satrec.d2 + 2.0 * cc1sq
            satrec.t4cof = 0.25 * (3.0 * satrec.d3 + satrec.cc1 * (12.0 * satrec.d2 + 10.0 * cc1sq))
            satrec.t5cof = 0.2 * (3.0 * satrec.d4 + 12.0 * satrec.cc1 * satrec.d3 + 6.0 * satrec.d2 * satrec.d2 +
                15.0 * cc1sq * (2.0 * satrec.d2 + cc1sq))
        }
    }

    sgp4(satrec, 0.0)
    satrec.init = 'n'
}

private class Dscom(
    val sinim: Double, val cosim: Double, val em: Double, val emsq: Double, val nm: Double,
    val s1: Double, val s2: Double, val s3: Double, val s4: Double, val s5: Double,
    val ss1: Double, val ss2: Double, val ss3: Double, val ss4: Double, val ss5: Double,
    val sz1: Double, val sz3: Double, val sz11: Double, val sz13: Double, val sz21: Double, val sz23: Double,
    val sz31: Double, val sz33: Double,
    val z1: Double, val z3: Double, val z11: Double, val z13: Double, val z21: Double, val z23: Double,
    val z31: Double, val z33: Double,
    val e3: Double, val ee2: Double, val peo: Double, val pgho: Double, val pho: Double, val pinco: Double, val plo: Double,
    val se2: Double, val se3: Double, val sgh2: Double, val sgh3: Double, val sgh4: Double, val sh2: Double, val sh3: Double,
    val si2: Double, val si3: Double, val sl2: Double, val sl3: Double, val sl4: Double,
    val xgh2: Double, val xgh3: Double, val xgh4: Double, val xh2: Double, val xh3: Double, val xi2: Double, val xi3: Double,
    val xl2: Double, val xl3: Double, val xl4: Double, val zmol: Double, val zmos: Double,
)

private fun dscom(epoch: Double, ep: Double, argpp: Double, tc: Double, inclp: Double, nodep: Double, np: Double): Dscom {
    val zes = 0.01675
    val zel = 0.05490
    val c1ss = 2.9864797e-6
    val c1l = 4.7968065e-7
    val zsinis = 0.39785416
    val zcosis = 0.91744867
    val zcosgs = 0.1945905
    val zsings = -0.98088458

    val nm = np
    val em = ep
    val snodm = sin(nodep)
    val cnodm = cos(nodep)
    val sinomm = sin(argpp)
    val cosomm = cos(argpp)
    val sinim = sin(inclp)
    val cosim = cos(inclp)
    val emsq = em * em
    val betasq = 1.0 - emsq
    val rtemsq = sqrt(betasq)

    val day = epoch + 18261.5 + tc / 1440.0
    val xnodce = (4.5236020 - 9.2422029e-4 * day).jmod(TWO_PI)
    val stem = sin(xnodce)
    val ctem = cos(xnodce)
    val zcosil = 0.91375164 - 0.03568096 * ctem
    val zsinil = sqrt(1.0 - zcosil * zcosil)
    val zsinhl = 0.089683511 * stem / zsinil
    val zcoshl = sqrt(1.0 - zsinhl * zsinhl)
    val gam = 5.8351514 + 0.0019443680 * day
    var zx = 0.39785416 * stem / zsinil
    val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
    zx = atan2(zx, zy)
    zx += gam - xnodce
    val zcosgl = cos(zx)
    val zsingl = sin(zx)

    var zcosg = zcosgs
    var zsing = zsings
    var zcosi = zcosis
    var zsini = zsinis
    var zcosh = cnodm
    var zsinh = snodm
    var cc = c1ss
    val xnoi = 1.0 / nm

    var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var s5 = 0.0; var s6 = 0.0; var s7 = 0.0
    var ss1 = 0.0; var ss2 = 0.0; var ss3 = 0.0; var ss4 = 0.0; var ss5 = 0.0; var ss6 = 0.0; var ss7 = 0.0
    var sz1 = 0.0; var sz2 = 0.0; var sz3 = 0.0; var sz11 = 0.0; var sz12 = 0.0; var sz13 = 0.0
    var sz21 = 0.0; var sz22 = 0.0; var sz23 = 0.0; var sz31 = 0.0; var sz32 = 0.0; var sz33 = 0.0
    var z1 = 0.0; var z2 = 0.0; var z3 = 0.0; var z11 = 0.0; var z12 = 0.0; var z13 = 0.0
    var z21 = 0.0; var z22 = 0.0; var z23 = 0.0; var z31 = 0.0; var z32 = 0.0; var z33 = 0.0

    for (lsflg in 1..2) {
        val a1 = zcosg * zcosh + zsing * zcosi * zsinh
        val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
        val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
        val a8 = zsing * zsini
        val a9 = zsing * zsinh + zcosg * zcosi * zcosh
        val a10 = zcosg * zsini
        val a2 = cosim * a7 + sinim * a8
        val a4 = cosim * a9 + sinim * a10
        val a5 = -sinim * a7 + cosim * a8
        val a6 = -sinim * a9 + cosim * a10

        val x1 = a1 * cosomm + a2 * sinomm
        val x2 = a3 * cosomm + a4 * sinomm
        val x3 = -a1 * sinomm + a2 * cosomm
        val x4 = -a3 * sinomm + a4 * cosomm
        val x5 = a5 * sinomm
        val x6 = a6 * sinomm
        val x7 = a5 * cosomm
        val x8 = a6 * cosomm

        z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3
        z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4
        z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4

        z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq
        z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq
        z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq

        z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5)
        z12 = -6.0 * (a1 * a6 + a3 * a5) + emsq * (-24.0 * (x2 * x7 + x1 * x8) + -6.0 * (x3 * x6 + x4 * x5))
        z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6)

        z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7)
        z22 = 6.0 * (a4 * a5 + a2 * a6) + emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8))
        z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8)

        z1 = z1 + z1 + betasq * z31
        z2 = z2 + z2 + betasq * z32
        z3 = z3 + z3 + betasq * z33
        s3 = cc * xnoi
        s2 = -0.5 * s3 / rtemsq
        s4 = s3 * rtemsq
        s1 = -15.0 * em * s4
        s5 = x1 * x3 + x2 * x4
        s6 = x2 * x3 + x1 * x4
        s7 = x2 * x4 - x1 * x3

        if (lsflg == 1) {
            ss1 = s1; ss2 = s2; ss3 = s3; ss4 = s4; ss5 = s5; ss6 = s6; ss7 = s7
            sz1 = z1; sz2 = z2; sz3 = z3
            sz11 = z11; sz12 = z12; sz13 = z13
            sz21 = z21; sz22 = z22; sz23 = z23
            sz31 = z31; sz32 = z32; sz33 = z33
            zcosg = zcosgl
            zsing = zsingl
            zcosi = zcosil
            zsini = zsinil
            zcosh = zcoshl * cnodm + zsinhl * snodm
            zsinh = snodm * zcoshl - cnodm * zsinhl
            cc = c1l
        }
    }

    val zmol = (4.7199672 + (0.22997150 * day - gam)).jmod(TWO_PI)
    val zmos = (6.2565837 + 0.017201977 * day).jmod(TWO_PI)

    return Dscom(
        sinim = sinim, cosim = cosim, em = em, emsq = emsq, nm = nm,
        s1 = s1, s2 = s2, s3 = s3, s4 = s4, s5 = s5,
        ss1 = ss1, ss2 = ss2, ss3 = ss3, ss4 = ss4, ss5 = ss5,
        sz1 = sz1, sz3 = sz3, sz11 = sz11, sz13 = sz13, sz21 = sz21, sz23 = sz23, sz31 = sz31, sz33 = sz33,
        z1 = z1, z3 = z3, z11 = z11, z13 = z13, z21 = z21, z23 = z23, z31 = z31, z33 = z33,
        e3 = 2.0 * s1 * s7, ee2 = 2.0 * s1 * s6,
        peo = 0.0, pgho = 0.0, pho = 0.0, pinco = 0.0, plo = 0.0,
        se2 = 2.0 * ss1 * ss6, se3 = 2.0 * ss1 * ss7,
        sgh2 = 2.0 * ss4 * sz32, sgh3 = 2.0 * ss4 * (sz33 - sz31), sgh4 = -18.0 * ss4 * zes,
        sh2 = -2.0 * ss2 * sz22, sh3 = -2.0 * ss2 * (sz23 - sz21),
        si2 = 2.0 * ss2 * sz12, si3 = 2.0 * ss2 * (sz13 - sz11),
        sl2 = -2.0 * ss3 * sz2, sl3 = -2.0 * ss3 * (sz3 - sz1), sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * zes,
        xgh2 = 2.0 * s4 * z32, xgh3 = 2.0 * s4 * (z33 - z31), xgh4 = -18.0 * s4 * zel,
        xh2 = -2.0 * s2 * z22, xh3 = -2.0 * s2 * (z23 - z21),
        xi2 = 2.0 * s2 * z12, xi3 = 2.0 * s2 * (z13 - z11),
        xl2 = -2.0 * s3 * z2, xl3 = -2.0 * s3 * (z3 - z1), xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * zel,
        zmol = zmol, zmos = zmos,
    )
}

private class Dpper(val ep: Double, val inclp: Double, val nodep: Double, val argpp: Double, val mp: Double)

private fun dpper(
    s: Satrec,
    init: Char,
    epIn: Double,
    inclpIn: Double,
    nodepIn: Double,
    argppIn: Double,
    mpIn: Double,
    opsmode: Char,
): Dpper {
    var ep = epIn
    var inclp = inclpIn
    var nodep = nodepIn
    var argpp = argppIn
    var mp = mpIn

    val zns = 1.19459e-5
    val zes = 0.01675
    val znl = 1.5835218e-4
    val zel = 0.05490

    var zm = if (init == 'y') s.zmos else s.zmos + zns * s.t
    var zf = zm + 2.0 * zes * sin(zm)
    var sinzf = sin(zf)
    var f2 = 0.5 * sinzf * sinzf - 0.25
    var f3 = -0.5 * sinzf * cos(zf)
    val ses = s.se2 * f2 + s.se3 * f3
    val sis = s.si2 * f2 + s.si3 * f3
    val sls = s.sl2 * f2 + s.sl3 * f3 + s.sl4 * sinzf
    val sghs = s.sgh2 * f2 + s.sgh3 * f3 + s.sgh4 * sinzf
    val shs = s.sh2 * f2 + s.sh3 * f3

    zm = if (init == 'y') s.zmol else s.zmol + znl * s.t
    zf = zm + 2.0 * zel * sin(zm)
    sinzf = sin(zf)
    f2 = 0.5 * sinzf * sinzf - 0.25
    f3 = -0.5 * sinzf * cos(zf)
    val sel = s.ee2 * f2 + s.e3 * f3
    val sil = s.xi2 * f2 + s.xi3 * f3
    val sll = s.xl2 * f2 + s.xl3 * f3 + s.xl4 * sinzf
    val sghl = s.xgh2 * f2 + s.xgh3 * f3 + s.xgh4 * sinzf
    val shll = s.xh2 * f2 + s.xh3 * f3

    var pe = ses + sel
    var pinc = sis + sil
    var pl = sls + sll
    var pgh = sghs + sghl
    var ph = shs + shll

    if (init == 'n') {
        pe -= s.peo
        pinc -= s.pinco
        pl -= s.plo
        pgh -= s.pgho
        ph -= s.pho
        inclp += pinc
        ep += pe
        val sinip = sin(inclp)
        val cosip = cos(inclp)

        if (inclp >= 0.2) {
            ph /= sinip
            pgh -= cosip * ph
            argpp += pgh
            nodep += ph
            mp += pl
        } else {
            val sinop = sin(nodep)
            val cosop = cos(nodep)
            var alfdp = sinip * sinop
            var betdp = sinip * cosop
            val dalf = ph * cosop + pinc * cosip * sinop
            val dbet = -ph * sinop + pinc * cosip * cosop
            alfdp += dalf
            betdp += dbet
            nodep = nodep.jmod(TWO_PI)
            if (nodep < 0.0 && opsmode == 'a') nodep += TWO_PI
            var xls = mp + argpp + cosip * nodep
            val dls = pl + pgh - pinc * nodep * sinip
            xls += dls
            val xnoh = nodep
            nodep = atan2(alfdp, betdp)
            if (nodep < 0.0 && opsmode == 'a') nodep += TWO_PI
            if (abs(xnoh - nodep) > PI) {
                if (nodep < xnoh) nodep += TWO_PI else nodep -= TWO_PI
            }
            mp += pl
            argpp = xls - mp - cosip * nodep
        }
    }
    return Dpper(ep, inclp, nodep, argpp, mp)
}

private fun dsinit(s: Satrec, ds: Dscom, tc: Double, xpidot: Double, eccsq: Double, inclmIn: Double, nmIn: Double) {
    val q22 = 1.7891679e-6
    val q31 = 2.1460748e-6
    val q33 = 2.2123015e-7
    val root22 = 1.7891679e-6
    val root44 = 7.3636953e-9
    val root54 = 2.1765803e-9
    val rptim = 4.37526908801129966e-3
    val root32 = 3.7393792e-7
    val root52 = 1.1428639e-7
    val znl = 1.5835218e-4
    val zns = 1.19459e-5

    val cosim = ds.cosim
    val sinim = ds.sinim
    var em = ds.em
    var emsq = ds.emsq
    val inclm = inclmIn
    val nm = nmIn

    s.irez = 0
    if (nm < 0.0052359877 && nm > 0.0034906585) s.irez = 1
    if (nm >= 8.26e-3 && nm <= 9.24e-3 && em >= 0.5) s.irez = 2

    // Solar terms
    val ses = ds.ss1 * zns * ds.ss5
    val sis = ds.ss2 * zns * (ds.sz11 + ds.sz13)
    val sls = -zns * ds.ss3 * (ds.sz1 + ds.sz3 - 14.0 - 6.0 * emsq)
    val sghs = ds.ss4 * zns * (ds.sz31 + ds.sz33 - 6.0)
    var shs = -zns * ds.ss2 * (ds.sz21 + ds.sz23)
    if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shs = 0.0
    if (sinim != 0.0) shs /= sinim
    val sgs = sghs - cosim * shs

    // Lunar terms
    s.dedt = ses + ds.s1 * znl * ds.s5
    s.didt = sis + ds.s2 * znl * (ds.z11 + ds.z13)
    s.dmdt = sls - znl * ds.s3 * (ds.z1 + ds.z3 - 14.0 - 6.0 * emsq)
    val sghl = ds.s4 * znl * (ds.z31 + ds.z33 - 6.0)
    var shll = -znl * ds.s2 * (ds.z21 + ds.z23)
    if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shll = 0.0
    s.domdt = sgs + sghl
    s.dnodt = shs
    if (sinim != 0.0) {
        s.domdt -= cosim / sinim * shll
        s.dnodt += shll / sinim
    }

    // Resonance terms (t = 0 at init, so the secular updates to em/inclm/etc. are no-ops here)
    val theta = (s.gsto + tc * rptim).jmod(TWO_PI)

    if (s.irez != 0) {
        val aonv = (nm / Wgs72.XKE).pow(X2O3)

        if (s.irez == 2) {
            val cosisq = cosim * cosim
            val emo = em
            em = s.ecco
            val emsqo = emsq
            emsq = eccsq
            val eoc = em * emsq
            val g201 = -0.306 - (em - 0.64) * 0.440
            val g211: Double; val g310: Double; val g322: Double; val g410: Double; val g422: Double; val g520: Double
            if (em <= 0.65) {
                g211 = 3.616 - 13.2470 * em + 16.2900 * emsq
                g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc
                g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc
                g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc
                g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc
                g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc
            } else {
                g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc
                g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc
                g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc
                g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc
                g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc
                g520 = if (em > 0.715) {
                    -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc
                } else {
                    1464.74 - 4664.75 * em + 3763.64 * emsq
                }
            }
            val g533: Double; val g521: Double; val g532: Double
            if (em < 0.7) {
                g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc
                g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc
                g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc
            } else {
                g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc
                g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc
                g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc
            }
            val sini2 = sinim * sinim
            val f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq)
            val f221 = 1.5 * sini2
            val f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq)
            val f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq)
            val f441 = 35.0 * sini2 * f220
            val f442 = 39.3750 * sini2 * sini2
            val f522 = 9.84375 * sinim * (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) +
                0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq))
            val f523 = sinim * (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) +
                6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq))
            val f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq))
            val f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq))

            val xno2 = nm * nm
            val ainv2 = aonv * aonv
            var temp1 = 3.0 * xno2 * ainv2
            var temp = temp1 * root22
            s.d2201 = temp * f220 * g201
            s.d2211 = temp * f221 * g211
            temp1 *= aonv
            temp = temp1 * root32
            s.d3210 = temp * f321 * g310
            s.d3222 = temp * f322 * g322
            temp1 *= aonv
            temp = 2.0 * temp1 * root44
            s.d4410 = temp * f441 * g410
            s.d4422 = temp * f442 * g422
            temp1 *= aonv
            temp = temp1 * root52
            s.d5220 = temp * f522 * g520
            s.d5232 = temp * f523 * g532
            temp = 2.0 * temp1 * root54
            s.d5421 = temp * f542 * g521
            s.d5433 = temp * f543 * g533
            s.xlamo = (s.mo + s.nodeo + s.nodeo - (theta + theta)).jmod(TWO_PI)
            s.xfact = s.mdot + s.dmdt + 2.0 * (s.nodedot + s.dnodt - rptim) - s.no
            em = emo
            emsq = emsqo
        }

        if (s.irez == 1) {
            val g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq)
            val g310 = 1.0 + 2.0 * emsq
            val g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq)
            val f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim)
            val f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim)
            var f330 = 1.0 + cosim
            f330 *= 1.875 * f330 * f330
            var del1 = 3.0 * nm * nm * aonv * aonv
            s.del2 = 2.0 * del1 * f220 * g200 * q22
            s.del3 = 3.0 * del1 * f330 * g300 * q33 * aonv
            del1 = del1 * f311 * g310 * q31 * aonv
            s.del1 = del1
            s.xlamo = (s.mo + s.nodeo + s.argpo - theta).jmod(TWO_PI)
            s.xfact = s.mdot + xpidot + s.dmdt + s.domdt + s.dnodt - (s.no + rptim)
        }

        s.xli = s.xlamo
        s.xni = s.no
        s.atime = 0.0
    }
}

private class Dspace(val em: Double, val argpm: Double, val inclm: Double, val mm: Double, val nodem: Double, val nm: Double)

private fun dspace(s: Satrec, tc: Double, emIn: Double, argpmIn: Double, inclmIn: Double, mmIn: Double, nodemIn: Double, nmIn: Double): Dspace {
    val fasx2 = 0.13130908
    val fasx4 = 2.8843198
    val fasx6 = 0.37448087
    val g22 = 5.7686396
    val g32 = 0.95240898
    val g44 = 1.8014998
    val g52 = 1.0508330
    val g54 = 4.4108898
    val rptim = 4.37526908801129966e-3
    val stepp = 720.0
    val stepn = -720.0
    val step2 = 259200.0

    val t = s.t
    var em = emIn
    var argpm = argpmIn
    var inclm = inclmIn
    var mm = mmIn
    var nodem = nodemIn
    var nm = nmIn

    var ft = 0.0
    val theta = (s.gsto + tc * rptim).jmod(TWO_PI)
    em += s.dedt * t
    inclm += s.didt * t
    argpm += s.domdt * t
    nodem += s.dnodt * t
    mm += s.dmdt * t

    if (s.irez != 0) {
        if (s.atime == 0.0 || t * s.atime <= 0.0 || abs(t) < abs(s.atime)) {
            s.atime = 0.0
            s.xni = s.no
            s.xli = s.xlamo
        }
        val delt = if (t > 0.0) stepp else stepn

        var xndt = 0.0
        var xldot = 0.0
        var xnddt = 0.0
        var iretn = 381
        while (iretn == 381) {
            if (s.irez != 2) {
                xndt = s.del1 * sin(s.xli - fasx2) + s.del2 * sin(2.0 * (s.xli - fasx4)) + s.del3 * sin(3.0 * (s.xli - fasx6))
                xldot = s.xni + s.xfact
                xnddt = s.del1 * cos(s.xli - fasx2) + 2.0 * s.del2 * cos(2.0 * (s.xli - fasx4)) +
                    3.0 * s.del3 * cos(3.0 * (s.xli - fasx6))
                xnddt *= xldot
            } else {
                val xomi = s.argpo + s.argpdot * s.atime
                val x2omi = xomi + xomi
                val x2li = s.xli + s.xli
                xndt = s.d2201 * sin(x2omi + s.xli - g22) + s.d2211 * sin(s.xli - g22) +
                    s.d3210 * sin(xomi + s.xli - g32) + s.d3222 * sin(-xomi + s.xli - g32) +
                    s.d4410 * sin(x2omi + x2li - g44) + s.d4422 * sin(x2li - g44) +
                    s.d5220 * sin(xomi + s.xli - g52) + s.d5232 * sin(-xomi + s.xli - g52) +
                    s.d5421 * sin(xomi + x2li - g54) + s.d5433 * sin(-xomi + x2li - g54)
                xldot = s.xni + s.xfact
                xnddt = s.d2201 * cos(x2omi + s.xli - g22) + s.d2211 * cos(s.xli - g22) +
                    s.d3210 * cos(xomi + s.xli - g32) + s.d3222 * cos(-xomi + s.xli - g32) +
                    s.d5220 * cos(xomi + s.xli - g52) + s.d5232 * cos(-xomi + s.xli - g52) +
                    2.0 * (s.d4410 * cos(x2omi + x2li - g44) + s.d4422 * cos(x2li - g44) +
                        s.d5421 * cos(xomi + x2li - g54) + s.d5433 * cos(-xomi + x2li - g54))
                xnddt *= xldot
            }

            if (abs(t - s.atime) >= stepp) {
                iretn = 381
            } else {
                ft = t - s.atime
                iretn = 0
            }
            if (iretn == 381) {
                s.xli += xldot * delt + xndt * step2
                s.xni += xndt * delt + xnddt * step2
                s.atime += delt
            }
        }

        nm = s.xni + xndt * ft + xnddt * ft * ft * 0.5
        val xl = s.xli + xldot * ft + xndt * ft * ft * 0.5
        mm = if (s.irez != 1) xl - 2.0 * nodem + 2.0 * theta else xl - nodem - argpm + theta
        val dndt = nm - s.no
        nm = s.no + dndt
    }
    return Dspace(em, argpm, inclm, mm, nodem, nm)
}

private fun sgp4(satrec: Satrec, tsince: Double): StateVector? {
    val temp4 = 1.5e-12
    satrec.t = tsince
    satrec.error = 0

    val xmdf = satrec.mo + satrec.mdot * satrec.t
    val argpdf = satrec.argpo + satrec.argpdot * satrec.t
    val nodedf = satrec.nodeo + satrec.nodedot * satrec.t
    var argpm = argpdf
    var mm = xmdf
    val t2 = satrec.t * satrec.t
    var nodem = nodedf + satrec.nodecf * t2
    var tempa = 1.0 - satrec.cc1 * satrec.t
    var tempe = satrec.bstar * satrec.cc4 * satrec.t
    var templ = satrec.t2cof * t2

    if (satrec.isimp != 1) {
        val delomg = satrec.omgcof * satrec.t
        val delmtemp = 1.0 + satrec.eta * cos(xmdf)
        val delm = satrec.xmcof * (delmtemp * delmtemp * delmtemp - satrec.delmo)
        val temp = delomg + delm
        mm = xmdf + temp
        argpm = argpdf - temp
        val t3 = t2 * satrec.t
        val t4 = t3 * satrec.t
        tempa = tempa - satrec.d2 * t2 - satrec.d3 * t3 - satrec.d4 * t4
        tempe += satrec.bstar * satrec.cc5 * (sin(mm) - satrec.sinmao)
        templ = templ + satrec.t3cof * t3 + t4 * (satrec.t4cof + satrec.t * satrec.t5cof)
    }

    var nm = satrec.no
    var em = satrec.ecco
    var inclm = satrec.inclo
    if (satrec.method == 'd') {
        val tc = satrec.t
        val d = dspace(satrec, tc, em, argpm, inclm, mm, nodem, nm)
        em = d.em; argpm = d.argpm; inclm = d.inclm; mm = d.mm; nodem = d.nodem; nm = d.nm
    }

    if (nm <= 0.0) {
        satrec.error = 2
        return null
    }

    val am = (Wgs72.XKE / nm).pow(X2O3) * tempa * tempa
    nm = Wgs72.XKE / am.pow(1.5)
    em -= tempe

    if (em >= 1.0 || em < -0.001) {
        satrec.error = 1
        return null
    }
    if (em < 1.0e-6) em = 1.0e-6

    mm += satrec.no * templ
    var xlm = mm + argpm + nodem
    nodem = nodem.jmod(TWO_PI)
    argpm = argpm.jmod(TWO_PI)
    xlm = xlm.jmod(TWO_PI)
    mm = (xlm - argpm - nodem).jmod(TWO_PI)

    val sinim = sin(inclm)
    val cosim = cos(inclm)

    var ep = em
    var xincp = inclm
    var argpp = argpm
    var nodep = nodem
    var mp = mm
    var sinip = sinim
    var cosip = cosim

    if (satrec.method == 'd') {
        val dp = dpper(satrec, 'n', ep, xincp, nodep, argpp, mp, satrec.operationmode)
        ep = dp.ep; nodep = dp.nodep; argpp = dp.argpp; mp = dp.mp; xincp = dp.inclp
        if (xincp < 0.0) {
            xincp = -xincp
            nodep += PI
            argpp -= PI
        }
        if (ep < 0.0 || ep > 1.0) {
            satrec.error = 3
            return null
        }
    }

    if (satrec.method == 'd') {
        sinip = sin(xincp)
        cosip = cos(xincp)
        satrec.aycof = -0.5 * Wgs72.J3OJ2 * sinip
        satrec.xlcof = if (abs(cosip + 1.0) > 1.5e-12) {
            -0.25 * Wgs72.J3OJ2 * sinip * (3.0 + 5.0 * cosip) / (1.0 + cosip)
        } else {
            -0.25 * Wgs72.J3OJ2 * sinip * (3.0 + 5.0 * cosip) / temp4
        }
    }

    val axnl = ep * cos(argpp)
    var temp = 1.0 / (am * (1.0 - ep * ep))
    val aynl = ep * sin(argpp) + temp * satrec.aycof
    val xl = mp + argpp + nodep + temp * satrec.xlcof * axnl

    // Kepler's equation
    val u = (xl - nodep).jmod(TWO_PI)
    var eo1 = u
    var tem5 = 9999.9
    var ktr = 1
    var sineo1 = 0.0
    var coseo1 = 0.0
    while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
        sineo1 = sin(eo1)
        coseo1 = cos(eo1)
        tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
        tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
        if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
        eo1 += tem5
        ktr += 1
    }

    val ecose = axnl * coseo1 + aynl * sineo1
    val esine = axnl * sineo1 - aynl * coseo1
    val el2 = axnl * axnl + aynl * aynl
    val pl = am * (1.0 - el2)
    if (pl < 0.0) {
        satrec.error = 4
        return null
    }

    val rl = am * (1.0 - ecose)
    val rdotl = sqrt(am) * esine / rl
    val rvdotl = sqrt(pl) / rl
    val betal = sqrt(1.0 - el2)
    temp = esine / (1.0 + betal)
    val sinu = am / rl * (sineo1 - aynl - axnl * temp)
    val cosu = am / rl * (coseo1 - axnl + aynl * temp)
    var su = atan2(sinu, cosu)
    val sin2u = (cosu + cosu) * sinu
    val cos2u = 1.0 - 2.0 * sinu * sinu
    temp = 1.0 / pl
    val temp1 = 0.5 * Wgs72.J2 * temp
    val temp2 = temp1 * temp

    if (satrec.method == 'd') {
        val cosisq = cosip * cosip
        satrec.con41 = 3.0 * cosisq - 1.0
        satrec.x1mth2 = 1.0 - cosisq
        satrec.x7thm1 = 7.0 * cosisq - 1.0
    }

    val mrt = rl * (1.0 - 1.5 * temp2 * betal * satrec.con41) + 0.5 * temp1 * satrec.x1mth2 * cos2u
    if (mrt < 1.0) {
        // Decayed: below the Earth's surface.
        satrec.error = 6
        return null
    }

    su -= 0.25 * temp2 * satrec.x7thm1 * sin2u
    val xnode = nodep + 1.5 * temp2 * cosip * sin2u
    val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
    val mvt = rdotl - nm * temp1 * satrec.x1mth2 * sin2u / Wgs72.XKE
    val rvdot = rvdotl + nm * temp1 * (satrec.x1mth2 * cos2u + 1.5 * satrec.con41) / Wgs72.XKE

    val sinsu = sin(su)
    val cossu = cos(su)
    val snod = sin(xnode)
    val cnod = cos(xnode)
    val sini = sin(xinc)
    val cosi = cos(xinc)
    val xmx = -snod * cosi
    val xmy = cnod * cosi
    val ux = xmx * sinsu + cnod * cossu
    val uy = xmy * sinsu + snod * cossu
    val uz = sini * sinsu
    val vx = xmx * cossu - cnod * sinsu
    val vy = xmy * cossu - snod * sinsu
    val vz = sini * cossu

    val r = Vec3(mrt * ux * Wgs72.EARTH_RADIUS, mrt * uy * Wgs72.EARTH_RADIUS, mrt * uz * Wgs72.EARTH_RADIUS)
    val v = Vec3(
        (mvt * ux + rvdot * vx) * Wgs72.VKMPERSEC,
        (mvt * uy + rvdot * vy) * Wgs72.VKMPERSEC,
        (mvt * uz + rvdot * vz) * Wgs72.VKMPERSEC,
    )
    return StateVector(r, v)
}
