package com.example.wearairmouse

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Детектор жестов на основе гироскопа и акселерометра.
 * Использует фильтрацию EMA и deadzone для плавного управления.
 */
class GestureDetector(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "WearAirMouse"
        
        // Пороги жестов (будут переопределены из SharedPreferences)
        private const val DEFAULT_ACCEL_THRESHOLD = 4.5f      // м/с² для левого клика
        private const val DEFAULT_GYRO_THRESHOLD = 8.0f       // рад/с для правого клика
        private const val DEFAULT_HOLD_TIME_MS = 1500L        // мс для удержания
        private const val DEFAULT_DEADZONE = 0.15f            // мёртвая зона
        private const val EMA_ALPHA = 0.3f                    // коэффициент сглаживания
        
        // Биты кнопок мыши
        const val BUTTON_LEFT = 0x01
        const val BUTTON_RIGHT = 0x02
        const val BUTTON_MIDDLE = 0x04
    }

    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private val gyroscope: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    private val accelerometer: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("wearairmouse_prefs", Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())
    
    // Настройки из SharedPreferences
    var sensitivity: Float = 1.0f
        private set
    var deadzone: Float = DEFAULT_DEADZONE
        private set
    var gestureSensitivity: Float = 0.7f
        private set
    var enableGyro: Boolean = true
        private set
    var enableAccel: Boolean = true
        private set

    // Сглаженные значения гироскопа (EMA)
    private var gyroXSmooth = 0f
    private var gyroYSmooth = 0f
    
    // Последние значения акселерометра
    private var lastAccelMagnitude = 0f
    private var lastGyroX = 0f
    private var lastGyroY = 0f
    
    // Тайминги для детекции жестов
    private var lastGestureTime = 0L
    private var holdStartTime = 0L
    private var isHolding = false
    private var lastMotionTime = 0L
    
    // Debouncing
    private val gestureDebounceMs = 300L
    
    // Callbacks
    var onCursorMove: ((Float, Float) -> Unit)? = null
    var onLeftClick: (() -> Unit)? = null
    var onRightClick: (() -> Unit)? = null
    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null
    var onGestureDetected: ((String) -> Unit)? = null

    /**
     * Загрузка настроек из SharedPreferences
     */
    fun loadSettings() {
        sensitivity = prefs.getFloat("sensitivity", 1.0f)
        deadzone = prefs.getFloat("deadzone", DEFAULT_DEADZONE)
        gestureSensitivity = prefs.getFloat("gesture_sensitivity", 0.7f)
        enableGyro = prefs.getBoolean("enable_gyro", true)
        enableAccel = prefs.getBoolean("enable_accel", true)
        
        Log.d(TAG, "Settings loaded: sensitivity=$sensitivity, deadzone=$deadzone, gestureSensitivity=$gestureSensitivity")
    }

    /**
     * Регистрация сенсоров
     */
    fun register() {
        loadSettings()
        
        if (enableGyro && gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Gyroscope registered")
        }
        
        if (enableAccel && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Accelerometer registered")
        }
    }

    /**
     * Отписка от сенсоров
     */
    fun unregister() {
        sensorManager.unregisterListener(this)
        gyroXSmooth = 0f
        gyroYSmooth = 0f
        isHolding = false
        holdStartTime = 0L
        Log.d(TAG, "Sensors unregistered")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Не используем
    }

    private fun handleGyroscope(event: SensorEvent) {
        if (!enableGyro) return
        
        val rawX = event.values[0]
        val rawY = event.values[1]
        
        // Применяем EMA сглаживание
        gyroXSmooth = EMA_ALPHA * rawX + (1 - EMA_ALPHA) * gyroXSmooth
        gyroYSmooth = EMA_ALPHA * rawY + (1 - EMA_ALPHA) * gyroYSmooth
        
        // Применяем deadzone
        val x = applyDeadzone(gyroXSmooth, deadzone)
        val y = applyDeadzone(gyroYSmooth, deadzone)
        
        // Проверяем на удержание (отсутствие движения)
        val motionMagnitude = sqrt(rawX * rawX + rawY * rawY)
        val now = SystemClock.elapsedRealtime()
        
        if (motionMagnitude < 0.1f && !isHolding) {
            if (holdStartTime == 0L) {
                holdStartTime = now
            } else if (now - holdStartTime >= DEFAULT_HOLD_TIME_MS) {
                triggerHoldStart()
            }
        } else {
            holdStartTime = 0L
            if (isHolding) {
                triggerHoldEnd()
            }
        }
        
        // Проверяем на правый клик (резкий поворот)
        val gyroThreshold = DEFAULT_GYRO_THRESHOLD * (1.0f - gestureSensitivity * 0.5f)
        if (abs(rawX) > gyroThreshold && canTriggerGesture()) {
            triggerRightClick()
            return
        }
        
        // Отправляем движение курсора
        if (x != 0f || y != 0f) {
            lastMotionTime = now
            val scaledX = x * sensitivity * 2.0f
            val scaledY = -y * sensitivity * 2.0f // Инвертируем Y для естественного движения
            
            handler.post {
                onCursorMove?.invoke(scaledX, scaledY)
            }
        }
        
        lastGyroX = rawX
        lastGyroY = rawY
    }

    private fun handleAccelerometer(event: SensorEvent) {
        if (!enableAccel) return
        
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        
        // Вычисляем общую величину ускорения (без гравитации примерно)
        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        
        // Проверяем на левый клик (резкий взмах)
        val accelThreshold = DEFAULT_ACCEL_THRESHOLD * (1.0f - gestureSensitivity * 0.5f)
        if (magnitude > accelThreshold && canTriggerGesture()) {
            triggerLeftClick()
        }
        
        lastAccelMagnitude = magnitude
    }

    private fun applyDeadzone(value: Float, deadzone: Float): Float {
        return if (abs(value) < deadzone) 0f else value
    }

    private fun canTriggerGesture(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now - lastGestureTime >= gestureDebounceMs
    }

    private fun triggerLeftClick() {
        lastGestureTime = SystemClock.elapsedRealtime()
        Log.d(TAG, "Left click gesture detected")
        
        handler.post {
            onLeftClick?.invoke()
            onGestureDetected?.invoke("ЛКМ")
        }
    }

    private fun triggerRightClick() {
        lastGestureTime = SystemClock.elapsedRealtime()
        Log.d(TAG, "Right click gesture detected")
        
        handler.post {
            onRightClick?.invoke()
            onGestureDetected?.invoke("ПКМ")
        }
    }

    private fun triggerHoldStart() {
        isHolding = true
        Log.d(TAG, "Hold start gesture detected")
        
        handler.post {
            onHoldStart?.invoke()
            onGestureDetected?.invoke("Удержание")
        }
    }

    private fun triggerHoldEnd() {
        isHolding = false
        holdStartTime = 0L
        Log.d(TAG, "Hold end gesture detected")
        
        handler.post {
            onHoldEnd?.invoke()
            onGestureDetected?.invoke("")
        }
    }

    /**
     * Принудительный клик (от кнопки на экране)
     */
    fun triggerLeftClickManual() {
        handler.post {
            onLeftClick?.invoke()
            onGestureDetected?.invoke("ЛКМ (кнопка)")
        }
    }

    fun triggerRightClickManual() {
        handler.post {
            onRightClick?.invoke()
            onGestureDetected?.invoke("ПКМ (кнопка)")
        }
    }

    fun triggerHoldStartManual() {
        if (!isHolding) {
            isHolding = true
            handler.post {
                onHoldStart?.invoke()
                onGestureDetected?.invoke("Удержание (кнопка)")
            }
        }
    }

    fun triggerHoldEndManual() {
        if (isHolding) {
            isHolding = false
            holdStartTime = 0L
            handler.post {
                onHoldEnd?.invoke()
                onGestureDetected?.invoke("")
            }
        }
    }

    /**
     * Проверка состояния удержания
     */
    fun isCurrentlyHolding(): Boolean = isHolding
}
