package com.example.wearairmouse

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import com.example.wearairmouse.databinding.ActivityMainBinding

/**
 * Главное приложение AirMouse для Wear OS.
 * Управление курсором через жесты запястья, кнопки на экране.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WearAirMouse"
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var hidManager: HidManager
    private lateinit var gestureDetector: GestureDetector
    
    private val handler = Handler(Looper.getMainLooper())
    
    // Период отправки отчётов (60 FPS = 16ms)
    private val reportIntervalMs = 16L
    
    // Текущее состояние мыши
    private var currentButtons = 0
    private var pendingX = 0f
    private var pendingY = 0f
    private var isHoldActive = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All permissions granted")
            initializeBluetooth()
        } else {
            Log.e(TAG, "Some permissions denied")
            updateStatus("Нет разрешений")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Инициализация менеджеров
        hidManager = HidManager(this)
        gestureDetector = GestureDetector(this)
        
        // Настройка UI
        setupUI()
        
        // Проверка и запрос разрешений
        checkAndRequestPermissions()
    }

    private fun setupUI() {
        // Обновление статуса подключения
        hidManager.onConnectionStateChanged = { connected ->
            runOnUiThread {
                if (connected) {
                    binding.statusText.text = getString(R.string.status_connected)
                    binding.statusText.setTextColor(getColor(android.R.color.holo_green_light))
                } else {
                    binding.statusText.text = getString(R.string.status_disconnected)
                    binding.statusText.setTextColor(getColor(android.R.color.holo_orange_light))
                }
            }
        }
        
        hidManager.onError = { error ->
            runOnUiThread {
                binding.statusText.text = "Ошибка: $error"
            }
        }
        
        // Обработка жестов
        gestureDetector.onCursorMove = { x, y ->
            accumulateMotion(x, y)
        }
        
        gestureDetector.onLeftClick = {
            triggerClick(Gamepad.BUTTON_LEFT)
        }
        
        gestureDetector.onRightClick = {
            triggerClick(Gamepad.BUTTON_RIGHT)
        }
        
        gestureDetector.onHoldStart = {
            isHoldActive = true
            currentButtons = currentButtons or Gamepad.BUTTON_LEFT
            binding.gestureText.text = "Удержание..."
        }
        
        gestureDetector.onHoldEnd = {
            isHoldActive = false
            currentButtons = currentButtons and Gamepad.BUTTON_LEFT.inv()
            binding.gestureText.text = ""
            sendMouseReport() // Отпускаем кнопку
        }
        
        gestureDetector.onGestureDetected = { gesture ->
            runOnUiThread {
                binding.gestureText.text = gesture
                // Очищаем индикатор через 1 секунду
                handler.postDelayed({
                    if (!isHoldActive && binding.gestureText.text == gesture) {
                        binding.gestureText.text = ""
                    }
                }, 1000)
            }
        }
        
        // Кнопки на экране
        binding.btnLcm.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentButtons = currentButtons or Gamepad.BUTTON_LEFT
                    sendMouseReport()
                    binding.gestureText.text = "ЛКМ"
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentButtons = currentButtons and Gamepad.BUTTON_LEFT.inv()
                    sendMouseReport()
                    binding.gestureText.text = ""
                    true
                }
                else -> false
            }
        }
        
        binding.btnPcm.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentButtons = currentButtons or Gamepad.BUTTON_RIGHT
                    sendMouseReport()
                    binding.gestureText.text = "ПКМ"
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentButtons = currentButtons and Gamepad.BUTTON_RIGHT.inv()
                    sendMouseReport()
                    binding.gestureText.text = ""
                    true
                }
                else -> false
            }
        }
        
        binding.btnHold.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gestureDetector.triggerHoldStartManual()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gestureDetector.triggerHoldEndManual()
                    true
                }
                else -> false
            }
        }
        
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            Log.i(TAG, "All permissions already granted")
            initializeBluetooth()
        } else {
            Log.i(TAG, "Requesting permissions: ${missingPermissions.joinToString()}")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun initializeBluetooth() {
        // Проверяем включён ли Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            updateStatus("Bluetooth не поддерживается")
            return
        }
        
        if (!bluetoothAdapter.isEnabled) {
            updateStatus("Включите Bluetooth")
            return
        }
        
        // Инициализируем HID менеджер
        val initialized = hidManager.initializeWithProxy()
        
        if (initialized) {
            updateStatus("Ожидание подключения...")
            startMotionReporting()
        } else {
            updateStatus("Ошибка инициализации")
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }

    private fun accumulateMotion(x: Float, y: Float) {
        pendingX += x
        pendingY += y
    }

    private fun startMotionReporting() {
        // Регистрируем сенсоры
        gestureDetector.register()
        
        // Запускаем цикл отправки отчётов
        handler.post(reportRunnable)
    }

    private val reportRunnable = object : Runnable {
        override fun run() {
            sendMouseReport()
            handler.postDelayed(this, reportIntervalMs)
        }
    }

    private fun sendMouseReport() {
        // Округляем накопленное движение
        val x = pendingX.toInt().coerceIn(-127, 127)
        val y = pendingY.toInt().coerceIn(-127, 127)
        
        // Сбрасываем накопленное, но сохраняем остаток
        pendingX -= x
        pendingY -= y
        
        // Отправляем отчёт
        hidManager.sendMouseReport(currentButtons, x, y, 0)
    }

    private fun triggerClick(button: Int) {
        // Быстрый клик: нажимаем и отпускаем
        val originalButtons = currentButtons
        currentButtons = currentButtons or button
        sendMouseReport()
        
        handler.postDelayed({
            currentButtons = originalButtons
            sendMouseReport()
        }, 50)
    }

    override fun onResume() {
        super.onResume()
        // Перезагружаем настройки
        gestureDetector.loadSettings()
        // Перерегистрируем сенсоры
        if (::hidManager.isInitialized && hidManager.isDeviceConnected()) {
            gestureDetector.register()
        }
    }

    override fun onPause() {
        super.onPause()
        // Отписываемся от сенсоров
        gestureDetector.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Останавливаем цикл отправки
        handler.removeCallbacks(reportRunnable)
        // Отписываемся от сенсоров
        gestureDetector.unregister()
        // Освобождаем HID ресурсы
        hidManager.cleanup()
    }
}
