package app.brainfit.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Детектор тряски на акселерометре. Берёт датчик линейного ускорения (гравитация уже вычтена),
 * а если его нет — обычный акселерометр через [GravityFilter]. Разрешений не требует.
 * Вызывать с главного потока: события датчика приходят туда же.
 */
@Singleton
class SensorShakeMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : ShakeMonitor, SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val linearSensor = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val sensor = linearSensor ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val meter = ShakeMeter()
    private val gravity = GravityFilter()
    private var listening = false

    override fun start() {
        unregister()
        meter.reset()
        gravity.reset()
        register()
    }

    override fun pause() = unregister()

    override fun resume() = register()

    override fun stop(): Float? {
        unregister()
        return meter.rms().also { meter.reset() }
    }

    private fun register() {
        val s = sensor ?: return
        if (listening) return
        // После перерыва оценка гравитации устарела: телефон могли повернуть.
        gravity.reset()
        listening = manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME) == true
    }

    private fun unregister() {
        if (!listening) return
        manager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (linearSensor != null) {
            meter.add(v[0], v[1], v[2])
        } else {
            gravity.linear(v[0], v[1], v[2])?.let { meter.add(it[0], it[1], it[2]) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
