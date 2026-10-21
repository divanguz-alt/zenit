package io.github.divanguz_alt.zenit.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

data class SensorAvailability(
    val rotation: Boolean,
    val magnetic: Boolean,
    val linearAcceleration: Boolean,
    val gyroscope: Boolean,
    val pressure: Boolean,
    val stepCounter: Boolean,
)

data class SensorSnapshot(
    val available: SensorAvailability,
    /** Heading relative to magnetic north, degrees [0, 360). */
    val headingMagneticDeg: Float? = null,
    /** Positive = top edge tilted up. */
    val pitchDeg: Float? = null,
    /** Positive = tilted right. */
    val rollDeg: Float? = null,
    val magHorizontalUt: Float? = null,
    val magTotalUt: Float? = null,
    val accelG: Float? = null,
    val rotationDps: Float? = null,
    /** +1 turning left (counter-clockwise about the screen normal), -1 turning right. */
    val rotationDirection: Int = 0,
    val pressureHpa: Float? = null,
    /** Linear-regression slope over the last 15 minutes, hPa per hour. */
    val pressureTrendHpaPerHour: Float? = null,
    val stepsSinceBoot: Long? = null,
)

/** Sensor fusion and filtering. Emits at most [EMIT_INTERVAL_MS] apart (10 Hz). */
@Singleton
class SensorRepository @Inject constructor(@ApplicationContext context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnetic = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val pressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val steps = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val availability = SensorAvailability(
        rotation = rotation != null,
        magnetic = magnetic != null,
        linearAcceleration = linear != null,
        gyroscope = gyro != null,
        pressure = pressure != null,
        stepCounter = steps != null,
    )

    /** Pressure history survives collector restarts so the trend stays meaningful. */
    private val pressureTrend = PressureTrend()

    fun updates(includeSteps: Boolean): Flow<SensorSnapshot> = callbackFlow {
        val thread = HandlerThread("zenit-sensors").apply { start() }
        val handler = Handler(thread.looper)
        var snap = SensorSnapshot(availability)
        var lastEmit = 0L
        val rot = FloatArray(9)
        val remapped = FloatArray(9)
        val ori = FloatArray(3)
        var haveRotation = false
        var accelFiltered = Float.NaN
        var gyroFiltered = Float.NaN

        fun maybeEmit(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (force || now - lastEmit >= EMIT_INTERVAL_MS) {
                lastEmit = now
                trySend(snap)
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rot, e.values)
                        haveRotation = true
                        SensorManager.getOrientation(rot, ori)
                        val pitch = -Math.toDegrees(ori[1].toDouble()).toFloat()
                        val roll = Math.toDegrees(ori[2].toDouble()).toFloat()
                        // Held upright (camera facing the horizon) the flat-phone azimuth degenerates;
                        // use the direction the back of the phone points instead.
                        val azimuth = if (abs(pitch) > 45f) {
                            SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                            val o2 = FloatArray(3)
                            SensorManager.getOrientation(remapped, o2)
                            o2[0]
                        } else {
                            ori[0]
                        }
                        val heading = ((Math.toDegrees(azimuth.toDouble()) + 360.0) % 360.0).toFloat()
                        snap = snap.copy(headingMagneticDeg = heading, pitchDeg = pitch, rollDeg = roll)
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                        val total = sqrt(x * x + y * y + z * z)
                        val horizontal = if (haveRotation) {
                            val vertical = rot[6] * x + rot[7] * y + rot[8] * z
                            sqrt((total * total - vertical * vertical).coerceAtLeast(0f))
                        } else {
                            null
                        }
                        snap = snap.copy(magTotalUt = total, magHorizontalUt = horizontal)
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.STANDARD_GRAVITY
                        accelFiltered = if (accelFiltered.isNaN()) g else accelFiltered + LOW_PASS * (g - accelFiltered)
                        snap = snap.copy(accelG = accelFiltered)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                        val dps = Math.toDegrees(sqrt(x * x + y * y + z * z).toDouble()).toFloat()
                        gyroFiltered = if (gyroFiltered.isNaN()) dps else gyroFiltered + LOW_PASS * (dps - gyroFiltered)
                        val dir = when {
                            z > 0.02f -> 1
                            z < -0.02f -> -1
                            else -> 0
                        }
                        snap = snap.copy(rotationDps = gyroFiltered, rotationDirection = dir)
                    }
                    Sensor.TYPE_PRESSURE -> {
                        val hpa = e.values[0]
                        pressureTrend.add(SystemClock.elapsedRealtime(), hpa)
                        snap = snap.copy(pressureHpa = hpa, pressureTrendHpaPerHour = pressureTrend.slopePerHour())
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        snap = snap.copy(stepsSinceBoot = e.values[0].toLong())
                        maybeEmit(force = true)
                        return
                    }
                }
                maybeEmit()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        val delay = SensorManager.SENSOR_DELAY_UI
        rotation?.let { sm.registerListener(listener, it, delay, handler) }
        magnetic?.let { sm.registerListener(listener, it, delay, handler) }
        linear?.let { sm.registerListener(listener, it, delay, handler) }
        gyro?.let { sm.registerListener(listener, it, delay, handler) }
        pressure?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        if (includeSteps) steps?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        trySend(snap)

        awaitClose {
            sm.unregisterListener(listener)
            thread.quitSafely()
        }
    }.conflate()

    companion object {
        const val EMIT_INTERVAL_MS = 100L
        private const val LOW_PASS = 0.15f
    }
}

/** Sliding linear regression of pressure over the last 15 minutes, sampled every 10 s. */
class PressureTrend(private val windowMs: Long = 15 * 60_000L, private val sampleEveryMs: Long = 10_000L) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Float>()

    @Synchronized
    fun add(timeMs: Long, hpa: Float) {
        if (times.isNotEmpty() && timeMs - times.last() < sampleEveryMs) return
        times.addLast(timeMs)
        values.addLast(hpa)
        while (times.isNotEmpty() && timeMs - times.first() > windowMs) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    /** hPa per hour, or null until at least two minutes of data exist. */
    @Synchronized
    fun slopePerHour(): Float? {
        if (times.size < 3 || times.last() - times.first() < 120_000L) return null
        val t0 = times.first()
        val n = times.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val x = (times[i] - t0) / 3_600_000.0
            val y = values[i].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val den = n * sxx - sx * sx
        if (den == 0.0) return null
        return ((n * sxy - sx * sy) / den).toFloat()
    }
}
